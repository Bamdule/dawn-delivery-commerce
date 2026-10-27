package io.github.bamdule.dawn.order.refund;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefundRequestRepository {

    private static final String COLUMNS = "id, order_no, refund_key, payment_round, amount, reason, status, attempt_count";
    // 접수된 환불은 결제 서비스가 대행사와 확정한다. 1시간 지나도 결과 이벤트가 없으면 그때 상태를 묻는다
    private static final int ACCEPTED_CHECK_MINUTES = 60;
    private static final int REQUESTED_OVERDUE_MINUTES = 10;
    private static final int ACCEPTED_OVERDUE_MINUTES = 120;

    private final JdbcClient jdbcClient;

    public RefundRequestRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // 환불 키가 유니크라 같은 사유로 두 번 불러도 한 건이다. 새로 넣었을 때만 참이다
    public boolean insert(String orderNo, String refundKey, int paymentRound, long amount, RefundReason reason) {
        return jdbcClient.sql("""
                INSERT IGNORE INTO refund_request
                    (order_no, refund_key, payment_round, amount, reason, status, next_attempt_at, created_at, updated_at)
                VALUES (:orderNo, :refundKey, :paymentRound, :amount, :reason, 'REQUESTED', NOW(3), NOW(3), NOW(3))
                """)
                .param("orderNo", orderNo)
                .param("refundKey", refundKey)
                .param("paymentRound", paymentRound)
                .param("amount", amount)
                .param("reason", reason.name())
                .update() == 1;
    }

    public boolean exists(String refundKey) {
        return jdbcClient.sql("SELECT COUNT(*) FROM refund_request WHERE refund_key = :refundKey")
                .param("refundKey", refundKey)
                .query(Integer.class)
                .single() > 0;
    }

    public Optional<RefundRequestRow> findByKey(String refundKey) {
        return jdbcClient.sql("SELECT " + COLUMNS + ", FALSE AS overdue FROM refund_request WHERE refund_key = :refundKey")
                .param("refundKey", refundKey)
                .query(RefundRequestRow.class)
                .optional();
    }

    public boolean transition(String refundKey, List<RefundStatus> from, RefundStatus to) {
        return jdbcClient.sql("""
                UPDATE refund_request SET status = :to, updated_at = NOW(3)
                 WHERE refund_key = :refundKey AND status IN (:from)
                """)
                .param("refundKey", refundKey)
                .param("from", from.stream().map(Enum::name).toList())
                .param("to", to.name())
                .update() == 1;
    }

    public boolean markAccepted(String refundKey) {
        return jdbcClient.sql("""
                UPDATE refund_request
                   SET status = 'ACCEPTED', accepted_at = NOW(3),
                       next_attempt_at = NOW(3) + INTERVAL :checkMinutes MINUTE, updated_at = NOW(3)
                 WHERE refund_key = :refundKey AND status = 'REQUESTED'
                """)
                .param("refundKey", refundKey)
                .param("checkMinutes", ACCEPTED_CHECK_MINUTES)
                .update() == 1;
    }

    public void postpone(String refundKey, long delayMillis) {
        jdbcClient.sql("""
                UPDATE refund_request
                   SET attempt_count = attempt_count + 1,
                       next_attempt_at = NOW(3) + INTERVAL :delayMillis * 1000 MICROSECOND, updated_at = NOW(3)
                 WHERE refund_key = :refundKey
                """)
                .param("refundKey", refundKey)
                .param("delayMillis", delayMillis)
                .update();
    }

    public List<RefundRequestRow> findDue(int limit) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                       , (status = 'REQUESTED' AND created_at < NOW(3) - INTERVAL :requestedOverdue MINUTE)
                         OR (status = 'ACCEPTED' AND accepted_at < NOW(3) - INTERVAL :acceptedOverdue MINUTE) AS overdue
                  FROM refund_request
                 WHERE status IN ('REQUESTED', 'ACCEPTED') AND next_attempt_at <= NOW(3)
                 ORDER BY next_attempt_at
                 LIMIT :limit
                """)
                .param("requestedOverdue", REQUESTED_OVERDUE_MINUTES)
                .param("acceptedOverdue", ACCEPTED_OVERDUE_MINUTES)
                .param("limit", limit)
                .query(RefundRequestRow.class)
                .list();
    }
}
