package io.github.bamdule.dawn.messaging;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

// 사람이 영구히 보낼 수 없는 이벤트를 다시 보내거나 격리한다. SENT 의 보존 규칙도 여기 둔다 (docs/design/03)
public class OutboxMaintenance {

    private final JdbcClient jdbcClient;

    public OutboxMaintenance(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // 장애 조사와 재발행 근거라 SENT 를 7일 둔다 (docs/design/03 "보존과 정리")
    public static final RetentionRule SENT_RETENTION = new RetentionRule("outbox-sent", "outbox_event",
            "status = 'SENT' AND sent_at < :before", Duration.ofDays(7));

    // 여러 번 실패해 사람이 봐야 하는 PENDING 이벤트. 오래된 것부터 본다
    public List<StuckEvent> findStuck(int minAttempts, int limit) {
        return jdbcClient.sql("""
                SELECT seq, event_id, topic, partition_key, attempt_count, created_at, next_attempt_at FROM outbox_event
                 WHERE status = 'PENDING' AND attempt_count >= :minAttempts ORDER BY seq LIMIT :limit
                """)
                .param("minAttempts", minAttempts)
                .param("limit", limit)
                .query(StuckEvent.class)
                .list();
    }

    public List<StuckEvent> findQuarantined(int limit) {
        return jdbcClient.sql("""
                SELECT seq, event_id, topic, partition_key, attempt_count, created_at, next_attempt_at FROM outbox_event
                 WHERE status = 'QUARANTINED' ORDER BY seq LIMIT :limit
                """)
                .param("limit", limit)
                .query(StuckEvent.class)
                .list();
    }

    // 원인(브로커 설정, 토픽 권한 등)을 고친 뒤 백오프를 기다리지 않고 다음 바퀴에 보내게 한다.
    // 다른 릴레이가 전송 중인 행이면 그 릴레이의 기록이 선점 값 조건으로 0행이 되어, 최악이면 한 번 더 나간다. 받는 쪽이 막는다
    public boolean retryNow(long seq) {
        return jdbcClient.sql("UPDATE outbox_event SET next_attempt_at = NOW(3) WHERE seq = :seq AND status = 'PENDING'")
                .param("seq", seq)
                .update() == 1;
    }

    // 격리하면 같은 키의 뒤 이벤트가 풀린다. 순서가 바뀌어도 되는지 사람이 판단한 뒤에만 부른다
    public boolean quarantine(long seq) {
        return jdbcClient.sql("UPDATE outbox_event SET status = 'QUARANTINED' WHERE seq = :seq AND status = 'PENDING'")
                .param("seq", seq)
                .update() == 1;
    }

    public record StuckEvent(long seq, String eventId, String topic, String partitionKey, int attemptCount,
                             LocalDateTime createdAt, LocalDateTime nextAttemptAt) {
    }
}
