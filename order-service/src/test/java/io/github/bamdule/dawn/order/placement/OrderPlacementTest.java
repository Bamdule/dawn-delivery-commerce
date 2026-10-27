package io.github.bamdule.dawn.order.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.github.bamdule.dawn.order.OrderIntegrationTest;
import io.github.bamdule.dawn.order.OrderStatus;
import io.github.bamdule.dawn.order.PaymentAttempt;
import io.github.bamdule.dawn.order.client.ApproveOutcome;
import io.github.bamdule.dawn.order.client.MarkPayingResult;
import io.github.bamdule.dawn.order.client.PaymentClient;
import io.github.bamdule.dawn.order.client.PaymentClient.ApproveResult;
import io.github.bamdule.dawn.order.client.ReservationState;
import io.github.bamdule.dawn.order.client.ReservationStatus;
import io.github.bamdule.dawn.order.client.ReserveResult;
import io.github.bamdule.dawn.order.store.OrderRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

class OrderPlacementTest extends OrderIntegrationTest {

    @Autowired
    private PaymentAttempts paymentAttempts;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 승인되면_PAID_와_PAID_이벤트를_함께_남기고_시도를_끝낸다() {
        OrderResult result = orderPlacement.place(command(newCheckoutId(), item(newProductId(), 2, 10_000), item(newProductId(), 1, 5_000)));

        OrderRow orderRow = order(result.orderNo());
        assertThat(result.outcome()).isEqualTo(OrderOutcome.PAID);
        assertThat(orderRow.status()).isEqualTo(OrderStatus.PAID);
        assertThat(orderRow.paidRound()).isEqualTo(1);
        assertThat(orderRow.paymentAttempt()).isEqualTo(PaymentAttempt.IDLE);
        assertThat(orderRow.totalAmount()).isEqualTo(25_000);
        assertThat(orderRow.reservationExpiresAt()).isNotNull();
        assertThat(outboxTypes(result.orderNo())).containsExactly("PAID");
        verify(paymentClient).approve(result.orderNo(), 1, 25_000, "CARD");
    }

    @Test
    void 같은_주문서_ID_로_동시에_5번_요청해도_주문과_결제가_하나다() {
        String checkoutId = newCheckoutId();
        PlaceOrderCommand placeOrderCommand = command(checkoutId, item(newProductId(), 1, 10_000));

        List<OrderResult> results = runConcurrently(5, () -> orderPlacement.place(placeOrderCommand));

        assertThat(results).extracting(OrderResult::orderNo).containsOnly(results.getFirst().orderNo());
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM orders WHERE checkout_id = :checkoutId")
                .param("checkoutId", checkoutId).query(Integer.class).single()).isEqualTo(1);
        verify(paymentClient, times(1)).approve(eq(results.getFirst().orderNo()), anyInt(), anyLong(), any());
        verify(inventoryClient, times(1)).reserve(eq(results.getFirst().orderNo()), any(), any());
    }

    @Test
    void 품절_목록의_상품이면_주문을_저장하지_않고_품절로_답한다() {
        long productId = newProductId();
        when(inventoryClient.soldOutProductIds()).thenReturn(List.of(productId));
        soldOutProducts.refresh();
        String checkoutId = newCheckoutId();

        OrderResult result = orderPlacement.place(command(checkoutId, item(productId, 1, 10_000)));

        assertThat(result.outcome()).isEqualTo(OrderOutcome.SOLD_OUT);
        assertThat(result.orderNo()).isNull();
        assertThat(orderRepository.findByCheckoutId(checkoutId)).isEmpty();
        verify(inventoryClient, never()).reserve(any(), any(), any());
    }

    @Test
    void 같은_주문서_ID_확인을_품절_목록보다_먼저_해서_재시도는_기존_주문의_상태를_받는다() {
        long productId = newProductId();
        String checkoutId = newCheckoutId();
        OrderResult first = orderPlacement.place(command(checkoutId, item(productId, 1, 10_000)));
        when(inventoryClient.soldOutProductIds()).thenReturn(List.of(productId));
        soldOutProducts.refresh();

        OrderResult retried = orderPlacement.place(command(checkoutId, item(productId, 1, 10_000)));

        assertThat(retried.orderNo()).isEqualTo(first.orderNo());
        assertThat(retried.outcome()).isEqualTo(OrderOutcome.PAID);
    }

    @Test
    void 예약에서_품절이면_주문을_FAILED_로_닫고_결제를_부르지_않는다() {
        long productId = newProductId();
        when(inventoryClient.reserve(any(), any(), any())).thenReturn(ReserveResult.soldOut(productId));

        OrderResult result = orderPlacement.place(command(newCheckoutId(), item(productId, 1, 10_000)));

        assertThat(result.outcome()).isEqualTo(OrderOutcome.SOLD_OUT);
        assertThat(order(result.orderNo()).status()).isEqualTo(OrderStatus.FAILED);
        verify(paymentClient, never()).approve(any(), anyInt(), anyLong(), any());
    }

    @Test
    void 카드가_거절되면_주문은_PENDING_이고_예약을_되돌린_뒤_다른_카드로_다음_회차를_결제한다() {
        when(paymentClient.approve(any(), eq(1), anyLong(), any()))
                .thenReturn(new ApproveResult(ApproveOutcome.DECLINED, 1, "INSUFFICIENT_BALANCE"));

        OrderResult declined = placeOne(10_000);

        assertThat(declined.outcome()).isEqualTo(OrderOutcome.PAYMENT_DECLINED);
        assertThat(declined.failureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(order(declined.orderNo()).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order(declined.orderNo()).paymentAttempt()).isEqualTo(PaymentAttempt.IDLE);
        verify(inventoryClient).revertPaying(declined.orderNo(), 1);

        OrderResult paid = paymentAttempts.pay(declined.orderNo(), "OTHER_CARD");

        assertThat(paid.outcome()).isEqualTo(OrderOutcome.PAID);
        assertThat(order(declined.orderNo()).paidRound()).isEqualTo(2);
        verify(paymentClient).approve(declined.orderNo(), 2, 10_000, "OTHER_CARD");
    }

    @Test
    void 결제_서비스가_대행사를_부르지_않고_거절하면_결제_지연으로_답한다() {
        when(paymentClient.approve(any(), anyInt(), anyLong(), any()))
                .thenReturn(new ApproveResult(ApproveOutcome.DECLINED, 1, PaymentClient.NOT_CALLED));

        OrderResult result = placeOne(10_000);

        assertThat(result.outcome()).isEqualTo(OrderOutcome.PAYMENT_DELAYED);
        assertThat(order(result.orderNo()).paymentAttempt()).isEqualTo(PaymentAttempt.IDLE);
    }

    @Test
    void 결제_확인_중에_다시_결제를_눌러도_주문이_FAILED_가_되지_않는다() {
        when(paymentClient.approve(any(), anyInt(), anyLong(), any()))
                .thenReturn(new ApproveResult(ApproveOutcome.UNKNOWN, 1, "TIMEOUT"));

        OrderResult checking = placeOne(10_000);
        OrderResult again = paymentAttempts.pay(checking.orderNo(), "CARD");

        assertThat(checking.outcome()).isEqualTo(OrderOutcome.PAYMENT_CHECKING);
        assertThat(again.outcome()).isEqualTo(OrderOutcome.PAYMENT_IN_PROGRESS);
        OrderRow orderRow = order(checking.orderNo());
        assertThat(orderRow.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(orderRow.paymentAttempt()).isEqualTo(PaymentAttempt.IN_PROGRESS);
        assertThat(orderRow.paymentRound()).isEqualTo(1);
        verify(paymentClient, times(1)).approve(any(), anyInt(), anyLong(), any());
    }

    @Test
    void 결제_승인_응답을_받지_못해도_실패로_단정하지_않는다() {
        when(paymentClient.approve(any(), anyInt(), anyLong(), any())).thenThrow(new ResourceAccessException("read timed out"));

        OrderResult result = placeOne(10_000);

        assertThat(result.outcome()).isEqualTo(OrderOutcome.PAYMENT_CHECKING);
        assertThat(order(result.orderNo()).paymentAttempt()).isEqualTo(PaymentAttempt.IN_PROGRESS);
    }

    @Test
    void 같은_주문에_다시_결제를_동시에_열_번_눌러도_시도는_하나만_진행된다() {
        when(paymentClient.approve(any(), eq(1), anyLong(), any()))
                .thenReturn(new ApproveResult(ApproveOutcome.DECLINED, 1, "INSUFFICIENT_BALANCE"));
        String orderNo = placeOne(10_000).orderNo();
        when(paymentClient.approve(any(), eq(2), anyLong(), any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return new ApproveResult(ApproveOutcome.APPROVED, 2, null);
        });

        List<OrderResult> results = runConcurrently(10, () -> paymentAttempts.pay(orderNo, "CARD"));

        assertThat(results).filteredOn(result -> result.outcome() == OrderOutcome.PAID).hasSize(1);
        assertThat(results).filteredOn(result -> result.outcome() == OrderOutcome.PAYMENT_IN_PROGRESS).hasSize(9);
        verify(paymentClient, times(1)).approve(orderNo, 2, 10_000, "CARD");
        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.PAID);
        assertThat(order(orderNo).paymentRound()).isEqualTo(2);
    }

    @Test
    void 앞_회차의_되돌림이_실패해_PAYING_으로_남았으면_앞_회차가_FAILED_인지_확인한_뒤_되돌리고_결제한다() {
        when(paymentClient.approve(any(), eq(1), anyLong(), any()))
                .thenReturn(new ApproveResult(ApproveOutcome.DECLINED, 1, "INSUFFICIENT_BALANCE"));
        when(inventoryClient.revertPaying(any(), eq(1))).thenThrow(new ResourceAccessException("read timed out")).thenReturn(true);
        String orderNo = placeOne(10_000).orderNo();
        when(inventoryClient.markPaying(orderNo, 2))
                .thenReturn(notMarked(ReservationStatus.PAYING, 1, false))
                .thenReturn(new MarkPayingResult(true, true, ReservationStatus.PAYING, 2, LocalDateTime.now().plusMinutes(5), false));
        when(paymentClient.roundStatus(orderNo, 1)).thenReturn(Optional.of("FAILED"));

        OrderResult result = paymentAttempts.pay(orderNo, "CARD");

        assertThat(result.outcome()).isEqualTo(OrderOutcome.PAID);
        verify(inventoryClient, times(2)).revertPaying(orderNo, 1);
        verify(paymentClient).approve(orderNo, 2, 10_000, "CARD");
    }

    @Test
    void 앞_회차가_아직_결과를_모르면_되돌리지_않고_결제_진행_중으로_답한다() {
        when(paymentClient.approve(any(), eq(1), anyLong(), any()))
                .thenReturn(new ApproveResult(ApproveOutcome.DECLINED, 1, "INSUFFICIENT_BALANCE"));
        String orderNo = placeOne(10_000).orderNo();
        when(inventoryClient.markPaying(orderNo, 2)).thenReturn(notMarked(ReservationStatus.PAYING, 1, false));
        when(paymentClient.roundStatus(orderNo, 1)).thenReturn(Optional.of("REQUESTED"));

        OrderResult result = paymentAttempts.pay(orderNo, "CARD");

        assertThat(result.outcome()).isEqualTo(OrderOutcome.PAYMENT_IN_PROGRESS);
        assertThat(order(orderNo).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order(orderNo).paymentAttempt()).isEqualTo(PaymentAttempt.IDLE);
        verify(paymentClient, never()).approve(orderNo, 2, 10_000, "CARD");
    }

    @Test
    void 결제_시작_표시가_예약_만료로_0행이면_결제를_부르지_않고_FAILED_로_닫는다() {
        when(inventoryClient.markPaying(any(), anyInt())).thenReturn(notMarked(ReservationStatus.HELD, null, true));

        OrderResult result = placeOne(10_000);

        assertThat(result.outcome()).isEqualTo(OrderOutcome.FAILED);
        assertThat(order(result.orderNo()).paymentAttempt()).isEqualTo(PaymentAttempt.IDLE);
        verify(paymentClient, never()).approve(any(), anyInt(), anyLong(), any());
    }

    @Test
    void 결제_시작_표시의_응답이_유실돼도_같은_회차로_PAYING_이면_결제를_진행한다() {
        when(inventoryClient.markPaying(any(), anyInt())).thenReturn(notMarked(ReservationStatus.PAYING, 1, false));

        OrderResult result = placeOne(10_000);

        assertThat(result.outcome()).isEqualTo(OrderOutcome.PAID);
    }

    @Test
    void 봉인됐다는_답을_받으면_만료_전에는_예약을_되돌려_다시_결제를_받는다() {
        when(paymentClient.approve(any(), anyInt(), anyLong(), any())).thenReturn(new ApproveResult(ApproveOutcome.SEALED, 1, null));
        when(inventoryClient.state(any())).thenReturn(Optional.of(
                new ReservationState(ReservationStatus.PAYING, 1, LocalDateTime.now().plusMinutes(5), false)));

        OrderResult result = placeOne(10_000);

        assertThat(result.outcome()).isEqualTo(OrderOutcome.RETRY_PAYMENT);
        assertThat(order(result.orderNo()).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order(result.orderNo()).paymentAttempt()).isEqualTo(PaymentAttempt.IDLE);
        verify(inventoryClient).revertPaying(result.orderNo(), 1);
    }

    @Test
    void 봉인됐다는_답을_받았는데_예약이_만료됐으면_FAILED_로_닫는다() {
        when(paymentClient.approve(any(), anyInt(), anyLong(), any())).thenReturn(new ApproveResult(ApproveOutcome.SEALED, 1, null));
        when(inventoryClient.state(any())).thenReturn(Optional.of(
                new ReservationState(ReservationStatus.PAYING, 1, LocalDateTime.now().minusSeconds(1), true)));

        OrderResult result = placeOne(10_000);

        assertThat(result.outcome()).isEqualTo(OrderOutcome.FAILED);
        assertThat(order(result.orderNo()).status()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void 대행사가_3초_지연하는_동안_주문_DB_커넥션을_잡고_있지_않다() throws Exception {
        HikariDataSource hikariDataSource = dataSource.unwrap(HikariDataSource.class);
        AtomicInteger maxActiveWhileWaiting = new AtomicInteger(-1);
        when(paymentClient.approve(any(), anyInt(), anyLong(), any())).thenAnswer(invocation -> {
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                int active = hikariDataSource.getHikariPoolMXBean().getActiveConnections();
                maxActiveWhileWaiting.accumulateAndGet(active, Math::max);
                Thread.sleep(50);
            }
            return new ApproveResult(ApproveOutcome.APPROVED, invocation.getArgument(1), null);
        });

        OrderResult result = placeOne(10_000);

        assertThat(result.outcome()).isEqualTo(OrderOutcome.PAID);
        System.out.printf("대행사를 기다리는 3초 동안 사용 중인 커넥션 최대=%d, 풀 크기=%d%n",
                maxActiveWhileWaiting.get(), hikariDataSource.getMaximumPoolSize());
        assertThat(maxActiveWhileWaiting.get()).as("결제를 기다리는 동안 커넥션을 잡으면 안 된다").isZero();
        // 같은 지표가 커넥션을 잡은 트랜잭션 안에서는 1 이상을 읽는지 확인해, 지표가 늘 0 이라 통과한 것이 아님을 보인다
        Integer activeInsideTransaction = transactionTemplate.execute(status -> {
            jdbcClient.sql("SELECT 1").query(Integer.class).single();
            return hikariDataSource.getHikariPoolMXBean().getActiveConnections();
        });
        assertThat(activeInsideTransaction).isPositive();
    }

    private static MarkPayingResult notMarked(ReservationStatus status, Integer round, boolean expired) {
        LocalDateTime expiresAt = expired ? LocalDateTime.now().minusSeconds(1) : LocalDateTime.now().plusMinutes(5);
        return new MarkPayingResult(true, false, status, round, expiresAt, expired);
    }

    private static <T> List<T> runConcurrently(int count, Supplier<T> task) {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executorService = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = IntStream.range(0, count)
                    .mapToObj(index -> executorService.submit(() -> {
                        start.await();
                        return task.get();
                    }))
                    .toList();
            start.countDown();
            return futures.stream().map(OrderPlacementTest::join).toList();
        }
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
