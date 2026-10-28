package io.github.bamdule.dawn.inventory.reconciliation;

import io.github.bamdule.dawn.inventory.stock.SellableStock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 대사가 찾은 차이를 사람이 확인해 판매 가능 재고를 바로잡는다. 늘리는 쪽은 부족분부터 갚고,
// 줄이는 쪽은 0 아래로 갈 몫을 부족분에 둔다. 다른 변경과 같은 규칙이다 (docs/design/02 "취소 복원과 부족분")
@Service
public class StockCorrections {

    private final SellableStock sellableStock;
    private final JdbcClient jdbcClient;

    public StockCorrections(SellableStock sellableStock, JdbcClient jdbcClient) {
        this.sellableStock = sellableStock;
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public void correct(long productId, int delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("보정 수량은 0 이 아니어야 합니다.");
        }
        String adjustmentId = UUID.randomUUID().toString();
        if (delta > 0) {
            sellableStock.increaseForAdjustment(adjustmentId, productId, delta);
        } else {
            sellableStock.decreaseForStocktake(adjustmentId, productId, -delta);
        }
        jdbcClient.sql("""
                UPDATE stock_reconciliation SET status = 'CORRECTED', checked_at = NOW(3)
                 WHERE product_id = :productId AND status IN ('SUSPECTED', 'CONFIRMED')
                """)
                .param("productId", productId)
                .update();
    }
}
