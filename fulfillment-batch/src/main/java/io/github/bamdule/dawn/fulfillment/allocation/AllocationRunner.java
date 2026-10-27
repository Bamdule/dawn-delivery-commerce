package io.github.bamdule.dawn.fulfillment.allocation;

import io.github.bamdule.dawn.fulfillment.allocation.Allocator.Center;
import io.github.bamdule.dawn.fulfillment.allocation.Allocator.Decision;
import io.github.bamdule.dawn.fulfillment.allocation.Allocator.Line;
import io.github.bamdule.dawn.fulfillment.allocation.Allocator.Order;
import io.github.bamdule.dawn.fulfillment.allocation.Allocator.Plan;
import io.github.bamdule.dawn.fulfillment.allocation.Allocator.Result;
import io.github.bamdule.dawn.fulfillment.run.CutoffRunRepository;
import io.github.bamdule.dawn.fulfillment.run.CutoffRunStatus;
import io.github.bamdule.dawn.fulfillment.run.StepPreconditionException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 할당 결과 표와 판정 표에만 쓴다. 품목 수량은 판정 발행과 출고 지시 스텝만 옮긴다.
// 그래서 저장 중에 죽으면 결과를 지우고 같은 입력으로 다시 계산하면 된다 (docs/design/05 "저장과 재시작")
@Service
public class AllocationRunner {

    private static final int SAVE_BATCH_SIZE = 1_000;

    private final CutoffRunRepository cutoffRunRepository;
    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public AllocationRunner(CutoffRunRepository cutoffRunRepository, JdbcClient jdbcClient, JdbcTemplate jdbcTemplate,
                            TransactionTemplate transactionTemplate) {
        this.cutoffRunRepository = cutoffRunRepository;
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public void allocate(LocalDateTime cutoffAt) {
        CutoffRunStatus status = cutoffRunRepository.status(cutoffAt);
        if (status.reached(CutoffRunStatus.ALLOCATED)) {
            return;
        }
        if (status != CutoffRunStatus.TARGET_FIXED) {
            throw new StepPreconditionException("대상 확정 전에는 할당할 수 없습니다. (cutoffAt=" + cutoffAt + ")");
        }
        deleteInChunks("allocation_result", cutoffAt);
        deleteInChunks("unfillable_decision", cutoffAt);
        snapshotStockOnce(cutoffAt);
        Plan plan = Allocator.allocate(readInput(cutoffAt), readSnapshot(cutoffAt), readCenters(), readOutsideCenters());
        saveResults(cutoffAt, plan.results());
        saveDecisions(cutoffAt, plan.decisions());
        cutoffRunRepository.transition(cutoffAt, CutoffRunStatus.TARGET_FIXED, CutoffRunStatus.ALLOCATED);
    }

    // 할당 중에도 입고와 실사가 실물 재고를 바꾼다. 처음 한 번만 복사해, 다시 계산해도 같은 재고를 본다
    private void snapshotStockOnce(LocalDateTime cutoffAt) {
        transactionTemplate.executeWithoutResult(status -> {
            boolean exists = jdbcClient.sql("SELECT COUNT(*) FROM allocation_stock_snapshot WHERE cutoff_at = :cutoffAt")
                    .param("cutoffAt", cutoffAt).query(Integer.class).single() > 0;
            if (!exists) {
                jdbcClient.sql("""
                        INSERT INTO allocation_stock_snapshot (cutoff_at, center_id, product_id, quantity)
                        SELECT :cutoffAt, center_id, product_id, quantity FROM center_stock
                        """)
                        .param("cutoffAt", cutoffAt)
                        .update();
            }
        });
    }

    private List<Order> readInput(LocalDateTime cutoffAt) {
        Map<String, List<Line>> linesByOrder = new LinkedHashMap<>();
        Map<String, String> regionByOrder = new HashMap<>();
        jdbcClient.sql("""
                SELECT order_no, product_id, quantity, region_code FROM allocation_input
                 WHERE cutoff_at = :cutoffAt ORDER BY paid_at, order_no, product_id
                """)
                .param("cutoffAt", cutoffAt)
                .query(rs -> {
                    String orderNo = rs.getString("order_no");
                    linesByOrder.computeIfAbsent(orderNo, key -> new ArrayList<>())
                            .add(new Line(rs.getLong("product_id"), rs.getInt("quantity")));
                    regionByOrder.put(orderNo, rs.getString("region_code"));
                });
        return linesByOrder.entrySet().stream()
                .map(entry -> new Order(entry.getKey(), regionByOrder.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    private Map<String, Map<Long, Integer>> readSnapshot(LocalDateTime cutoffAt) {
        Map<String, Map<Long, Integer>> snapshot = new HashMap<>();
        jdbcClient.sql("SELECT center_id, product_id, quantity FROM allocation_stock_snapshot WHERE cutoff_at = :cutoffAt")
                .param("cutoffAt", cutoffAt)
                .query(rs -> {
                    snapshot.computeIfAbsent(rs.getString("center_id"), key -> new HashMap<>())
                            .put(rs.getLong("product_id"), rs.getInt("quantity"));
                });
        return snapshot;
    }

    private List<Center> readCenters() {
        return jdbcClient.sql("SELECT center_id, region_code FROM center").query(Center.class).list();
    }

    private Map<String, List<String>> readOutsideCenters() {
        Map<String, List<String>> byRegion = new HashMap<>();
        jdbcClient.sql("""
                SELECT cc.region_code, cc.center_id FROM center_coverage cc JOIN center c ON c.center_id = cc.center_id
                 WHERE c.region_code <> cc.region_code ORDER BY cc.region_code, cc.center_id
                """)
                .query(rs -> {
                    byRegion.computeIfAbsent(rs.getString("region_code"), key -> new ArrayList<>()).add(rs.getString("center_id"));
                });
        return byRegion;
    }

    private void saveResults(LocalDateTime cutoffAt, List<Result> results) {
        Timestamp cutoff = Timestamp.valueOf(cutoffAt);
        saveInBatches("""
                INSERT INTO allocation_result (cutoff_at, order_no, product_id, center_id, quantity) VALUES (?, ?, ?, ?, ?)
                """, results.stream()
                .map(result -> new Object[] {cutoff, result.orderNo(), result.productId(), result.centerId(), result.quantity()})
                .toList());
    }

    private void saveDecisions(LocalDateTime cutoffAt, List<Decision> decisions) {
        Timestamp cutoff = Timestamp.valueOf(cutoffAt);
        saveInBatches("""
                INSERT INTO unfillable_decision (cutoff_at, order_no, product_id, quantity, reason, created_at)
                VALUES (?, ?, ?, ?, ?, NOW(3))
                """, decisions.stream()
                .map(decision -> new Object[] {cutoff, decision.orderNo(), decision.productId(), decision.quantity(), decision.reason()})
                .toList());
    }

    private void saveInBatches(String sql, List<Object[]> rows) {
        for (int from = 0; from < rows.size(); from += SAVE_BATCH_SIZE) {
            List<Object[]> batch = rows.subList(from, Math.min(from + SAVE_BATCH_SIZE, rows.size()));
            transactionTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(sql, batch));
        }
    }

    private void deleteInChunks(String table, LocalDateTime cutoffAt) {
        int deleted;
        do {
            deleted = jdbcClient.sql("DELETE FROM " + table + " WHERE cutoff_at = :cutoffAt LIMIT " + SAVE_BATCH_SIZE)
                    .param("cutoffAt", cutoffAt)
                    .update();
        } while (deleted == SAVE_BATCH_SIZE);
    }
}
