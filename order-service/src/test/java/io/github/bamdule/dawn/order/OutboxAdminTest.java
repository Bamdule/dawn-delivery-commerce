package io.github.bamdule.dawn.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// 관리 API 는 메시징 모듈에 있고, 이벤트를 내는 서비스마다 같은 주소로 켜진다
@AutoConfigureMockMvc
class OutboxAdminTest extends OrderIntegrationTest {

    @Autowired
    private MockMvcTester mockMvcTester;

    @Test
    void 열_번_넘게_실패한_이벤트가_목록에_보이고_바로_다시_보내게_할_수_있다() {
        long seq = insertPending(11, LocalDateTime.now().plusMinutes(1));

        assertThat(mockMvcTester.get().uri("/admin/outbox/stuck"))
                .hasStatusOk().bodyJson().extractingPath("$[?(@.seq == " + seq + ")].attemptCount").asArray().containsExactly(11);
        assertThat(mockMvcTester.post().uri("/admin/outbox/{seq}/retry", seq)).hasStatus(204);

        assertThat(jdbcClient.sql("SELECT next_attempt_at <= NOW(3) FROM outbox_event WHERE seq = :seq")
                .param("seq", seq).query(Boolean.class).single()).isTrue();
    }

    @Test
    void 격리하면_QUARANTINED_목록으로_옮기고_다시_격리하거나_다시_보낼_수_없다() {
        long seq = insertPending(12, LocalDateTime.now().plusMinutes(1));

        assertThat(mockMvcTester.post().uri("/admin/outbox/{seq}/quarantine", seq)).hasStatus(204);

        assertThat(mockMvcTester.get().uri("/admin/outbox/quarantined"))
                .hasStatusOk().bodyJson().extractingPath("$[?(@.seq == " + seq + ")].seq").asArray().hasSize(1);
        assertThat(mockMvcTester.post().uri("/admin/outbox/{seq}/quarantine", seq)).hasStatus(409);
        assertThat(mockMvcTester.post().uri("/admin/outbox/{seq}/retry", seq)).hasStatus(409);
    }

    private long insertPending(int attemptCount, LocalDateTime nextAttemptAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                INSERT INTO outbox_event (event_id, topic, partition_key, payload, status, attempt_count, next_attempt_at, created_at)
                VALUES (:eventId, 'order.events', :key, '{}', 'PENDING', :attemptCount, :nextAttemptAt, NOW(3))
                """)
                .param("eventId", UUID.randomUUID().toString())
                .param("key", "admin-test-" + UUID.randomUUID())
                .param("attemptCount", attemptCount)
                .param("nextAttemptAt", nextAttemptAt)
                .update(keyHolder, "seq");
        return keyHolder.getKey().longValue();
    }
}
