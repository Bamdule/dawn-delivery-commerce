package io.github.bamdule.dawn.inventory.reservation;

import io.github.bamdule.dawn.inventory.stock.OutOfStockException;
import io.github.bamdule.dawn.inventory.stock.SlotStock;
import io.github.bamdule.dawn.inventory.stock.StockLine;
import io.github.bamdule.dawn.messaging.test.ExperimentReport;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.LoadRunner;
import io.github.bamdule.dawn.messaging.test.LoadRunner.LoadResult;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

// 실험 1. 같은 상품에 몰리는 차감을 세 방식으로 비교한다 (docs/experiments/01-재고-동시성.md).
// 커넥션 풀은 일꾼 수와 같게 둬서 풀이 아니라 행 잠금이 병목이 되게 한다
@Tag("experiment")
@SpringBootTest(properties = {
        "dawn.reservation.expiry-interval=1h",
        "dawn.slot.merge-interval=1h",
        "spring.datasource.hikari.maximum-pool-size=64"})
@Import({MySqlContainers.class, KafkaContainers.class})
class StockConcurrencyExperiment {

    private static final int WORKERS = 64;
    private static final Duration RUN = Duration.ofSeconds(10);
    private static final int ARRIVALS_PER_SECOND = 500;
    private static final int PLENTY = 10_000_000;
    private static final int CATALOG_SIZE = 200;
    private static final AtomicLong NEXT_ORDER = new AtomicLong();

    @Autowired
    private Reservations reservations;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private SlotStock slotStock;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private PaymentRoundSealer paymentRoundSealer;

    @Test
    void 같은_상품에_몰리는_차감을_조건부_갱신_슬롯_비관적_락으로_비교한다() throws Exception {
        ExperimentReport report = new ExperimentReport("01-stock-concurrency");
        warmUp();

        report.line("## 한 행 조건부 갱신 한 문장 (예약 없이 차감만, 자동 커밋)");
        report.line(LoadResult.header());
        long rowOnly = newProduct(PLENTY);
        report.line(LoadRunner.closedLoop(WORKERS, RUN, () -> decrementOnly(rowOnly)).row("닫힌 부하, 일꾼 " + WORKERS));

        for (String shape : List.of("상품 1개 주문", "상품 3개 주문")) {
            boolean threeProducts = shape.equals("상품 3개 주문");
            report.line("");
            report.line("## " + shape + ", 닫힌 부하 (일꾼 " + WORKERS + ", " + RUN.toSeconds() + "초)");
            report.line(LoadResult.header());
            report.line(LoadRunner.closedLoop(WORKERS, RUN, conditional(threeProducts, false)).row("조건부 갱신"));
            report.line(LoadRunner.closedLoop(WORKERS, RUN, conditional(threeProducts, true)).row("슬롯 8개"));
            report.line(LoadRunner.closedLoop(WORKERS, RUN, pessimistic(threeProducts)).row("비관적 락"));

            report.line("");
            report.line("## " + shape + ", 열린 부하 (초당 " + ARRIVALS_PER_SECOND + "건 도착, " + RUN.toSeconds() + "초)");
            report.line(LoadResult.header());
            report.line(LoadRunner.openLoop(ARRIVALS_PER_SECOND, RUN, conditional(threeProducts, false)).row("조건부 갱신"));
            report.line(LoadRunner.openLoop(ARRIVALS_PER_SECOND, RUN, conditional(threeProducts, true)).row("슬롯 8개"));
            report.line(LoadRunner.openLoop(ARRIVALS_PER_SECOND, RUN, pessimistic(threeProducts)).row("비관적 락"));
        }
    }

    // 첫 측정이 커넥션 생성과 JIT 를 떠안지 않게 한 번 돌려 둔다
    private void warmUp() throws InterruptedException {
        LoadRunner.closedLoop(WORKERS, Duration.ofSeconds(3), conditional(false, false));
    }

    // 운영 코드의 예약 경로 그대로다. 슬롯 상품은 마지막에, 나머지는 상품번호 순으로 차감한다
    private BooleanSupplier conditional(boolean threeProducts, boolean hotSpread) {
        Supplier<List<Long>> products = products(threeProducts, hotSpread);
        return () -> {
            try {
                reservations.reserve(new ReservationCommand(nextOrderNo(), "SEOUL",
                        products.get().stream().map(productId -> new StockLine(productId, 1)).toList()));
                return true;
            } catch (OutOfStockException outOfStockException) {
                return false;
            }
        };
    }

    // 비교용으로 테스트 안에만 둔다. 상품번호 순으로 행을 잠근 뒤 읽고, 계산하고, 쓴다
    private BooleanSupplier pessimistic(boolean threeProducts) {
        Supplier<List<Long>> productsPerOrder = products(threeProducts, false);
        return () -> Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            String orderNo = nextOrderNo();
            List<Long> products = productsPerOrder.get();
            for (long productId : products) {
                int quantity = jdbcClient.sql("SELECT quantity FROM stock WHERE product_id = :productId FOR UPDATE")
                        .param("productId", productId).query(Integer.class).single();
                if (quantity < 1) {
                    status.setRollbackOnly();
                    return false;
                }
                jdbcClient.sql("UPDATE stock SET quantity = :quantity, updated_at = NOW(3) WHERE product_id = :productId")
                        .param("quantity", quantity - 1).param("productId", productId).update();
            }
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
            for (long productId : products) {
                reservationRepository.insertHeld(orderNo, productId, 1, expiresAt);
            }
            return true;
        }));
    }

    private boolean decrementOnly(long productId) {
        return jdbcClient.sql("UPDATE stock SET quantity = quantity - 1, updated_at = NOW(3) WHERE product_id = :productId AND quantity >= 1")
                .param("productId", productId).update() == 1;
    }

    // 상품 3개 주문은 모든 주문이 함께 사는 특가 상품 하나와, 상품 200개 중에서 주문마다 고르는 일반 상품 둘이다.
    // 상품번호 순으로 잠그되 특가 상품을 마지막에 잠그는 것이 운영 코드의 순서다
    private Supplier<List<Long>> products(boolean threeProducts, boolean hotSpread) {
        long hot = newProduct(PLENTY);
        if (hotSpread) {
            slotStock.spread(hot);
        }
        if (!threeProducts) {
            return () -> List.of(hot);
        }
        List<Long> catalog = IntStream.range(0, CATALOG_SIZE).mapToObj(index -> newProduct(PLENTY)).toList();
        return () -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int first = random.nextInt(CATALOG_SIZE);
            int second = (first + 1 + random.nextInt(CATALOG_SIZE - 1)) % CATALOG_SIZE;
            List<Long> normal = Stream.of(catalog.get(first), catalog.get(second)).sorted().toList();
            return List.of(normal.get(0), normal.get(1), hot);
        };
    }

    private long newProduct(int quantity) {
        return InventoryFixture.newProduct(jdbcClient, quantity, 10);
    }

    private static String nextOrderNo() {
        return "EXP-" + NEXT_ORDER.incrementAndGet();
    }
}
