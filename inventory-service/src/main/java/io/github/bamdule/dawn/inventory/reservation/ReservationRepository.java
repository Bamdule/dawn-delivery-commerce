package io.github.bamdule.dawn.inventory.reservation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private final JdbcClient jdbcClient;

    public ReservationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertHeld(String orderNo, long productId, int quantity, LocalDateTime expiresAt) {
        jdbcClient.sql("""
                INSERT INTO stock_reservation
                    (reservation_id, order_no, product_id, quantity, status, expires_at, created_at, updated_at)
                VALUES (:reservationId, :orderNo, :productId, :quantity, 'HELD', :expiresAt, NOW(3), NOW(3))
                """)
                .param("reservationId", UUID.randomUUID().toString())
                .param("orderNo", orderNo)
                .param("productId", productId)
                .param("quantity", quantity)
                .param("expiresAt", expiresAt)
                .update();
    }

    public List<ReservationRow> findByOrderNo(String orderNo) {
        return jdbcClient.sql("""
                SELECT reservation_id, order_no, product_id, quantity, cancelled_quantity, status, payment_round, expires_at
                  FROM stock_reservation WHERE order_no = :orderNo ORDER BY product_id
                """)
                .param("orderNo", orderNo)
                .query(ReservationRow.class)
                .list();
    }

    public Optional<ReservationRow> findForUpdate(String orderNo, long productId) {
        return jdbcClient.sql("""
                SELECT reservation_id, order_no, product_id, quantity, cancelled_quantity, status, payment_round, expires_at
                  FROM stock_reservation WHERE order_no = :orderNo AND product_id = :productId FOR UPDATE
                """)
                .param("orderNo", orderNo)
                .param("productId", productId)
                .query(ReservationRow.class)
                .optional();
    }

    // HELD 이고 만료 전일 때만. 만료 배치의 해제와 같은 HELD 조건이라 둘이 겹쳐도 하나만 이긴다
    public int markPaying(String orderNo, int paymentRound) {
        return jdbcClient.sql("""
                UPDATE stock_reservation SET status = 'PAYING', payment_round = :round, updated_at = NOW(3)
                 WHERE order_no = :orderNo AND status = 'HELD' AND expires_at > NOW(3)
                """)
                .param("orderNo", orderNo)
                .param("round", paymentRound)
                .update();
    }

    // 늦게 도착한 앞 회차의 되돌림이 다음 회차의 PAYING 을 건드리지 않게 회차를 조건으로 건다
    public int revertPaying(String orderNo, int paymentRound) {
        return jdbcClient.sql("""
                UPDATE stock_reservation SET status = 'HELD', updated_at = NOW(3)
                 WHERE order_no = :orderNo AND status = 'PAYING' AND payment_round = :round
                """)
                .param("orderNo", orderNo)
                .param("round", paymentRound)
                .update();
    }

    public int confirmPaying(String orderNo) {
        return jdbcClient.sql("""
                UPDATE stock_reservation SET status = 'CONFIRMED', updated_at = NOW(3)
                 WHERE order_no = :orderNo AND status = 'PAYING'
                """)
                .param("orderNo", orderNo)
                .update();
    }

    public int confirmPaying(String orderNo, int paymentRound) {
        return jdbcClient.sql("""
                UPDATE stock_reservation SET status = 'CONFIRMED', updated_at = NOW(3)
                 WHERE order_no = :orderNo AND status = 'PAYING' AND payment_round = :round
                """)
                .param("orderNo", orderNo)
                .param("round", paymentRound)
                .update();
    }

    public boolean releaseHeld(String reservationId) {
        return jdbcClient.sql("""
                UPDATE stock_reservation SET status = 'RELEASED', updated_at = NOW(3)
                 WHERE reservation_id = :reservationId AND status = 'HELD' AND expires_at <= NOW(3)
                """)
                .param("reservationId", reservationId)
                .update() == 1;
    }

    public boolean releasePaying(String reservationId, int paymentRound) {
        return jdbcClient.sql("""
                UPDATE stock_reservation SET status = 'RELEASED', updated_at = NOW(3)
                 WHERE reservation_id = :reservationId AND status = 'PAYING' AND payment_round = :round
                """)
                .param("reservationId", reservationId)
                .param("round", paymentRound)
                .update() == 1;
    }

    public void addCancelled(String reservationId, int quantity) {
        jdbcClient.sql("""
                UPDATE stock_reservation
                   SET cancelled_quantity = cancelled_quantity + :quantity,
                       status = CASE WHEN cancelled_quantity + :quantity >= quantity THEN 'RESTORED' ELSE status END,
                       updated_at = NOW(3)
                 WHERE reservation_id = :reservationId
                """)
                .param("quantity", quantity)
                .param("reservationId", reservationId)
                .update();
    }

    public List<ReservationRow> findExpiredHeld(int limit) {
        return jdbcClient.sql("""
                SELECT reservation_id, order_no, product_id, quantity, cancelled_quantity, status, payment_round, expires_at
                  FROM stock_reservation WHERE status = 'HELD' AND expires_at <= NOW(3)
                 ORDER BY expires_at LIMIT :limit
                """)
                .param("limit", limit)
                .query(ReservationRow.class)
                .list();
    }

    public List<ExpiredPayingOrder> findExpiredPayingOrders(int limit) {
        return jdbcClient.sql("""
                SELECT order_no, MIN(payment_round) AS payment_round
                  FROM stock_reservation WHERE status = 'PAYING' AND expires_at <= NOW(3)
                 GROUP BY order_no ORDER BY MIN(expires_at) LIMIT :limit
                """)
                .param("limit", limit)
                .query(ExpiredPayingOrder.class)
                .list();
    }

    public record ExpiredPayingOrder(String orderNo, int paymentRound) {
    }
}
