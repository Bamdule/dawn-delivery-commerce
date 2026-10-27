package io.github.bamdule.dawn.inventory.stock;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StockRepository {

    private final JdbcClient jdbcClient;

    public StockRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // 읽지 않고 조건을 건 한 문장으로 뺀다. 0행이면 재고가 모자란 것이다 (docs/design/02 방법 2)
    public boolean decrease(long productId, int quantity) {
        return jdbcClient.sql("""
                UPDATE stock
                   SET quantity = quantity - :quantity, updated_at = NOW(3)
                 WHERE product_id = :productId
                   AND slot_mode = 'NONE'
                   AND quantity >= :quantity
                """)
                .param("productId", productId)
                .param("quantity", quantity)
                .update() == 1;
    }

    // 슬롯 상품은 슬롯 합이 판매 가능 재고라 stock.quantity 대신 슬롯을 더해 본다
    public List<Long> findSoldOutProductIds() {
        return jdbcClient.sql("""
                SELECT s.product_id
                  FROM stock s
                 WHERE (s.slot_mode = 'NONE' AND s.quantity = 0)
                    OR (s.slot_mode <> 'NONE'
                        AND (SELECT COALESCE(SUM(ss.quantity), 0) FROM stock_slot ss WHERE ss.product_id = s.product_id) = 0)
                 ORDER BY s.product_id
                """)
                .query(Long.class)
                .list();
    }
}
