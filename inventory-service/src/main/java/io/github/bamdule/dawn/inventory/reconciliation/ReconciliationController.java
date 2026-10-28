package io.github.bamdule.dawn.inventory.reconciliation;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReconciliationController {

    private final StockCorrections stockCorrections;
    private final JdbcClient jdbcClient;

    public ReconciliationController(StockCorrections stockCorrections, JdbcClient jdbcClient) {
        this.stockCorrections = stockCorrections;
        this.jdbcClient = jdbcClient;
    }

    // 경보가 울린 상품. 사람이 실물을 확인한 뒤 보정한다
    @GetMapping("/admin/stock-reconciliations")
    public List<Mismatch> confirmed() {
        return jdbcClient.sql("""
                SELECT product_id, left_side, right_side, first_seen_at FROM stock_reconciliation
                 WHERE status = 'CONFIRMED' ORDER BY first_seen_at
                """).query(Mismatch.class).list();
    }

    @PostMapping("/admin/stocks/{productId}/corrections")
    public ResponseEntity<Void> correct(@PathVariable long productId, @RequestBody CorrectionRequest correctionRequest) {
        stockCorrections.correct(productId, correctionRequest.delta());
        return ResponseEntity.noContent().build();
    }

    public record Mismatch(long productId, int leftSide, int rightSide, LocalDateTime firstSeenAt) {
    }

    // 판매 가능 재고에 더하거나 뺄 수량. 대사 식의 차이(오른쪽 - 왼쪽)를 그대로 넣으면 식이 맞는다
    public record CorrectionRequest(int delta) {
    }
}
