package io.github.bamdule.dawn.payment.refund;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefundRepository {

    private static final String COLUMNS = """
            r.id, r.payment_id, r.refund_key, r.amount, r.status, p.order_no, p.payment_round, p.idempotency_key AS payment_key
            """;

    private final JdbcClient jdbcClient;

    public RefundRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<RefundRow> findByKey(String refundKey) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM refund r JOIN payment p ON p.id = r.payment_id WHERE r.refund_key = :key")
                .param("key", refundKey).query(RefundRow.class).optional();
    }

    // 진행 중과 완료를 더하고 실패는 뺀다. 이 합이 승인 금액을 넘는 접수는 거절한다 (docs/design/04)
    public long sumNotFailed(long paymentId) {
        return jdbcClient.sql("SELECT COALESCE(SUM(amount), 0) FROM refund WHERE payment_id = :paymentId AND status <> 'FAILED'")
                .param("paymentId", paymentId).query(Long.class).single();
    }

    public void insertRequested(long paymentId, String refundKey, long amount) {
        jdbcClient.sql("""
                INSERT INTO refund (payment_id, refund_key, amount, status, requested_at, updated_at)
                VALUES (:paymentId, :key, :amount, 'REQUESTED', NOW(3), NOW(3))
                """)
                .param("paymentId", paymentId).param("key", refundKey).param("amount", amount).update();
    }

    public boolean transition(long refundId, List<String> from, String to) {
        return jdbcClient.sql("UPDATE refund SET status = :to, last_sent_at = NOW(3), updated_at = NOW(3) WHERE id = :id AND status IN (:from)")
                .param("to", to).param("id", refundId).param("from", from).update() == 1;
    }

    public List<DueRefund> findRequested(int limit) {
        return jdbcClient.sql("""
                SELECT r.id, r.refund_key, r.amount, p.order_no, p.payment_round, p.idempotency_key AS payment_key,
                       r.requested_at <= NOW(3) - INTERVAL 1 HOUR AS overdue
                  FROM refund r JOIN payment p ON p.id = r.payment_id
                 WHERE r.status = 'REQUESTED'
                 ORDER BY r.requested_at LIMIT :limit
                """)
                .param("limit", limit).query(DueRefund.class).list();
    }

    public record RefundRow(long id, long paymentId, String refundKey, long amount, String status, String orderNo,
                            int paymentRound, String paymentKey) {
    }

    public record DueRefund(long id, String refundKey, long amount, String orderNo, int paymentRound, String paymentKey,
                            boolean overdue) {
    }
}
