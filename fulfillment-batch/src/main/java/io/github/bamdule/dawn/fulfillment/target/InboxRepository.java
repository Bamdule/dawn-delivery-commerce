package io.github.bamdule.dawn.fulfillment.target;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InboxRepository {

    private final JdbcClient jdbcClient;

    public InboxRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // 처리와 같은 트랜잭션에서 불러야 기록만 되고 처리가 빠지는 일이 없다
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordIfAbsent(String consumer, String eventId) {
        return jdbcClient.sql("INSERT IGNORE INTO inbox_event (consumer, event_id, processed_at) VALUES (:consumer, :eventId, NOW(3))")
                .param("consumer", consumer)
                .param("eventId", eventId)
                .update() == 1;
    }
}
