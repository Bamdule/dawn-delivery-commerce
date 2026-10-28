package io.github.bamdule.dawn.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;

// 아웃박스를 쓰는 서비스마다 같은 두 지표를 낸다.
// 가장 오래된 PENDING 의 나이(30초 넘으면 경보)와, 10번 넘게 실패해 사람이 봐야 하는 이벤트 수(1건이면 경보)다
public final class OutboxMetrics {

    private OutboxMetrics() {
    }

    public static void register(DbGauges dbGauges, JdbcClient jdbcClient, OutboxProperties outboxProperties) {
        dbGauges.register("dawn.outbox.pending.oldest.age", "가장 오래된 PENDING 아웃박스 이벤트의 나이", "seconds",
                () -> jdbcClient.sql("""
                        SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), NOW(3)), 0) FROM outbox_event WHERE status = 'PENDING'
                        """).query(Long.class).single());
        dbGauges.register("dawn.outbox.pending.stuck", "여러 번 실패해 사람이 봐야 하는 PENDING 아웃박스 이벤트 수", "events",
                () -> jdbcClient.sql("SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING' AND attempt_count > :limit")
                        .param("limit", outboxProperties.alertAttemptCount())
                        .query(Long.class).single());
    }
}
