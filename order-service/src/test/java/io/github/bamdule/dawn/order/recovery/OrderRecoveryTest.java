package io.github.bamdule.dawn.order.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.bamdule.dawn.event.PaymentEvent;
import io.github.bamdule.dawn.event.PaymentEventType;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.order.OrderIntegrationTest;
import io.github.bamdule.dawn.order.OrderStatus;
import io.github.bamdule.dawn.order.PaymentAttempt;
import io.github.bamdule.dawn.order.client.ApproveOutcome;
import io.github.bamdule.dawn.order.client.PaymentClient.ApproveResult;
import io.github.bamdule.dawn.order.client.ReservationState;
import io.github.bamdule.dawn.order.client.ReservationStatus;
import io.github.bamdule.dawn.order.client.SealOutcome;
import io.github.bamdule.dawn.order.placement.OrderOutcome;
import io.github.bamdule.dawn.order.placement.OrderResult;
import io.github.bamdule.dawn.order.placement.PaymentAttempts;
import io.github.bamdule.dawn.order.store.NewOrder;
import io.github.bamdule.dawn.order.store.OrderRow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

class OrderRecoveryTest extends OrderIntegrationTest {

    @Autowired
    private OrderCleanup orderCleanup;

    @Autowired
    private PaymentEventConsumer paymentEventConsumer;

    @Autowired
    private PaymentAttempts paymentAttempts;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void 승인_응답을_받은_직후_PAID_커밋_전에_죽어도_재기동_뒤_PAID_와_PAID_이벤트가_한_번씩_생긴다() {
        // 결제 서비스는 승인했지만 주문 서비스가 응답을 받지 못한 채 멈춘 상태다
        when(paymentClient.approve(any(), anyInt(), anyLong(), any())).thenThrow(new ResourceAccessException("connection reset"));
        String orderNo = placeOne(10_000).orderNo();
        ageAttempt(orderNo, 61);
        when(paymentClient.seal(orderNo, 1)).thenReturn(SealOutcome.APPROVED);

        orderCleanup.run();
        orderCleanup.run();
        paymentEventConsumer.handle(approved(orderNo, 1, 10_000, false));

        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.PAID);
        assertThat(order(orderNo).paidRound()).isEqualTo(1);
        assertThat(outboxTypes(orderNo)).containsExactly("PAID");
        assertThat(refundKeys(orderNo)).isEmpty();
    }

    @Test
    void 실패_이벤트가_오면_정리_배치를_기다리지_않고_예약을_되돌려_시도를_끝낸다() {
        String orderNo = savePending();
        orderRepository.saveReservationExpiry(orderNo, LocalDateTime.now().plusMinutes(10));
        orderRepository.startAttempt(orderNo, 0);

        paymentEventConsumer.handle(failed(orderNo, 2));
        assertThat(order(orderNo).paymentAttempt()).as("다른 회차의 실패는 이 시도와 무관하다").isEqualTo(PaymentAttempt.IN_PROGRESS);

        paymentEventConsumer.handle(failed(orderNo, 1));

        verify(inventoryClient).revertPaying(orderNo, 1);
        assertThat(order(orderNo).paymentAttempt()).isEqualTo(PaymentAttempt.IDLE);
        assertThat(order(orderNo).status()).as("예약 유지 시간 안에 다음 회차를 받을 수 있다").isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void 실패_이벤트의_되돌림이_실패하면_시도를_남겨_정리_배치에_맡긴다() {
        String orderNo = savePending();
        orderRepository.saveReservationExpiry(orderNo, LocalDateTime.now().plusMinutes(10));
        orderRepository.startAttempt(orderNo, 0);
        when(inventoryClient.revertPaying(orderNo, 1)).thenThrow(new ResourceAccessException("connection reset"));

        paymentEventConsumer.handle(failed(orderNo, 1));

        assertThat(order(orderNo).paymentAttempt()).isEqualTo(PaymentAttempt.IN_PROGRESS);
    }

    @Test
    void 확정되지_않은_시도가_한_페이지를_넘게_쌓여도_한_주기에_뒤쪽_주문까지_닫는다() {
        List<String> stuck = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            String orderNo = savePending();
            orderRepository.startAttempt(orderNo, 0);
            stuck.add(orderNo);
        }
        jdbcClient.sql("UPDATE orders SET attempt_started_at = NOW(3) - INTERVAL 2 HOUR WHERE order_no IN (:orderNos)")
                .param("orderNos", stuck).update();
        when(paymentClient.seal(any(), anyInt())).thenReturn(SealOutcome.UNKNOWN);
        String last = savePending();
        orderRepository.startAttempt(last, 0);
        ageAttempt(last, 61);
        when(paymentClient.seal(last, 1)).thenReturn(SealOutcome.SEALED);

        orderCleanup.resolveStuckAttempts();

        assertThat(order(last).paymentAttempt()).as("앞의 501건이 결과를 몰라 남아도 뒤의 주문을 끝낸다").isEqualTo(PaymentAttempt.IDLE);
        jdbcClient.sql("UPDATE orders SET payment_attempt = 'IDLE', attempt_started_at = NULL WHERE order_no IN (:orderNos)")
                .param("orderNos", stuck).update();
    }

    @Test
    void 주문_저장_직후에_죽으면_예약이_없으니_FAILED_로_닫는다() {
        String orderNo = savePending();
        jdbcClient.sql("UPDATE orders SET created_at = NOW(3) - INTERVAL 11 MINUTE WHERE order_no = :orderNo")
                .param("orderNo", orderNo).update();
        when(inventoryClient.state(orderNo)).thenReturn(Optional.empty());

        orderCleanup.run();

        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void 예약_직후에_죽으면_예약이_만료된_뒤_FAILED_로_닫는다() {
        String orderNo = savePending();
        orderRepository.saveReservationExpiry(orderNo, LocalDateTime.now().plusMinutes(10));
        when(inventoryClient.state(orderNo)).thenReturn(Optional.of(heldExpired()));

        orderCleanup.run();
        assertThat(order(orderNo).status()).as("만료 시각 전에는 손대지 않는다").isEqualTo(OrderStatus.PENDING);

        expireReservation(orderNo);
        orderCleanup.run();

        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void 결제_시작_표시_직후에_죽으면_회차를_봉인해_시도를_끝내고_만료_뒤_FAILED_로_닫는다() {
        String orderNo = savePending();
        orderRepository.saveReservationExpiry(orderNo, LocalDateTime.now().plusMinutes(10));
        orderRepository.startAttempt(orderNo, 0);
        ageAttempt(orderNo, 61);

        orderCleanup.run();

        assertThat(order(orderNo).paymentAttempt()).isEqualTo(PaymentAttempt.IDLE);
        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.PENDING);
        verify(paymentClient).seal(orderNo, 1);
        verify(inventoryClient).revertPaying(orderNo, 1);

        expireReservation(orderNo);
        when(inventoryClient.state(orderNo)).thenReturn(Optional.of(
                new ReservationState(ReservationStatus.RELEASED, 1, LocalDateTime.now().minusSeconds(1), true)));
        orderCleanup.run();

        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void 봉인_요청에_회차가_아직_결과를_모른다고_답하면_시도를_그대로_둔다() {
        when(paymentClient.approve(any(), anyInt(), anyLong(), any())).thenReturn(new ApproveResult(ApproveOutcome.UNKNOWN, 1, "TIMEOUT"));
        String orderNo = placeOne(10_000).orderNo();
        ageAttempt(orderNo, 61);
        when(paymentClient.seal(orderNo, 1)).thenReturn(SealOutcome.REQUESTED);

        orderCleanup.run();

        assertThat(order(orderNo).paymentAttempt()).isEqualTo(PaymentAttempt.IN_PROGRESS);
        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void 오래_멈췄던_요청이_깨어나_시도를_끝내려_해도_그사이_시작된_다음_회차의_시도를_바꾸지_못한다() throws Exception {
        CountDownLatch firstRoundCalled = new CountDownLatch(1);
        CountDownLatch releaseFirstRound = new CountDownLatch(1);
        when(paymentClient.approve(any(), eq(1), anyLong(), any())).thenAnswer(invocation -> {
            firstRoundCalled.countDown();
            releaseFirstRound.await(10, TimeUnit.SECONDS);
            return new ApproveResult(ApproveOutcome.DECLINED, 1, "INSUFFICIENT_BALANCE");
        });
        when(paymentClient.approve(any(), eq(2), anyLong(), any())).thenReturn(new ApproveResult(ApproveOutcome.UNKNOWN, 2, "TIMEOUT"));
        String checkoutId = newCheckoutId();
        CompletableFuture<OrderResult> stalled = CompletableFuture.supplyAsync(
                () -> orderPlacement.place(command(checkoutId, item(newProductId(), 1, 10_000))));
        assertThat(firstRoundCalled.await(10, TimeUnit.SECONDS)).isTrue();
        String orderNo = orderRepository.findByCheckoutId(checkoutId).orElseThrow().orderNo();

        // 회차 1 이 멈춘 사이 정리 배치가 봉인해 끝내고, 사용자가 회차 2 를 시작했다
        ageAttempt(orderNo, 61);
        orderCleanup.run();
        OrderResult second = paymentAttempts.pay(orderNo, "OTHER_CARD");
        assertThat(second.outcome()).isEqualTo(OrderOutcome.PAYMENT_CHECKING);

        releaseFirstRound.countDown();
        stalled.get(10, TimeUnit.SECONDS);

        OrderRow orderRow = order(orderNo);
        assertThat(orderRow.paymentAttempt()).isEqualTo(PaymentAttempt.IN_PROGRESS);
        assertThat(orderRow.paymentRound()).isEqualTo(2);
        assertThat(orderRow.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void 결제_이벤트의_승인이_진행_중인_회차면_PAID_로_바꾼다() {
        when(paymentClient.approve(any(), anyInt(), anyLong(), any())).thenReturn(new ApproveResult(ApproveOutcome.UNKNOWN, 1, "TIMEOUT"));
        String orderNo = placeOne(10_000).orderNo();

        paymentEventConsumer.handle(approved(orderNo, 1, 10_000, false));
        paymentEventConsumer.handle(approved(orderNo, 1, 10_000, false));

        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.PAID);
        assertThat(outboxTypes(orderNo)).containsExactly("PAID");
        assertThat(refundKeys(orderNo)).isEmpty();
    }

    @Test
    void 실패로_닫은_회차가_늦게_승인되면_그_회차가_환불된다() {
        when(paymentClient.approve(any(), eq(1), anyLong(), any()))
                .thenReturn(new ApproveResult(ApproveOutcome.DECLINED, 1, "INSUFFICIENT_BALANCE"));
        String orderNo = placeOne(10_000).orderNo();
        paymentAttempts.pay(orderNo, "OTHER_CARD");
        assertThat(order(orderNo).paidRound()).isEqualTo(2);

        paymentEventConsumer.handle(approved(orderNo, 1, 10_000, false));
        paymentEventConsumer.handle(approved(orderNo, 1, 10_000, false));

        assertThat(refundKeys(orderNo)).containsExactly("refund-" + orderNo + "-orphan-1");
        Map<String, Object> refund = refundRow("refund-" + orderNo + "-orphan-1");
        assertThat(refund).containsEntry("payment_round", 1).containsEntry("amount", 10_000L)
                .containsEntry("reason", "ORPHAN_APPROVAL").containsEntry("status", "REQUESTED");
        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void 정산_대사가_바로잡은_승인은_주문을_결제한_회차여도_환불한다() {
        String orderNo = placeOne(10_000).orderNo();

        paymentEventConsumer.handle(approved(orderNo, 1, 10_000, true));

        assertThat(refundKeys(orderNo)).containsExactly("refund-" + orderNo + "-orphan-1");
    }

    @Test
    void 결제되지_않은_주문의_예약이_확정돼_있으면_FAILED_로_닫고_재고_복원을_요청한다() {
        String orderNo = savePending();
        expireReservation(orderNo);
        when(inventoryClient.state(orderNo)).thenReturn(Optional.of(
                new ReservationState(ReservationStatus.CONFIRMED, 1, LocalDateTime.now().minusSeconds(1), true)));

        orderCleanup.run();
        orderCleanup.run();

        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.FAILED);
        assertThat(outboxTypes(orderNo)).containsExactly("CANCELLED");
        assertThat(jdbcClient.sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.cancelReason')) FROM outbox_event WHERE partition_key = :orderNo")
                .param("orderNo", orderNo).query(String.class).single()).isEqualTo("ORPHAN");
    }

    @Test
    void payment_events_토픽의_승인을_받아_주문을_PAID_로_바꾼다() throws Exception {
        when(paymentClient.approve(any(), anyInt(), anyLong(), any())).thenReturn(new ApproveResult(ApproveOutcome.UNKNOWN, 1, "TIMEOUT"));
        String orderNo = placeOne(10_000).orderNo();

        kafkaTemplate.send(Topics.PAYMENT_EVENTS, orderNo, jsonMapper.writeValueAsString(approved(orderNo, 1, 10_000, false))).get();

        long deadline = System.currentTimeMillis() + 20_000;
        while (order(orderNo).status() != OrderStatus.PAID && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.PAID);
        assertThat(outboxTypes(orderNo)).containsExactly("PAID");
    }

    private String savePending() {
        String orderNo = "20261028-T" + UUID.randomUUID().toString().substring(0, 9).toUpperCase();
        transactionTemplate.executeWithoutResult(status -> orderRepository.insert(new NewOrder(orderNo, newCheckoutId(), 1L,
                "김새벽", "서울시 강남구 테헤란로 1", "SEOUL", List.of(new NewOrder.NewOrderItem(newProductId(), 1, 10_000)))));
        return orderNo;
    }

    private static ReservationState heldExpired() {
        return new ReservationState(ReservationStatus.HELD, null, LocalDateTime.now().minusSeconds(1), true);
    }

    private static PaymentEvent failed(String orderNo, int paymentRound) {
        return new PaymentEvent(UUID.randomUUID(), PaymentEventType.FAILED, orderNo, paymentRound, 10_000, null,
                false, Instant.now());
    }

    private static PaymentEvent approved(String orderNo, int paymentRound, long amount, boolean settlementCorrection) {
        return new PaymentEvent(UUID.randomUUID(), PaymentEventType.APPROVED, orderNo, paymentRound, amount, null,
                settlementCorrection, Instant.now());
    }

    private List<String> refundKeys(String orderNo) {
        return jdbcClient.sql("SELECT refund_key FROM refund_request WHERE order_no = :orderNo ORDER BY id")
                .param("orderNo", orderNo).query(String.class).list();
    }

    private Map<String, Object> refundRow(String refundKey) {
        return jdbcClient.sql("SELECT payment_round, amount, reason, status FROM refund_request WHERE refund_key = :refundKey")
                .param("refundKey", refundKey).query().singleRow();
    }
}
