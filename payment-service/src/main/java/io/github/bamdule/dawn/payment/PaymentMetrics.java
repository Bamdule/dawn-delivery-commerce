package io.github.bamdule.dawn.payment;

import io.github.bamdule.dawn.messaging.DbGauges;
import io.github.bamdule.dawn.payment.guard.PgCallGuard;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// 결제 서비스가 주인인 미결 상태와, 대행사를 부르지 않고 거절한 호출을 센다 (docs/design/01 "운영 지표")
@Component
public class PaymentMetrics {

    public PaymentMetrics(DbGauges dbGauges, JdbcClient jdbcClient, PgCallGuard pgCallGuard, MeterRegistry meterRegistry) {
        dbGauges.register("dawn.payment.requested.overdue", "10분 넘게 REQUESTED 인 결제. 대행사 장애나 조회 배치 정지", "payments",
                () -> count(jdbcClient, "SELECT COUNT(*) FROM payment WHERE status = 'REQUESTED' AND requested_at < NOW(3) - INTERVAL 10 MINUTE"));
        dbGauges.register("dawn.payment.unknown", "사람이 대행사에 확인해야 하는 UNKNOWN 결제", "payments",
                () -> count(jdbcClient, "SELECT COUNT(*) FROM payment WHERE status = 'UNKNOWN'"));
        dbGauges.register("dawn.payment.refund.attention", "사람이 대행사에 확인해야 하는 UNKNOWN 이나 FAILED 환불", "refunds",
                () -> count(jdbcClient, "SELECT COUNT(*) FROM refund WHERE status IN ('UNKNOWN', 'FAILED')"));
        dbGauges.register("dawn.payment.refund.requested.overdue", "1시간 넘게 REQUESTED 인 환불. 대행사 환불 지연", "refunds",
                () -> count(jdbcClient, "SELECT COUNT(*) FROM refund WHERE status = 'REQUESTED' AND requested_at < NOW(3) - INTERVAL 1 HOUR"));
        dbGauges.register("dawn.payment.settlement.mismatch", "최근 하루 정산 대사에서 찾은 어긋남", "mismatches",
                () -> count(jdbcClient, "SELECT COUNT(*) FROM settlement_mismatch WHERE created_at > NOW(3) - INTERVAL 1 DAY"));
        FunctionCounter.builder("dawn.payment.pg.rejected", pgCallGuard, PgCallGuard::rejectedByLimit)
                .description("동시 호출 한도를 넘어 대행사를 부르지 않고 거절한 호출")
                .tag("reason", "limit")
                .register(meterRegistry);
        FunctionCounter.builder("dawn.payment.pg.rejected", pgCallGuard, PgCallGuard::rejectedByCircuit)
                .description("회로가 열려 대행사를 부르지 않고 거절한 호출")
                .tag("reason", "circuit")
                .register(meterRegistry);
    }

    private static long count(JdbcClient jdbcClient, String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }
}
