package io.github.bamdule.dawn.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import io.github.bamdule.dawn.inventory.stock.OutOfStockException;
import io.github.bamdule.dawn.inventory.stock.SlotStock;
import io.github.bamdule.dawn.inventory.stock.StockLine;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"dawn.reservation.expiry-interval=1h", "dawn.slot.merge-interval=1h"})
@Import({MySqlContainers.class, KafkaContainers.class})
class SlotIntegrationTest {

    @Autowired
    private Reservations reservations;

    @Autowired
    private ReservationLifecycle reservationLifecycle;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private SlotStock slotStock;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private PaymentRoundSealer paymentRoundSealer;

    @BeforeEach
    void defaultSealOutcome() {
        given(paymentRoundSealer.seal(anyString(), anyInt())).willReturn(SealOutcome.REQUESTED);
    }

    @Test
    void 켜면_재고를_슬롯_8개에_나눠_넣고_stock_수량은_0이_된다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 100, 3);

        slotStock.spread(productId);

        assertThat(InventoryFixture.quantityOf(jdbcClient, productId)).isZero();
        assertThat(slots(productId)).hasSize(8).allSatisfy(quantity -> assertThat(quantity).isBetween(12, 13));
        assertThat(slotSum(productId)).isEqualTo(100);
    }

    @Test
    void 슬롯_8개_재고_100에_300건이_동시에_오면_정확히_100건만_성공한다() throws Exception {
        long productId = InventoryFixture.newProduct(jdbcClient, 100, 3);
        slotStock.spread(productId);

        AtomicInteger succeeded = new AtomicInteger();
        runConcurrently(300, 50, () -> {
            try {
                reservations.reserve(new ReservationCommand(InventoryFixture.newOrderNo(), "SEOUL",
                        List.of(new StockLine(productId, 1))));
                succeeded.incrementAndGet();
            } catch (OutOfStockException outOfStockException) {
                // 품절
            }
        });

        assertThat(succeeded.get()).isEqualTo(100);
        assertThat(slotSum(productId)).isZero();
        assertThat(reservedSlotSum(productId)).isEqualTo(100);
    }

    @Test
    void 한_슬롯보다_많은_수량은_여러_슬롯에서_나눠_빼고_풀면_내역대로_원래_슬롯에_돌아간다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 16, 3);
        slotStock.spread(productId);
        List<Integer> before = slots(productId);
        String orderNo = InventoryFixture.newOrderNo();

        reservations.reserve(new ReservationCommand(orderNo, "SEOUL", List.of(new StockLine(productId, 5))));
        assertThat(slotSum(productId)).isEqualTo(11);
        InventoryFixture.expireNow(jdbcClient, orderNo);
        reservationRepository.findByOrderNo(orderNo).forEach(reservationLifecycle::releaseExpiredHeld);

        assertThat(slots(productId)).isEqualTo(before);
    }

    @Test
    void 합친_뒤의_되돌림과_켜기_전의_예약은_슬롯_0_으로_돌아간다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 30, 3);
        String beforeSpread = InventoryFixture.newOrderNo();
        reservations.reserve(new ReservationCommand(beforeSpread, "SEOUL", List.of(new StockLine(productId, 2))));
        slotStock.spread(productId);
        String afterSpread = InventoryFixture.newOrderNo();
        reservations.reserve(new ReservationCommand(afterSpread, "SEOUL", List.of(new StockLine(productId, 10))));
        assertThat(slotStock.mergeIfLow(productId)).as("잔량 합 18 은 20 아래라 합친다").isTrue();

        for (String orderNo : List.of(beforeSpread, afterSpread)) {
            InventoryFixture.expireNow(jdbcClient, orderNo);
            reservationRepository.findByOrderNo(orderNo).forEach(reservationLifecycle::releaseExpiredHeld);
        }

        List<Integer> slots = slots(productId);
        assertThat(slots.getFirst()).isEqualTo(30);
        assertThat(slots.subList(1, 8)).containsOnly(0);
    }

    @Test
    void 합친_상품을_다시_펴면_슬롯_0_의_수량을_8개에_나눈다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 3);
        slotStock.spread(productId);
        slotStock.mergeIfLow(productId);
        jdbcClient.sql("UPDATE stock_slot SET quantity = 300 WHERE product_id = :productId AND slot_no = 0")
                .param("productId", productId).update();

        slotStock.spread(productId);

        assertThat(slots(productId)).allSatisfy(quantity -> assertThat(quantity).isBetween(37, 38));
        assertThat(slotSum(productId)).isEqualTo(300);
    }

    @Test
    void 예약과_만료_해제와_합치기와_다시_펴기를_섞어_돌려도_재고_합이_맞고_합친_상품의_다른_슬롯은_비어_있다() throws Exception {
        int initial = 200;
        long productId = InventoryFixture.newProduct(jdbcClient, initial, 3);
        slotStock.spread(productId);
        AtomicBoolean running = new AtomicBoolean(true);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        ExecutorService modeChanger = Executors.newSingleThreadExecutor();
        Future<?> modeChanges = modeChanger.submit(() -> {
            while (running.get()) {
                try {
                    slotStock.mergeIfLow(productId);
                    if (ThreadLocalRandom.current().nextInt(3) == 0) {
                        slotStock.spread(productId);
                    }
                } catch (org.springframework.dao.PessimisticLockingFailureException lockFailure) {
                    // 교착으로 되돌아간 모드 전환은 다음 바퀴에 다시 한다
                }
            }
        });

        runConcurrently(400, 30, () -> {
            String orderNo = InventoryFixture.newOrderNo();
            try {
                reservations.reserve(new ReservationCommand(orderNo, "SEOUL",
                        List.of(new StockLine(productId, 1 + ThreadLocalRandom.current().nextInt(3)))));
                if (ThreadLocalRandom.current().nextInt(10) < 6) {
                    InventoryFixture.expireNow(jdbcClient, orderNo);
                    reservationRepository.findByOrderNo(orderNo).forEach(reservationLifecycle::releaseExpiredHeld);
                }
            } catch (OutOfStockException outOfStockException) {
                // 품절
            } catch (org.springframework.dao.PessimisticLockingFailureException lockFailure) {
                // 재시도까지 교착이면 이 요청은 실패로 끝난다. 실패해도 재고 합은 맞아야 한다
            } catch (Throwable throwable) {
                unexpected.add(throwable);
            }
        });
        running.set(false);
        modeChanges.get(30, TimeUnit.SECONDS);
        modeChanger.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(InventoryFixture.quantityOf(jdbcClient, productId) + slotSum(productId) + heldQuantity(productId))
                .as("판매 가능 재고 + 잡힌 예약 = 처음 재고").isEqualTo(initial);
        String mode = jdbcClient.sql("SELECT slot_mode FROM stock WHERE product_id = :productId")
                .param("productId", productId).query(String.class).single();
        if (mode.equals("MERGED")) {
            assertThat(slots(productId).subList(1, 8)).as("합친 상품은 슬롯 0 에서만 판다").containsOnly(0);
        }
    }

    private List<Integer> slots(long productId) {
        return jdbcClient.sql("SELECT quantity FROM stock_slot WHERE product_id = :productId ORDER BY slot_no")
                .param("productId", productId).query(Integer.class).list();
    }

    private int slotSum(long productId) {
        return slots(productId).stream().mapToInt(Integer::intValue).sum();
    }

    private int reservedSlotSum(long productId) {
        return jdbcClient.sql("""
                SELECT COALESCE(SUM(rs.quantity), 0) FROM reservation_slot rs
                  JOIN stock_reservation r ON r.reservation_id = rs.reservation_id
                 WHERE r.product_id = :productId
                """).param("productId", productId).query(Integer.class).single();
    }

    private int heldQuantity(long productId) {
        return jdbcClient.sql("""
                SELECT COALESCE(SUM(quantity - cancelled_quantity), 0) FROM stock_reservation
                 WHERE product_id = :productId AND status IN ('HELD', 'PAYING', 'CONFIRMED')
                """).param("productId", productId).query(Integer.class).single();
    }

    private void runConcurrently(int requests, int threads, Runnable request) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(requests);
        for (int index = 0; index < requests; index++) {
            futures.add(executorService.submit(() -> {
                start.await();
                request.run();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        executorService.shutdown();
    }
}
