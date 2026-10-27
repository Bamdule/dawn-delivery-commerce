package io.github.bamdule.dawn.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class OutboxWriter {

    private final JdbcClient jdbcClient;

    public OutboxWriter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // 상태 변경과 같은 트랜잭션에 묶여야 의미가 있어서, 바깥 트랜잭션 없이 부르면 실패시킨다.
    // 같은 이벤트 아이디가 이미 있으면 유니크 위반이 바깥 트랜잭션 전체를 되돌린다
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(OutboxMessage outboxMessage) {
        jdbcClient.sql("""
                INSERT INTO outbox_event
                    (event_id, topic, partition_key, payload, status, attempt_count, next_attempt_at, created_at)
                VALUES
                    (:eventId, :topic, :partitionKey, :payload, 'PENDING', 0, NOW(3), NOW(3))
                """)
                .param("eventId", outboxMessage.eventId().toString())
                .param("topic", outboxMessage.topic())
                .param("partitionKey", outboxMessage.partitionKey())
                .param("payload", outboxMessage.payload())
                .update();
    }
}
