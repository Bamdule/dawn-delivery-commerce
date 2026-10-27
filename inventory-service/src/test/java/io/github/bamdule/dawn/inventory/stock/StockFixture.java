package io.github.bamdule.dawn.inventory.stock;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;

// 테스트끼리 같은 컨테이너를 쓰므로 상품번호가 겹치지 않게 나눠 준다
final class StockFixture {

    private static final AtomicLong NEXT_PRODUCT_ID = new AtomicLong(100_000);

    private StockFixture() {
    }

    static long newProduct(JdbcClient jdbcClient, int quantity) {
        long productId = NEXT_PRODUCT_ID.incrementAndGet();
        jdbcClient.sql("INSERT INTO stock (product_id, quantity, slot_mode, updated_at) VALUES (:productId, :quantity, 'NONE', NOW(3))")
                .param("productId", productId)
                .param("quantity", quantity)
                .update();
        return productId;
    }

    static int quantityOf(JdbcClient jdbcClient, long productId) {
        return jdbcClient.sql("SELECT quantity FROM stock WHERE product_id = :productId")
                .param("productId", productId)
                .query(Integer.class)
                .single();
    }
}
