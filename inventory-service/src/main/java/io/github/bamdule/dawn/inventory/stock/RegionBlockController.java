package io.github.bamdule.dawn.inventory.stock;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

// 권역 불일치가 반복된 상품을 그 권역에 팔지 않게 운영자가 설정한다 (docs/design/05)
@RestController
public class RegionBlockController {

    private final JdbcClient jdbcClient;

    public RegionBlockController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PutMapping("/admin/stocks/{productId}/blocked-regions/{regionCode}")
    public ResponseEntity<Void> block(@PathVariable long productId, @PathVariable String regionCode) {
        jdbcClient.sql("""
                INSERT IGNORE INTO stock_region_block (product_id, region_code, created_at) VALUES (:productId, :regionCode, NOW(3))
                """)
                .param("productId", productId)
                .param("regionCode", regionCode)
                .update();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/admin/stocks/{productId}/blocked-regions/{regionCode}")
    public ResponseEntity<Void> unblock(@PathVariable long productId, @PathVariable String regionCode) {
        jdbcClient.sql("DELETE FROM stock_region_block WHERE product_id = :productId AND region_code = :regionCode")
                .param("productId", productId)
                .param("regionCode", regionCode)
                .update();
        return ResponseEntity.noContent().build();
    }
}
