package io.github.bamdule.dawn.order.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.github.bamdule.dawn.event.FulfillmentEvent;
import io.github.bamdule.dawn.event.FulfillmentEventType;
import io.github.bamdule.dawn.event.OrderEvent.OrderLine;
import io.github.bamdule.dawn.event.PaymentEvent;
import io.github.bamdule.dawn.event.PaymentEventType;
import io.github.bamdule.dawn.order.OrderIntegrationTest;
import io.github.bamdule.dawn.order.OrderStatus;
import io.github.bamdule.dawn.order.cancel.CancelOutcome;
import io.github.bamdule.dawn.order.cancel.OrderCancellation;
import io.github.bamdule.dawn.order.client.PaymentClient.RefundAcceptResult;
import io.github.bamdule.dawn.order.recovery.FulfillmentEventConsumer;
import io.github.bamdule.dawn.order.recovery.PaymentEventConsumer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.client.ResourceAccessException;

@AutoConfigureMockMvc
class OrderRefundTest extends OrderIntegrationTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 10, 28, 23, 0);

    @Autowired
    private OrderCancellation orderCancellation;

    @Autowired
    private FulfillmentEventConsumer fulfillmentEventConsumer;

    @Autowired
    private PaymentEventConsumer paymentEventConsumer;

    @Autowired
    private RefundSender refundSender;

    @Autowired
    private MockMvcTester mockMvcTester;

    @Test
    void 마감_전_고객_취소는_남은_품목을_모두_취소하고_전액_환불을_요청한다() {
        long productA = newProductId();
        long productB = newProductId();
        String orderNo = orderPlacement.place(command(newCheckoutId(), item(productA, 2, 10_000), item(productB, 1, 5_000))).orderNo();

        assertThat(orderCancellation.cancelByCustomer(orderNo)).isEqualTo(CancelOutcome.CANCELLED);
        assertThat(orderCancellation.cancelByCustomer(orderNo)).isEqualTo(CancelOutcome.ALREADY_CANCELLED);

        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order(orderNo).cancelReason()).isEqualTo("CUSTOMER");
        assertThat(refunds(orderNo)).containsExactly(Map.of("refund_key", "refund-" + orderNo + "-cancel",
                "amount", 25_000L, "reason", "CUSTOMER_CANCEL", "status", "REQUESTED"));
        assertThat(outboxTypes(orderNo)).containsExactly("PAID", "CANCELLED");
        assertThat(cancelledQuantities(orderNo)).containsExactly(2, 1);
    }

    @Test
    void 속한_회차의_마감이_지나면_고객_취소를_받지_않는다() {
        String orderNo = placeOne(10_000).orderNo();
        jdbcClient.sql("UPDATE orders SET paid_at = DATE(NOW()) - INTERVAL 1 DAY + INTERVAL 22 HOUR WHERE order_no = :orderNo")
                .param("orderNo", orderNo).update();

        assertThat(orderCancellation.cancelByCustomer(orderNo)).isEqualTo(CancelOutcome.CUTOFF_PASSED);
        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.PAID);
        assertThat(refunds(orderNo)).isEmpty();
    }

    @Test
    void 못_채운_품목은_그_품목만_취소하고_품목_금액을_환불하며_같은_이벤트가_다시_와도_한_번이다() {
        long productA = newProductId();
        long productB = newProductId();
        String orderNo = orderPlacement.place(command(newCheckoutId(), item(productA, 2, 10_000), item(productB, 1, 5_000))).orderNo();
        FulfillmentEvent unfillable = unfillable(orderNo, new OrderLine(productA, 1));

        fulfillmentEventConsumer.handle(unfillable);
        fulfillmentEventConsumer.handle(unfillable);

        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.PAID);
        assertThat(refunds(orderNo)).containsExactly(Map.of("refund_key", "refund-" + orderNo + "-cutoff-2026102823",
                "amount", 10_000L, "reason", "UNFILLABLE_PARTIAL", "status", "REQUESTED"));
        assertThat(outboxTypes(orderNo)).containsExactly("PAID", "CANCELLED");
        assertThat(cancelledQuantities(orderNo)).containsExactly(1, 0);
    }

    @Test
    void 관리자_취소로_남은_품목이_모두_취소되면_주문이_CANCELLED_다() {
        long productA = newProductId();
        long productB = newProductId();
        String orderNo = orderPlacement.place(command(newCheckoutId(), item(productA, 2, 10_000), item(productB, 1, 5_000))).orderNo();
        fulfillmentEventConsumer.handle(unfillable(orderNo, new OrderLine(productA, 1)));

        fulfillmentEventConsumer.handle(adminCancelled(orderNo, 7L, new OrderLine(productA, 1), new OrderLine(productB, 1)));

        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order(orderNo).cancelReason()).isEqualTo("ADMIN");
        assertThat(refunds(orderNo)).extracting(row -> row.get("refund_key"), row -> row.get("amount"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("refund-" + orderNo + "-cutoff-2026102823", 10_000L),
                        org.assertj.core.groups.Tuple.tuple("refund-" + orderNo + "-admin-7", 15_000L));
        assertThat(outboxTypes(orderNo)).containsExactly("PAID", "CANCELLED", "CANCELLED");
    }

    @Test
    void 두_사유가_같은_품목을_겹쳐_취소해도_품목_수량을_넘지_않는다() {
        long productA = newProductId();
        String orderNo = orderPlacement.place(command(newCheckoutId(), item(productA, 2, 10_000))).orderNo();

        fulfillmentEventConsumer.handle(unfillable(orderNo, new OrderLine(productA, 2)));
        fulfillmentEventConsumer.handle(adminCancelled(orderNo, 8L, new OrderLine(productA, 2)));

        assertThat(cancelledQuantities(orderNo)).containsExactly(2);
        assertThat(refunds(orderNo)).hasSize(1);
        assertThat(refunds(orderNo).getFirst()).containsEntry("reason", "UNFILLABLE_CANCEL");
        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 결제_서비스가_접수하면_ACCEPTED_이고_완료_이벤트가_오면_COMPLETED_와_누적액을_한_번_반영한다() {
        String orderNo = cancelledOrder(10_000);
        String refundKey = "refund-" + orderNo + "-cancel";
        when(paymentClient.acceptRefund(eq(refundKey), any(), anyInt(), anyLong())).thenReturn(new RefundAcceptResult(true, "REQUESTED"));

        refundSender.run();
        assertThat(refundStatus(refundKey)).isEqualTo("ACCEPTED");

        paymentEventConsumer.handle(refundEvent(PaymentEventType.REFUNDED, orderNo, refundKey, false));
        paymentEventConsumer.handle(refundEvent(PaymentEventType.REFUNDED, orderNo, refundKey, false));

        assertThat(refundStatus(refundKey)).isEqualTo("COMPLETED");
        assertThat(order(orderNo).refundedAmount()).isEqualTo(10_000);
    }

    @Test
    void 접수_응답을_유실시키고_그사이_환불을_완료시켜도_COMPLETED_와_누적액이_맞다() {
        String orderNo = cancelledOrder(10_000);
        String refundKey = "refund-" + orderNo + "-cancel";
        when(paymentClient.acceptRefund(eq(refundKey), any(), anyInt(), anyLong()))
                .thenThrow(new ResourceAccessException("read timed out"))
                .thenReturn(new RefundAcceptResult(true, "COMPLETED"));

        refundSender.run();
        assertThat(refundStatus(refundKey)).isEqualTo("REQUESTED");

        // 결제 서비스는 접수했고 대행사 환불까지 끝내 완료 이벤트를 먼저 냈다
        paymentEventConsumer.handle(refundEvent(PaymentEventType.REFUNDED, orderNo, refundKey, false));
        makeDue(refundKey);
        refundSender.run();

        assertThat(refundStatus(refundKey)).isEqualTo("COMPLETED");
        assertThat(order(orderNo).refundedAmount()).isEqualTo(10_000);
    }

    @Test
    void 접수_응답에_완료가_실려_오면_바로_COMPLETED_로_반영한다() {
        String orderNo = cancelledOrder(10_000);
        String refundKey = "refund-" + orderNo + "-cancel";
        when(paymentClient.acceptRefund(eq(refundKey), any(), anyInt(), anyLong())).thenReturn(new RefundAcceptResult(true, "COMPLETED"));

        refundSender.run();
        paymentEventConsumer.handle(refundEvent(PaymentEventType.REFUNDED, orderNo, refundKey, false));

        assertThat(refundStatus(refundKey)).isEqualTo("COMPLETED");
        assertThat(order(orderNo).refundedAmount()).isEqualTo(10_000);
    }

    @Test
    void 한_시간_넘은_ACCEPTED_는_결제_서비스에_상태를_물어_맞춘다() {
        String orderNo = cancelledOrder(10_000);
        String refundKey = "refund-" + orderNo + "-cancel";
        when(paymentClient.acceptRefund(eq(refundKey), any(), anyInt(), anyLong())).thenReturn(new RefundAcceptResult(true, "REQUESTED"));
        refundSender.run();
        when(paymentClient.refundStatus(refundKey)).thenReturn(Optional.of("COMPLETED"));

        refundSender.run();
        assertThat(refundStatus(refundKey)).as("한 시간 전에는 묻지 않는다").isEqualTo("ACCEPTED");

        makeDue(refundKey);
        refundSender.run();

        assertThat(refundStatus(refundKey)).isEqualTo("COMPLETED");
        assertThat(order(orderNo).refundedAmount()).isEqualTo(10_000);
    }

    @Test
    void 접수가_거절되면_REJECTED_이고_사람이_금액을_바로잡아_새_키로_다시_요청한다() {
        String orderNo = cancelledOrder(10_000);
        String refundKey = "refund-" + orderNo + "-cancel";
        when(paymentClient.acceptRefund(eq(refundKey), any(), anyInt(), anyLong()))
                .thenReturn(new RefundAcceptResult(false, "EXCEEDS_APPROVED_AMOUNT"));

        refundSender.run();
        assertThat(refundStatus(refundKey)).isEqualTo("REJECTED");

        assertThat(mockMvcTester.post().uri("/admin/refunds/{refundKey}/reissue", refundKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 9000}"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.refundKey")
                .asString()
                .startsWith("refund-" + orderNo + "-reissue-");
        assertThat(refunds(orderNo)).hasSize(2);
        assertThat(refunds(orderNo).getLast()).containsEntry("amount", 9_000L).containsEntry("status", "REQUESTED");
    }

    @Test
    void 정산_대사가_완료를_실패로_바로잡으면_누적액을_되돌리고_다시_요청할_수_있다() {
        String orderNo = cancelledOrder(10_000);
        String refundKey = "refund-" + orderNo + "-cancel";
        paymentEventConsumer.handle(refundEvent(PaymentEventType.REFUNDED, orderNo, refundKey, false));
        assertThat(order(orderNo).refundedAmount()).isEqualTo(10_000);

        paymentEventConsumer.handle(refundEvent(PaymentEventType.REFUND_FAILED, orderNo, refundKey, true));
        paymentEventConsumer.handle(refundEvent(PaymentEventType.REFUND_FAILED, orderNo, refundKey, true));

        assertThat(refundStatus(refundKey)).isEqualTo("FAILED");
        assertThat(order(orderNo).refundedAmount()).isZero();
        assertThat(mockMvcTester.post().uri("/admin/refunds/{refundKey}/reissue", refundKey)).hasStatusOk();
        assertThat(refunds(orderNo)).hasSize(2);
        assertThat(refunds(orderNo).getLast()).containsEntry("amount", 10_000L);
    }

    @Test
    void 진행_중인_환불은_다시_요청할_수_없다() {
        String orderNo = cancelledOrder(10_000);

        assertThat(mockMvcTester.post().uri("/admin/refunds/{refundKey}/reissue", "refund-" + orderNo + "-cancel")).hasStatus(409);
    }

    @Test
    void 쓰이지_않은_승인의_환불은_주문의_환불_누적액에_넣지_않는다() {
        String orderNo = placeOne(10_000).orderNo();
        paymentEventConsumer.handle(new PaymentEvent(UUID.randomUUID(), PaymentEventType.APPROVED, orderNo, 1, 10_000, null, true, Instant.now()));
        String orphanKey = "refund-" + orderNo + "-orphan-1";

        paymentEventConsumer.handle(refundEvent(PaymentEventType.REFUNDED, orderNo, orphanKey, false));

        assertThat(refundStatus(orphanKey)).isEqualTo("COMPLETED");
        assertThat(order(orderNo).refundedAmount()).isZero();
    }

    private String cancelledOrder(long unitPrice) {
        String orderNo = placeOne(unitPrice).orderNo();
        orderCancellation.cancelByCustomer(orderNo);
        return orderNo;
    }

    private static FulfillmentEvent unfillable(String orderNo, OrderLine... lines) {
        return new FulfillmentEvent(UUID.randomUUID(), FulfillmentEventType.UNFILLABLE, orderNo, CUTOFF, null,
                List.of(lines), null, null, null, Instant.now());
    }

    private static FulfillmentEvent adminCancelled(String orderNo, long exceptionNo, OrderLine... lines) {
        return new FulfillmentEvent(UUID.randomUUID(), FulfillmentEventType.EXCEPTION_CANCELLED, orderNo, null, exceptionNo,
                List.of(lines), null, null, null, Instant.now());
    }

    private static PaymentEvent refundEvent(PaymentEventType type, String orderNo, String refundKey, boolean settlementCorrection) {
        return new PaymentEvent(UUID.randomUUID(), type, orderNo, 1, 10_000, refundKey, settlementCorrection, Instant.now());
    }

    private List<Map<String, Object>> refunds(String orderNo) {
        return jdbcClient.sql("SELECT refund_key, amount, reason, status FROM refund_request WHERE order_no = :orderNo ORDER BY id")
                .param("orderNo", orderNo).query().listOfRows();
    }

    private String refundStatus(String refundKey) {
        return jdbcClient.sql("SELECT status FROM refund_request WHERE refund_key = :refundKey")
                .param("refundKey", refundKey).query(String.class).single();
    }

    private List<Integer> cancelledQuantities(String orderNo) {
        return jdbcClient.sql("""
                SELECT i.cancelled_quantity FROM order_item i JOIN orders o ON o.id = i.order_id
                 WHERE o.order_no = :orderNo ORDER BY i.product_id
                """)
                .param("orderNo", orderNo).query(Integer.class).list();
    }

    private void makeDue(String refundKey) {
        jdbcClient.sql("UPDATE refund_request SET next_attempt_at = NOW(3) - INTERVAL 1 SECOND WHERE refund_key = :refundKey")
                .param("refundKey", refundKey).update();
    }
}
