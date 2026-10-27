package io.github.bamdule.dawn.payment.round;

import io.github.bamdule.dawn.payment.PaymentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private static final String COLUMNS = "id, order_no, payment_round, idempotency_key, amount, status, failure_code, attempt_count";

    private final JdbcClient jdbcClient;

    public PaymentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // 주문마다 한 행을 먼저 잠가, 회차 열기와 봉인과 결과 기록과 이벤트 기록을 주문 안에서 한 줄로 세운다 (docs/design/03)
    public void lockOrder(String orderNo) {
        jdbcClient.sql("INSERT IGNORE INTO payment_order (order_no, updated_at) VALUES (:orderNo, NOW(3))")
                .param("orderNo", orderNo).update();
        jdbcClient.sql("SELECT order_no FROM payment_order WHERE order_no = :orderNo FOR UPDATE")
                .param("orderNo", orderNo).query(String.class).single();
    }

    public List<PaymentRow> findByOrderNo(String orderNo) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM payment WHERE order_no = :orderNo ORDER BY payment_round")
                .param("orderNo", orderNo).query(PaymentRow.class).list();
    }

    public Optional<PaymentRow> findRound(String orderNo, int paymentRound) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM payment WHERE order_no = :orderNo AND payment_round = :round")
                .param("orderNo", orderNo).param("round", paymentRound).query(PaymentRow.class).optional();
    }

    public Optional<PaymentRow> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM payment WHERE idempotency_key = :key")
                .param("key", idempotencyKey).query(PaymentRow.class).optional();
    }

    public long insert(String orderNo, int paymentRound, String idempotencyKey, long amount, PaymentStatus status) {
        jdbcClient.sql("""
                INSERT INTO payment (order_no, payment_round, idempotency_key, amount, status, attempt_count,
                                     last_sent_at, requested_at, updated_at)
                VALUES (:orderNo, :round, :key, :amount, :status, :attemptCount, NOW(3), NOW(3), NOW(3))
                """)
                .param("orderNo", orderNo)
                .param("round", paymentRound)
                .param("key", idempotencyKey)
                .param("amount", amount)
                .param("status", status.name())
                .param("attemptCount", status == PaymentStatus.REQUESTED ? 1 : 0)
                .update();
        return findRound(orderNo, paymentRound).orElseThrow().id();
    }

    // 결과 기록은 이전 상태를 조건으로 건다. 동기 응답과 조회 배치가 같은 회차를 확정하려 해도 한 번만 바뀐다
    public boolean transition(long paymentId, PaymentStatus from, PaymentStatus to, String failureCode) {
        return jdbcClient.sql("""
                UPDATE payment
                   SET status = :to, failure_code = :failureCode,
                       approved_at = CASE WHEN :to = 'APPROVED' THEN NOW(3) ELSE approved_at END,
                       updated_at = NOW(3)
                 WHERE id = :id AND status = :from
                """)
                .param("to", to.name())
                .param("failureCode", failureCode)
                .param("id", paymentId)
                .param("from", from.name())
                .update() == 1;
    }

    public void recordUnknownAttempt(long paymentId, String failureCode, long backoffSeconds) {
        jdbcClient.sql("""
                UPDATE payment SET failure_code = :failureCode, next_attempt_at = NOW(3) + INTERVAL :backoff SECOND, updated_at = NOW(3)
                 WHERE id = :id AND status = 'REQUESTED'
                """)
                .param("failureCode", failureCode).param("backoff", backoffSeconds).param("id", paymentId).update();
    }

    public void recordResend(long paymentId, long backoffSeconds) {
        jdbcClient.sql("""
                UPDATE payment
                   SET attempt_count = attempt_count + 1, last_sent_at = NOW(3),
                       next_attempt_at = NOW(3) + INTERVAL :backoff SECOND, updated_at = NOW(3)
                 WHERE id = :id AND status = 'REQUESTED'
                """)
                .param("backoff", backoffSeconds).param("id", paymentId).update();
    }

    // 시간 비교는 DB 시계로 한다. 서버마다 시계가 달라도 판단이 같다
    public List<RequestedPayment> findRequestedDue(int limit) {
        return jdbcClient.sql("""
                SELECT id, order_no, payment_round, idempotency_key, amount, failure_code, attempt_count,
                       last_sent_at <= NOW(3) - INTERVAL 60 SECOND AS not_found_trustworthy,
                       requested_at <= NOW(3) - INTERVAL 10 MINUTE AS overdue
                  FROM payment
                 WHERE status = 'REQUESTED'
                   AND requested_at <= NOW(3) - INTERVAL 10 SECOND
                   AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3))
                 ORDER BY requested_at
                 LIMIT :limit
                """)
                .param("limit", limit)
                .query(RequestedPayment.class)
                .list();
    }

    public record RequestedPayment(long id, String orderNo, int paymentRound, String idempotencyKey, long amount,
                                   String failureCode, int attemptCount, boolean notFoundTrustworthy, boolean overdue) {
    }
}
