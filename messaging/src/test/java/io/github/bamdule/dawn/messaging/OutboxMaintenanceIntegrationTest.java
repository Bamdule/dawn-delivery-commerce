package io.github.bamdule.dawn.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({MySqlContainers.class, KafkaContainers.class})
class OutboxMaintenanceIntegrationTest {

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OutboxMaintenance outboxMaintenance;

    @Autowired
    private RetentionCleaner retentionCleaner;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 보존_기간이_지난_SENT_만_1000행씩_나눠_지운다() {
        String topic = "cleanup-" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            for (int index = 0; index < 2_500; index++) {
                outboxWriter.write(new OutboxMessage(UUID.randomUUID(), topic, "key-" + index, "{}"));
            }
        });
        jdbcClient.sql("UPDATE outbox_event SET status = 'SENT', sent_at = NOW(3) - INTERVAL 8 DAY WHERE topic = :topic")
                .param("topic", topic).update();
        transactionTemplate.executeWithoutResult(status ->
                outboxWriter.write(new OutboxMessage(UUID.randomUUID(), topic, "fresh", "{}")));

        int deleted = retentionCleaner.clean(OutboxMaintenance.SENT_RETENTION);

        assertThat(deleted).isGreaterThanOrEqualTo(2_500);
        assertThat(countByTopic(topic)).isEqualTo(1);
    }

    @Test
    void 격리하면_같은_키의_뒤_이벤트가_풀린다() {
        String topic = "quarantine-" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            outboxWriter.write(new OutboxMessage(UUID.randomUUID(), topic, "order-1", "{\"sequence\":0}"));
            outboxWriter.write(new OutboxMessage(UUID.randomUUID(), topic, "order-1", "{\"sequence\":1}"));
        });
        jdbcClient.sql("""
                UPDATE outbox_event SET next_attempt_at = NOW(3) + INTERVAL 1 HOUR, attempt_count = 10
                 WHERE topic = :topic AND payload->>'$.sequence' = '0'
                """).param("topic", topic).update();
        long stuckSeq = jdbcClient.sql("SELECT seq FROM outbox_event WHERE topic = :topic AND payload->>'$.sequence' = '0'")
                .param("topic", topic).query(Long.class).single();

        assertThat(outboxMaintenance.quarantine(stuckSeq)).isTrue();
        assertThat(outboxMaintenance.quarantine(stuckSeq)).as("두 번째 격리는 0행").isFalse();
        while (outboxRelay.relayOnce() > 0) {
            // 남은 이벤트를 모두 보낸다
        }

        assertThat(statusOf(topic, "1")).isEqualTo("SENT");
        assertThat(statusOf(topic, "0")).isEqualTo("QUARANTINED");
    }

    private long countByTopic(String topic) {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox_event WHERE topic = :topic")
                .param("topic", topic).query(Long.class).single();
    }

    private String statusOf(String topic, String sequence) {
        return jdbcClient.sql("SELECT status FROM outbox_event WHERE topic = :topic AND payload->>'$.sequence' = :sequence")
                .param("topic", topic).param("sequence", sequence).query(String.class).single();
    }
}
