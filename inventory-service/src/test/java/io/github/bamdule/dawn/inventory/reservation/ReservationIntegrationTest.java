package io.github.bamdule.dawn.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import io.github.bamdule.dawn.event.CancelReason;
import io.github.bamdule.dawn.event.OrderEvent;
import io.github.bamdule.dawn.event.OrderEventType;
import io.github.bamdule.dawn.inventory.consumer.OrderEventConsumer;
import io.github.bamdule.dawn.inventory.stock.OutOfStockException;
import io.github.bamdule.dawn.inventory.stock.SellableStock;
import io.github.bamdule.dawn.inventory.stock.StockLine;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "dawn.reservation.expiry-interval=1h")
@Import({MySqlContainers.class, KafkaContainers.class})
class ReservationIntegrationTest {

    private static final String SEOUL = "SEOUL";

    @Autowired
    private Reservations reservations;

    @Autowired
    private ReservationLifecycle reservationLifecycle;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationExpiryJob reservationExpiryJob;

    @Autowired
    private OrderEventConsumer orderEventConsumer;

    @Autowired
    private SellableStock sellableStock;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private PaymentRoundSealer paymentRoundSealer;

    // 같은 DB 를 쓰는 다른 테스트가 남긴 만료 PAYING 도 배치가 집는다. 정해 두지 않은 주문은 확인 중으로 답해 건드리지 않게 한다
    @BeforeEach
    void defaultSealOutcome() {
        given(paymentRoundSealer.seal(anyString(), anyInt())).willReturn(SealOutcome.REQUESTED);
    }

    @Test
    void 한_주문의_예약은_가장_짧은_유지_시간으로_만료_시각이_하나다() {
        long normal = InventoryFixture.newProduct(jdbcClient, 10, 10);
        long special = InventoryFixture.newProduct(jdbcClient, 10, 3);
        String orderNo = InventoryFixture.newOrderNo();

        LocalDateTime expiresAt = reservations.reserve(command(orderNo, line(normal, 2), line(special, 1)));

        assertThat(expiresAt).isCloseTo(dbNow().plusMinutes(3), within(5, ChronoUnit.SECONDS));
        assertThat(reservationRepository.findByOrderNo(orderNo))
                .extracting(ReservationRow::expiresAt).containsOnly(expiresAt);
        assertThat(InventoryFixture.quantityOf(jdbcClient, normal)).isEqualTo(8);
        assertThat(InventoryFixture.quantityOf(jdbcClient, special)).isEqualTo(9);
    }

    @Test
    void 같은_주문의_예약_요청이_동시에_다섯_번_와도_재고는_한_번만_빠진다() throws Exception {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        String orderNo = InventoryFixture.newOrderNo();

        runConcurrently(5, () -> reservations.reserve(command(orderNo, line(productId, 2))));

        assertThat(InventoryFixture.quantityOf(jdbcClient, productId)).isEqualTo(8);
        assertThat(reservationRepository.findByOrderNo(orderNo)).hasSize(1);
    }

    @Test
    void 판매_제한_권역의_주문은_품절로_답하고_재고를_건드리지_않는다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        jdbcClient.sql("INSERT INTO stock_region_block (product_id, region_code, created_at) VALUES (:productId, :region, NOW(3))")
                .param("productId", productId).param("region", SEOUL).update();

        assertThatThrownBy(() -> reservations.reserve(command(InventoryFixture.newOrderNo(), line(productId, 1))))
                .isInstanceOf(OutOfStockException.class);
        assertThat(InventoryFixture.quantityOf(jdbcClient, productId)).isEqualTo(10);
    }

    @Test
    void 결제_시작과_만료를_같은_순간에_100번_겹쳐도_결제가_시작된_예약은_풀리지_않는다() throws Exception {
        int markedCount = 0;
        int releasedCount = 0;
        for (int attempt = 0; attempt < 100; attempt++) {
            long productId = InventoryFixture.newProduct(jdbcClient, 1, 10);
            String orderNo = InventoryFixture.newOrderNo();
            reservations.reserve(command(orderNo, line(productId, 1)));
            jdbcClient.sql("UPDATE stock_reservation SET expires_at = NOW(3) + INTERVAL 20000 MICROSECOND WHERE order_no = :orderNo")
                    .param("orderNo", orderNo).update();
            // 출발 시점을 0~39ms 로 바꿔 가며 만료 시각(20ms 뒤)의 앞, 경계, 뒤를 고르게 훑는다
            Thread.sleep(attempt % 40);

            List<Object> results = runConcurrently(2, index -> index == 0
                    ? reservations.markPaying(orderNo, 1)
                    : releaseExpired(orderNo));

            MarkPayingResult markPayingResult = (MarkPayingResult) results.get(0);
            String status = InventoryFixture.statusOf(jdbcClient, orderNo, productId);
            int quantity = InventoryFixture.quantityOf(jdbcClient, productId);
            if (markPayingResult.marked()) {
                markedCount++;
                assertThat(status).isEqualTo("PAYING");
                assertThat(quantity).as("결제가 시작됐는데 재고가 풀리면 안 된다").isZero();
            } else if (status.equals("RELEASED")) {
                releasedCount++;
                assertThat(quantity).isEqualTo(1);
            } else {
                // 해제는 만료 직전에, 결제 시작은 만료 직후에 와서 둘 다 0행인 경우다. 만료된 HELD 로 남아 다음 배치가 푼다
                assertThat(status).isEqualTo("HELD");
                assertThat(isExpired(orderNo)).isTrue();
                assertThat(quantity).isZero();
            }
        }
        System.out.printf("결제 시작이 이긴 횟수=%d, 만료가 이긴 횟수=%d%n", markedCount, releasedCount);
        assertThat(markedCount).as("결제 시작이 이긴 경우도 시험해야 한다").isPositive();
        assertThat(releasedCount).as("만료가 이긴 경우도 시험해야 한다").isPositive();
    }

    @Test
    void 늦게_도착한_앞_회차의_되돌림은_다음_회차의_결제_중_표시를_건드리지_않는다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        String orderNo = InventoryFixture.newOrderNo();
        reservations.reserve(command(orderNo, line(productId, 1)));
        assertThat(reservations.markPaying(orderNo, 2).marked()).isTrue();

        assertThat(reservations.revertPaying(orderNo, 1)).isFalse();
        assertThat(InventoryFixture.statusOf(jdbcClient, orderNo, productId)).isEqualTo("PAYING");
        assertThat(reservations.revertPaying(orderNo, 2)).isTrue();
        assertThat(InventoryFixture.statusOf(jdbcClient, orderNo, productId)).isEqualTo("HELD");
    }

    @Test
    void 결제_시작_표시가_0행이면_현재_상태와_회차를_돌려준다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        String orderNo = InventoryFixture.newOrderNo();
        reservations.reserve(command(orderNo, line(productId, 1)));
        reservations.markPaying(orderNo, 1);

        MarkPayingResult second = reservations.markPaying(orderNo, 2);

        assertThat(second.marked()).isFalse();
        assertThat(second.currentStatus()).isEqualTo(ReservationStatus.PAYING);
        assertThat(second.currentRound()).isEqualTo(1);
    }

    @Test
    void 결제가_승인됐는데_확정_이벤트가_늦어도_만료_배치는_예약을_확정하고_재고를_다시_팔지_않는다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 1, 10);
        String orderNo = paying(productId);
        given(paymentRoundSealer.seal(orderNo, 1)).willReturn(SealOutcome.APPROVED);

        reservationExpiryJob.run();

        assertThat(InventoryFixture.statusOf(jdbcClient, orderNo, productId)).isEqualTo("CONFIRMED");
        assertThat(InventoryFixture.quantityOf(jdbcClient, productId)).isZero();
    }

    @Test
    void 봉인된_회차의_예약만_풀고_결과를_모르는_결제의_예약은_풀지_않는다() {
        long sealedProduct = InventoryFixture.newProduct(jdbcClient, 1, 10);
        long unknownProduct = InventoryFixture.newProduct(jdbcClient, 1, 10);
        long requestedProduct = InventoryFixture.newProduct(jdbcClient, 1, 10);
        String sealedOrder = paying(sealedProduct);
        String unknownOrder = paying(unknownProduct);
        String requestedOrder = paying(requestedProduct);
        given(paymentRoundSealer.seal(anyString(), anyInt())).willAnswer(invocation -> {
            String orderNo = invocation.getArgument(0);
            if (orderNo.equals(sealedOrder)) {
                return SealOutcome.SEALED;
            }
            return orderNo.equals(unknownOrder) ? SealOutcome.UNKNOWN : SealOutcome.REQUESTED;
        });

        reservationExpiryJob.run();

        assertThat(InventoryFixture.statusOf(jdbcClient, sealedOrder, sealedProduct)).isEqualTo("RELEASED");
        assertThat(InventoryFixture.quantityOf(jdbcClient, sealedProduct)).isEqualTo(1);
        assertThat(InventoryFixture.statusOf(jdbcClient, unknownOrder, unknownProduct)).isEqualTo("PAYING");
        assertThat(InventoryFixture.statusOf(jdbcClient, requestedOrder, requestedProduct)).isEqualTo("PAYING");
    }

    @Test
    void PAID_처리가_빠진_사이_고객_취소가_먼저_와도_재고가_돌아오고_다시_와도_두_번_더하지_않는다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 5, 10);
        String orderNo = InventoryFixture.newOrderNo();
        reservations.reserve(command(orderNo, line(productId, 2)));
        reservations.markPaying(orderNo, 1);
        OrderEvent cancelled = cancelled(orderNo, productId, 2);

        orderEventConsumer.handle(cancelled);
        orderEventConsumer.handle(cancelled);
        orderEventConsumer.handle(paid(orderNo, productId, 2));

        assertThat(InventoryFixture.statusOf(jdbcClient, orderNo, productId)).isEqualTo("RESTORED");
        assertThat(InventoryFixture.quantityOf(jdbcClient, productId)).isEqualTo(5);
    }

    @Test
    void 확정은_결제_중인_예약만_바꾸고_두_번_와도_같다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 5, 10);
        String orderNo = paying(productId);

        orderEventConsumer.handle(paid(orderNo, productId, 1));
        orderEventConsumer.handle(paid(orderNo, productId, 1));

        assertThat(InventoryFixture.statusOf(jdbcClient, orderNo, productId)).isEqualTo("CONFIRMED");
        assertThat(InventoryFixture.quantityOf(jdbcClient, productId)).isEqualTo(4);
    }

    @Test
    void 실사_부족분이_있으면_만료_해제가_그것부터_갚아_실물보다_많이_팔지_않는다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        List<String> orders = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            String orderNo = InventoryFixture.newOrderNo();
            reservations.reserve(command(orderNo, line(productId, 1)));
            orders.add(orderNo);
        }
        assertThat(InventoryFixture.quantityOf(jdbcClient, productId)).isZero();

        transactionTemplate.executeWithoutResult(status ->
                sellableStock.decreaseForStocktake(UUID.randomUUID().toString(), productId, 2));
        assertDeficitInvariant(productId, 0, 2);

        orders.forEach(orderNo -> InventoryFixture.expireNow(jdbcClient, orderNo));
        reservationExpiryJob.run();

        assertDeficitInvariant(productId, 8, 0);
    }

    @Test
    void 판매_가능_재고가_남아_있으면_실사_감소는_부족분_없이_바로_뺀다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 5, 10);

        transactionTemplate.executeWithoutResult(status ->
                sellableStock.decreaseForStocktake(UUID.randomUUID().toString(), productId, 2));

        assertDeficitInvariant(productId, 3, 0);
    }

    private void assertDeficitInvariant(long productId, int expectedQuantity, int expectedDeficit) {
        int quantity = InventoryFixture.quantityOf(jdbcClient, productId);
        int deficit = InventoryFixture.deficitOf(jdbcClient, productId);
        assertThat(quantity).isEqualTo(expectedQuantity);
        assertThat(deficit).isEqualTo(expectedDeficit);
        if (deficit > 0) {
            assertThat(quantity).as("부족분이 있으면 판매 가능 재고는 0").isZero();
        }
    }

    private String paying(long productId) {
        String orderNo = InventoryFixture.newOrderNo();
        reservations.reserve(command(orderNo, line(productId, 1)));
        assertThat(reservations.markPaying(orderNo, 1).marked()).isTrue();
        InventoryFixture.expireNow(jdbcClient, orderNo);
        return orderNo;
    }

    private boolean isExpired(String orderNo) {
        return jdbcClient.sql("SELECT COUNT(*) FROM stock_reservation WHERE order_no = :orderNo AND expires_at <= NOW(3)")
                .param("orderNo", orderNo).query(Integer.class).single() > 0;
    }

    private Object releaseExpired(String orderNo) {
        reservationRepository.findByOrderNo(orderNo).forEach(reservationLifecycle::releaseExpiredHeld);
        return null;
    }

    private ReservationCommand command(String orderNo, StockLine... lines) {
        return new ReservationCommand(orderNo, SEOUL, List.of(lines));
    }

    private StockLine line(long productId, int quantity) {
        return new StockLine(productId, quantity);
    }

    private OrderEvent paid(String orderNo, long productId, int quantity) {
        return new OrderEvent(UUID.randomUUID(), OrderEventType.PAID, orderNo, 1L, 10_000L,
                List.of(new OrderEvent.OrderLine(productId, quantity)), null, Instant.now());
    }

    private OrderEvent cancelled(String orderNo, long productId, int quantity) {
        return new OrderEvent(UUID.randomUUID(), OrderEventType.CANCELLED, orderNo, 1L, 10_000L,
                List.of(new OrderEvent.OrderLine(productId, quantity)), CancelReason.CUSTOMER, Instant.now());
    }

    private LocalDateTime dbNow() {
        return jdbcClient.sql("SELECT NOW(3)").query(LocalDateTime.class).single();
    }

    private List<Object> runConcurrently(int requests, ConcurrentRequest request) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int index = 0; index < requests; index++) {
            int requestIndex = index;
            futures.add(executorService.submit(() -> {
                start.await();
                return request.run(requestIndex);
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get());
        }
        executorService.shutdown();
        return results;
    }

    private List<Object> runConcurrently(int requests, Runnable request) throws Exception {
        return runConcurrently(requests, index -> {
            request.run();
            return null;
        });
    }

    @FunctionalInterface
    private interface ConcurrentRequest {
        Object run(int index) throws Exception;
    }
}
