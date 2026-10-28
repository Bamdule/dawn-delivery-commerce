package io.github.bamdule.dawn.fulfillment.stock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 재고 서비스의 재고 대사가 부른다. 대사 식의 오른쪽(센터 실물 합)과, 왼쪽의 출고 전 확정 수량을 풀필먼트가 준다.
// 출고 전 확정 수량은 출고 대상 품목의 대기와 예외 수량이다. 못 채움과 취소는 이미 판매 가능 재고로 돌아갔다 (docs/design/02)
@RestController
public class StockSummaryController {

    private static final int MAX_PRODUCTS = 1_000;

    private final JdbcClient jdbcClient;

    public StockSummaryController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostMapping("/stocks/summary")
    public List<StockSummary> summary(@RequestBody SummaryRequest summaryRequest) {
        List<Long> productIds = summaryRequest.productIds();
        if (productIds.isEmpty()) {
            return List.of();
        }
        if (productIds.size() > MAX_PRODUCTS) {
            throw new IllegalArgumentException("상품은 한 번에 1,000개까지 물을 수 있습니다.");
        }
        Map<Long, Integer> physical = sums("""
                SELECT product_id, SUM(quantity) AS quantity FROM center_stock WHERE product_id IN (:productIds) GROUP BY product_id
                """, productIds);
        Map<Long, Integer> pending = sums("""
                SELECT product_id, SUM(waiting_qty + exception_qty) AS quantity FROM shipment_target_line
                 WHERE product_id IN (:productIds) GROUP BY product_id
                """, productIds);
        return productIds.stream()
                .map(productId -> new StockSummary(productId, physical.getOrDefault(productId, 0), pending.getOrDefault(productId, 0)))
                .toList();
    }

    private Map<Long, Integer> sums(String sql, List<Long> productIds) {
        Map<Long, Integer> sums = new HashMap<>();
        jdbcClient.sql(sql).param("productIds", productIds).query(rs -> {
            sums.put(rs.getLong("product_id"), rs.getInt("quantity"));
        });
        return sums;
    }

    public record SummaryRequest(List<Long> productIds) {
    }

    public record StockSummary(long productId, int physicalQuantity, int pendingQuantity) {
    }
}
