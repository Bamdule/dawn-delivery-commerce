package io.github.bamdule.dawn.order;

import io.github.bamdule.dawn.messaging.DbGauges;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// 주문 서비스가 주인인 멈춘 상태를 센다. 하나라도 있으면 경보다 (docs/design/01 "수렴 책임", "운영 지표")
@Component
public class OrderMetrics {

    public OrderMetrics(DbGauges dbGauges, JdbcClient jdbcClient) {
        dbGauges.register("dawn.order.pending.overdue", "15분 넘게 PENDING 인 주문. 결제 결과가 주문에 반영되지 않음", "orders",
                () -> count(jdbcClient, "SELECT COUNT(*) FROM orders WHERE status = 'PENDING' AND created_at < NOW(3) - INTERVAL 15 MINUTE"));
        dbGauges.register("dawn.order.attempt.overdue", "15분 넘게 IN_PROGRESS 인 결제 시도. 정리 배치 정지나 대행사 장애", "attempts",
                () -> count(jdbcClient, """
                        SELECT COUNT(*) FROM orders WHERE payment_attempt = 'IN_PROGRESS' AND attempt_started_at < NOW(3) - INTERVAL 15 MINUTE
                        """));
        dbGauges.register("dawn.order.refund.unaccepted", "결제 서비스가 10분 넘게 접수하지 않은 환불 요청", "refunds",
                () -> count(jdbcClient, "SELECT COUNT(*) FROM refund_request WHERE status = 'REQUESTED' AND created_at < NOW(3) - INTERVAL 10 MINUTE"));
        dbGauges.register("dawn.order.refund.accepted.overdue", "접수된 뒤 2시간 넘게 결과가 없는 환불 요청", "refunds",
                () -> count(jdbcClient, "SELECT COUNT(*) FROM refund_request WHERE status = 'ACCEPTED' AND accepted_at < NOW(3) - INTERVAL 2 HOUR"));
        dbGauges.register("dawn.order.refund.attention", "사람이 확정해야 하는 환불 요청 (접수 거절, 실패)", "refunds",
                () -> count(jdbcClient, "SELECT COUNT(*) FROM refund_request WHERE status IN ('REJECTED', 'FAILED')"));
    }

    private static long count(JdbcClient jdbcClient, String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }
}
