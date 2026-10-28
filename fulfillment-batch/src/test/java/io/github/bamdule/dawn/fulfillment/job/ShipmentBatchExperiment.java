package io.github.bamdule.dawn.fulfillment.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import io.github.bamdule.dawn.fulfillment.target.OrderClient;
import io.github.bamdule.dawn.fulfillment.target.OrderClient.PaidPage;
import io.github.bamdule.dawn.messaging.test.ExperimentReport;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 실험 4. 30만 주문(품목 90만 줄)으로 스텝별 시간을 잰다 (docs/experiments/04-출고-배치.md).
// 고르게 나뉠 때와 한 센터에 절반이 몰릴 때를 비교한다. 주문 서비스의 결제 완료 목록은 가짜가 페이지로 준다
@Tag("experiment")
@SpringBootTest(properties = {
        "dawn.fulfillment.scheduler.enabled=false",
        "dawn.outbox.relay.enabled=false",
        "dawn.metrics.refresh.enabled=false"})
@Import({MySqlContainers.class, KafkaContainers.class})
class ShipmentBatchExperiment {

    private static final int ORDERS = 300_000;
    private static final int PRODUCTS = 5_000;
    private static final int PAGE_SIZE = 1_000;

    @MockitoBean
    private OrderClient orderClient;

    @Autowired
    private ShipmentJobRunner shipmentJobRunner;

    @Autowired
    private JdbcClient jdbcClient;

    private final Map<LocalDateTime, List<String>> paidLists = new ConcurrentHashMap<>();

    @Test
    void 삼십만_주문의_스텝별_시간을_고른_분산과_한_센터_쏠림에서_잰다() throws Exception {
        ExperimentReport report = new ExperimentReport("04-shipment-batch");
        when(orderClient.paidOrders(any(), any(), any(), anyInt())).thenAnswer(invocation -> page(
                invocation.getArgument(0), invocation.getArgument(2)));
        when(orderClient.detail(any())).thenReturn(Optional.empty());
        jdbcClient.sql("""
                INSERT IGNORE INTO center (center_id, region_code) VALUES
                    ('SEOUL-A', 'SEOUL'), ('SEOUL-B', 'SEOUL'), ('DAEJEON-A', 'DAEJEON'), ('BUSAN-A', 'BUSAN')
                """).update();

        // 고른 분산: 서울 주문이 절반이고 서울 센터 둘에 재고가 같아 네 센터가 4분의 1씩 나간다
        run(report, "고른 분산", LocalDateTime.of(2100, 1, 1, 23, 0), "E", 1_000_000L, false);
        // 쏠림: 서울 B 에 재고가 없어 서울 주문 절반이 모두 서울 A 로 간다
        run(report, "서울 A 에 절반 쏠림", LocalDateTime.of(2100, 1, 11, 23, 0), "S", 2_000_000L, true);
    }

    private void run(ExperimentReport report, String label, LocalDateTime cutoffAt, String prefix, long productBase,
                     boolean skewed) throws Exception {
        long preparedAt = System.nanoTime();
        prepare(cutoffAt, prefix, productBase, skewed);
        report.line("## " + label + " (주문 %,d, 품목 %,d 줄, 준비 %.0f초)".formatted(ORDERS, ORDERS * 3,
                (System.nanoTime() - preparedAt) / 1e9));

        JobExecution jobExecution = shipmentJobRunner.run(cutoffAt);

        report.line("잡 상태 " + jobExecution.getStatus() + ", 회차 상태 " + jdbcClient.sql("SELECT status FROM cutoff_run WHERE cutoff_at = :cutoffAt")
                .param("cutoffAt", cutoffAt).query(String.class).single());
        report.line("");
        report.line("| 스텝 | 걸린 시간 |");
        report.line("|---|---|");
        jdbcClient.sql("""
                SELECT step_name, TIMESTAMPDIFF(MICROSECOND, start_time, end_time) / 1000000 AS seconds
                  FROM BATCH_STEP_EXECUTION WHERE job_execution_id = :id ORDER BY step_execution_id
                """)
                .param("id", jobExecution.getId())
                .query((rs, rowNum) -> "| %s | %s |".formatted(rs.getString("step_name"), format(rs.getDouble("seconds"))))
                .list()
                .forEach(report::line);
        report.line("");
        report.line("| 센터 | 출고 지시 | 품목 수량 |");
        report.line("|---|---|---|");
        jdbcClient.sql("""
                SELECT o.center_id, COUNT(DISTINCT o.id) AS orders, SUM(l.quantity) AS quantity
                  FROM shipment_order o JOIN shipment_order_line l ON l.shipment_order_id = o.id
                 WHERE o.cutoff_at = :cutoffAt GROUP BY o.center_id ORDER BY o.center_id
                """)
                .param("cutoffAt", cutoffAt)
                .query((rs, rowNum) -> "| %s | %,d | %,d |".formatted(rs.getString("center_id"), rs.getLong("orders"), rs.getLong("quantity")))
                .list()
                .forEach(report::line);
        report.line("");
    }

    // 출고 대상과 품목, 센터 재고를 SQL 로 한 번에 만든다. 주문은 서울 절반, 대전과 부산 4분의 1씩이다
    private void prepare(LocalDateTime cutoffAt, String prefix, long productBase, boolean skewed) {
        jdbcClient.sql("SET SESSION cte_max_recursion_depth = 1000000").update();
        jdbcClient.sql("""
                INSERT INTO shipment_target (order_no, status, region_code, recipient_name, address, paid_at, updated_at)
                WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < :orders)
                SELECT CONCAT(:prefix, LPAD(n, 9, '0')), 'WAITING',
                       CASE WHEN n % 4 IN (0, 1) THEN 'SEOUL' WHEN n % 4 = 2 THEN 'DAEJEON' ELSE 'BUSAN' END,
                       '김새벽', '서울시 강남구 테헤란로 1', :cutoffAt - INTERVAL (n % 86000) SECOND, NOW(3)
                  FROM seq
                """)
                .param("orders", ORDERS).param("prefix", prefix).param("cutoffAt", cutoffAt)
                .update();
        jdbcClient.sql("""
                INSERT INTO shipment_target_line (order_no, product_id, quantity, waiting_qty)
                WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < :orders)
                SELECT CONCAT(:prefix, LPAD(seq.n, 9, '0')), :productBase + MOD(seq.n * 7 + item.k * 1777, :products), 1, 1
                  FROM seq CROSS JOIN (SELECT 0 AS k UNION ALL SELECT 1 UNION ALL SELECT 2) item
                """)
                .param("orders", ORDERS).param("prefix", prefix).param("productBase", productBase).param("products", PRODUCTS)
                .update();
        jdbcClient.sql("""
                INSERT INTO center_stock (center_id, product_id, quantity, updated_at)
                WITH RECURSIVE seq (n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n < :products - 1)
                SELECT c.center_id, :productBase + seq.n,
                       CASE WHEN :skewed AND c.center_id = 'SEOUL-B' THEN 0 ELSE 1000 END, NOW(3)
                  FROM seq CROSS JOIN center c
                """)
                .param("products", PRODUCTS).param("productBase", productBase).param("skewed", skewed)
                .update();
        paidLists.put(cutoffAt, jdbcClient.sql("SELECT order_no FROM shipment_target WHERE order_no LIKE CONCAT(:prefix, '%') ORDER BY order_no")
                .param("prefix", prefix).query(String.class).list());
    }

    // 커서 자리에 다음 페이지의 시작 위치를 넣는다
    private PaidPage page(LocalDateTime cutoffAt, Long cursorId) {
        List<String> orderNos = paidLists.getOrDefault(cutoffAt, List.of());
        int from = cursorId == null ? 0 : cursorId.intValue();
        int to = Math.min(from + PAGE_SIZE, orderNos.size());
        return new PaidPage(orderNos.subList(from, to), to < orderNos.size() ? cutoffAt : null,
                to < orderNos.size() ? (long) to : null);
    }

    private static String format(double seconds) {
        Duration duration = Duration.ofMillis((long) (seconds * 1000));
        return duration.toMinutes() > 0
                ? "%d분 %d초".formatted(duration.toMinutes(), duration.toSecondsPart())
                : "%.1f초".formatted(seconds);
    }
}
