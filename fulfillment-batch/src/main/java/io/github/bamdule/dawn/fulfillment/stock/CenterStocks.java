package io.github.bamdule.dawn.fulfillment.stock;

import io.github.bamdule.dawn.fulfillment.event.FulfillmentEventPublisher;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 실물 재고는 풀필먼트가 바꾸고, 판매 가능 재고는 STOCK_CHANGED 를 받은 재고 서비스가 바꾼다.
// 실물 재고, 조정 이력, 아웃박스를 한 트랜잭션에 쓴다 (docs/design/05 "실사와 입고")
@Service
public class CenterStocks {

    private static final Logger log = LoggerFactory.getLogger(CenterStocks.class);
    private static final int MAX_STOCKTAKE_ATTEMPTS = 3;

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final FulfillmentEventPublisher fulfillmentEventPublisher;

    public CenterStocks(JdbcClient jdbcClient, TransactionTemplate transactionTemplate,
                        FulfillmentEventPublisher fulfillmentEventPublisher) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
        this.fulfillmentEventPublisher = fulfillmentEventPublisher;
    }

    public void inbound(String centerId, long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("입고 수량은 1 이상이어야 합니다.");
        }
        transactionTemplate.executeWithoutResult(status -> {
            int before = jdbcClient.sql("SELECT quantity FROM center_stock WHERE center_id = :centerId AND product_id = :productId FOR UPDATE")
                    .param("centerId", centerId).param("productId", productId).query(Integer.class).optional().orElse(0);
            jdbcClient.sql("""
                    INSERT INTO center_stock (center_id, product_id, quantity, updated_at) VALUES (:centerId, :productId, :quantity, NOW(3))
                    ON DUPLICATE KEY UPDATE quantity = quantity + :quantity, updated_at = NOW(3)
                    """)
                    .param("centerId", centerId).param("productId", productId).param("quantity", quantity)
                    .update();
            recordAdjustment(centerId, productId, before, before + quantity, "INBOUND");
        });
    }

    // 센 수량에는 출고 지시는 됐지만 아직 선반에서 빠지지 않은 물건이 섞여 있다. 덮어쓰지 않고 그 수량을 뺀 차이만 반영한다.
    // 읽은 뒤 출고 지시가 재고를 바꿨을 수 있어, 읽은 갱신 시각을 조건으로 걸고 0행이면 다시 센다
    public int stocktake(String centerId, long productId, int countedQuantity) {
        for (int attempt = 1; attempt <= MAX_STOCKTAKE_ATTEMPTS; attempt++) {
            Optional<Integer> applied = transactionTemplate.execute(status -> tryStocktake(centerId, productId, countedQuantity));
            if (applied.isPresent()) {
                return applied.get();
            }
        }
        throw new IllegalStateException("실사 중 재고가 계속 바뀌었습니다. (centerId=" + centerId + ", productId=" + productId + ")");
    }

    private Optional<Integer> tryStocktake(String centerId, long productId, int countedQuantity) {
        StockRow stockRow = jdbcClient.sql("SELECT quantity, updated_at FROM center_stock WHERE center_id = :centerId AND product_id = :productId")
                .param("centerId", centerId).param("productId", productId).query(StockRow.class).optional()
                .orElse(new StockRow(0, null));
        int notPicked = jdbcClient.sql("""
                SELECT COALESCE(SUM(l.quantity), 0) FROM shipment_order o JOIN shipment_order_line l ON l.shipment_order_id = o.id
                 WHERE o.center_id = :centerId AND o.status = 'CREATED' AND l.product_id = :productId
                """)
                .param("centerId", centerId).param("productId", productId).query(Integer.class).single();
        int difference = countedQuantity - (stockRow.quantity() + notPicked);
        if (difference == 0) {
            return Optional.of(0);
        }
        int after = stockRow.quantity() + difference;
        if (after < 0) {
            log.error("피킹 전 출고 수량보다 적은 실사 (centerId={}, productId={}, counted={}, notPicked={})",
                    centerId, productId, countedQuantity, notPicked);
            after = 0;
        }
        if (stockRow.updatedAt() == null) {
            jdbcClient.sql("INSERT INTO center_stock (center_id, product_id, quantity, updated_at) VALUES (:centerId, :productId, :quantity, NOW(3))")
                    .param("centerId", centerId).param("productId", productId).param("quantity", after).update();
        } else if (jdbcClient.sql("""
                UPDATE center_stock SET quantity = :after, updated_at = NOW(3)
                 WHERE center_id = :centerId AND product_id = :productId AND updated_at = :readUpdatedAt
                """)
                .param("after", after).param("centerId", centerId).param("productId", productId)
                .param("readUpdatedAt", stockRow.updatedAt())
                .update() == 0) {
            return Optional.empty();
        }
        recordAdjustment(centerId, productId, stockRow.quantity(), after, "STOCKTAKE");
        return Optional.of(after - stockRow.quantity());
    }

    private void recordAdjustment(String centerId, long productId, int before, int after, String reason) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                INSERT INTO stock_adjustment (center_id, product_id, before_qty, after_qty, reason, created_at)
                VALUES (:centerId, :productId, :before, :after, :reason, NOW(3))
                """)
                .param("centerId", centerId).param("productId", productId).param("before", before).param("after", after)
                .param("reason", reason)
                .update(keyHolder, "id");
        fulfillmentEventPublisher.stockChanged(keyHolder.getKey().longValue(), productId, after - before);
    }

    private record StockRow(int quantity, LocalDateTime updatedAt) {
    }
}
