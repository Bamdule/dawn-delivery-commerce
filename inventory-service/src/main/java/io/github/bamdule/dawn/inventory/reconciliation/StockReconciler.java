package io.github.bamdule.dawn.inventory.reconciliation;

import io.github.bamdule.dawn.inventory.reconciliation.FulfillmentStockClient.StockSummary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

// 판매 가능 재고가 실물과 맞는지 상품마다 확인한다. 식은 아래와 같다 (docs/design/02 "지켜야 할 식과 재고 대사").
//   판매 가능 재고 - 부족분 + 잡힌 예약(HELD, PAYING) + 출고 전 확정 수량 = 센터 실물 합
// 출고 대상에 오르기 전의 PAID 와 오가는 이벤트 때문에 순간값이 흔들려서, 어긋난 상품만 10분 뒤 다시 잰다
@Service
public class StockReconciler {

    private static final Logger log = LoggerFactory.getLogger(StockReconciler.class);
    private static final int PAGE_SIZE = 1_000;
    private static final int RECHECK_AFTER_MINUTES = 10;

    private final JdbcClient jdbcClient;
    private final FulfillmentStockClient fulfillmentStockClient;

    public StockReconciler(JdbcClient jdbcClient, FulfillmentStockClient fulfillmentStockClient) {
        this.jdbcClient = jdbcClient;
        this.fulfillmentStockClient = fulfillmentStockClient;
    }

    // 하루 한 번, 출고 지시가 끝난 뒤 전체 상품을 잰다. 이미 열린 기록이 있는 상품은 새로 남기지 않는다
    public int reconcileAll() {
        int suspected = 0;
        long afterProductId = 0;
        while (true) {
            List<Long> productIds = jdbcClient.sql("SELECT product_id FROM stock WHERE product_id > :after ORDER BY product_id LIMIT :limit")
                    .param("after", afterProductId).param("limit", PAGE_SIZE).query(Long.class).list();
            if (productIds.isEmpty()) {
                return suspected;
            }
            for (Measurement measurement : measure(productIds)) {
                if (!measurement.balanced() && recordSuspected(measurement)) {
                    suspected++;
                }
            }
            afterProductId = productIds.getLast();
        }
    }

    // 10분 지난 의심 상품만 다시 잰다. 또 어긋나면 확정하고 경보, 맞으면 흔들림이었다
    public int recheckSuspected() {
        List<Long> productIds = jdbcClient.sql("""
                SELECT product_id FROM stock_reconciliation
                 WHERE status = 'SUSPECTED' AND first_seen_at < NOW(3) - INTERVAL :minutes MINUTE
                 ORDER BY first_seen_at LIMIT :limit
                """)
                .param("minutes", RECHECK_AFTER_MINUTES).param("limit", PAGE_SIZE).query(Long.class).list();
        if (productIds.isEmpty()) {
            return 0;
        }
        int confirmed = 0;
        for (Measurement measurement : measure(productIds)) {
            String status = measurement.balanced() ? "RESOLVED" : "CONFIRMED";
            jdbcClient.sql("""
                    UPDATE stock_reconciliation
                       SET status = :status, left_side = :leftSide, right_side = :rightSide, checked_at = NOW(3)
                     WHERE product_id = :productId AND status = 'SUSPECTED'
                    """)
                    .param("status", status).param("leftSide", measurement.leftSide()).param("rightSide", measurement.rightSide())
                    .param("productId", measurement.productId())
                    .update();
            if (!measurement.balanced()) {
                confirmed++;
                log.error("재고 대사 불일치 (productId={}, leftSide={}, rightSide={})",
                        measurement.productId(), measurement.leftSide(), measurement.rightSide());
            }
        }
        return confirmed;
    }

    private List<Measurement> measure(List<Long> productIds) {
        Map<Long, Integer> inventorySide = new HashMap<>();
        jdbcClient.sql("""
                SELECT s.product_id,
                       s.quantity + COALESCE((SELECT SUM(ss.quantity) FROM stock_slot ss WHERE ss.product_id = s.product_id), 0)
                         - s.deficit
                         + COALESCE((SELECT SUM(r.quantity - r.cancelled_quantity) FROM stock_reservation r
                                      WHERE r.product_id = s.product_id AND r.status IN ('HELD', 'PAYING')), 0) AS quantity
                  FROM stock s WHERE s.product_id IN (:productIds)
                """)
                .param("productIds", productIds)
                .query(rs -> {
                    inventorySide.put(rs.getLong("product_id"), rs.getInt("quantity"));
                });
        return fulfillmentStockClient.summary(productIds).stream()
                .map(summary -> new Measurement(summary.productId(),
                        inventorySide.getOrDefault(summary.productId(), 0) + summary.pendingQuantity(), summary.physicalQuantity()))
                .toList();
    }

    private boolean recordSuspected(Measurement measurement) {
        return jdbcClient.sql("""
                INSERT INTO stock_reconciliation (product_id, left_side, right_side, status, first_seen_at)
                SELECT :productId, :leftSide, :rightSide, 'SUSPECTED', NOW(3) FROM DUAL
                 WHERE NOT EXISTS (SELECT 1 FROM stock_reconciliation
                                    WHERE product_id = :productId AND status IN ('SUSPECTED', 'CONFIRMED'))
                """)
                .param("productId", measurement.productId()).param("leftSide", measurement.leftSide())
                .param("rightSide", measurement.rightSide())
                .update() == 1;
    }

    record Measurement(long productId, int leftSide, int rightSide) {

        boolean balanced() {
            return leftSide == rightSide;
        }
    }
}
