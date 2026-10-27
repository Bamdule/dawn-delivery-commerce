package io.github.bamdule.dawn.inventory.reservation;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;

final class InventoryFixture {

    private static final AtomicLong NEXT_PRODUCT_ID = new AtomicLong(500_000);
    private static final AtomicLong NEXT_ORDER = new AtomicLong();

    private InventoryFixture() {
    }

    static long newProduct(JdbcClient jdbcClient, int quantity, int holdMinutes) {
        long productId = NEXT_PRODUCT_ID.incrementAndGet();
        jdbcClient.sql("""
                INSERT INTO stock (product_id, quantity, slot_mode, hold_minutes, deficit, updated_at)
                VALUES (:productId, :quantity, 'NONE', :holdMinutes, 0, NOW(3))
                """)
                .param("productId", productId)
                .param("quantity", quantity)
                .param("holdMinutes", holdMinutes)
                .update();
        return productId;
    }

    static String newOrderNo() {
        return "20261028-" + String.format("%06d", NEXT_ORDER.incrementAndGet()) + "-" + System.nanoTime() % 100_000;
    }

    static int quantityOf(JdbcClient jdbcClient, long productId) {
        return jdbcClient.sql("SELECT quantity FROM stock WHERE product_id = :productId")
                .param("productId", productId).query(Integer.class).single();
    }

    static int deficitOf(JdbcClient jdbcClient, long productId) {
        return jdbcClient.sql("SELECT deficit FROM stock WHERE product_id = :productId")
                .param("productId", productId).query(Integer.class).single();
    }

    static String statusOf(JdbcClient jdbcClient, String orderNo, long productId) {
        return jdbcClient.sql("SELECT status FROM stock_reservation WHERE order_no = :orderNo AND product_id = :productId")
                .param("orderNo", orderNo).param("productId", productId).query(String.class).single();
    }

    static void expireNow(JdbcClient jdbcClient, String orderNo) {
        jdbcClient.sql("UPDATE stock_reservation SET expires_at = NOW(3) - INTERVAL 1 SECOND WHERE order_no = :orderNo")
                .param("orderNo", orderNo).update();
    }
}
