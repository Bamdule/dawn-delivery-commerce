package io.github.bamdule.dawn.inventory.stock;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    public Map<Long, SlotMode> findModes(List<Long> productIds) {
        return jdbcClient.sql("SELECT product_id, slot_mode FROM stock WHERE product_id IN (:productIds)")
                .param("productIds", productIds)
                .query((resultSet, rowNum) -> Map.entry(resultSet.getLong("product_id"), SlotMode.valueOf(resultSet.getString("slot_mode"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public SlotMode findMode(long productId) {
        return SlotMode.valueOf(jdbcClient.sql("SELECT slot_mode FROM stock WHERE product_id = :productId")
                .param("productId", productId)
                .query(String.class)
                .single());
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
