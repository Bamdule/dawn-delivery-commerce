package io.github.bamdule.dawn.messaging;

import io.github.bamdule.dawn.messaging.test.ExperimentReport;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;

// 실험 2. 설계는 "200ms 마다 500건"으로 상한을 초당 2,500건으로 셌다 (docs/experiments/02-릴레이-처리량.md).
// 스케줄러는 꽉 찬 바퀴면 기다리지 않고 바로 다음 바퀴를 돌아서, 실제 상한은 한 바퀴의 시간이 정한다
@Tag("experiment")
@SpringBootTest
@Import({MySqlContainers.class, KafkaContainers.class})
class OutboxRelayThroughputExperiment {

    private static final int PARTITIONS = 12;
    private static final int EVENTS = 50_000;
    private static final int EVENTS_PER_KEY = 2;
    private static final long POLL_INTERVAL_MILLIS = 200;
    private static final AtomicLong NEXT_KEY = new AtomicLong();
    private static final String PAYLOAD = "{\"type\":\"PAID\",\"orderNo\":\"20261028-K3F9A2Q7XZ\",\"memberId\":1,\"totalAmount\":45000,"
            + "\"lines\":[{\"productId\":1001,\"quantity\":2},{\"productId\":1002,\"quantity\":1}],"
            + "\"delivery\":{\"recipientName\":\"김새벽\",\"address\":\"서울시 강남구 테헤란로 1\",\"regionCode\":\"SEOUL\"}}";

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OutboxProperties outboxProperties;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Test
    void 릴레이의_한_바퀴_시간과_밀린_양에_따른_처리량을_잰다() throws Exception {
        ExperimentReport report = new ExperimentReport("02-relay-throughput");
        String topic = "experiment.relay." + UUID.randomUUID();
        kafkaAdmin.createOrModifyTopics(new NewTopic(topic, PARTITIONS, (short) 1));
        clearPending();
        insertPending(topic, 2_000);
        drain(1);

        report.line("## 한 바퀴의 단계별 시간 (바퀴당 " + outboxProperties.batchSize() + "건, 밀린 이벤트 수별 평균 10바퀴)");
        report.line("| 밀린 이벤트 | 선점 ms | 전송 ms | 기록 ms | 한 바퀴 ms | 바퀴만 돌 때 상한 건/초 |");
        report.line("|---|---|---|---|---|---|");
        for (int backlog : List.of(10_000, 50_000, 200_000)) {
            clearPending();
            insertPending(topic, backlog);
            report.line(phaseRow(backlog));
        }

        report.line("");
        report.line("## 실제 스케줄러로 " + String.format("%,d", EVENTS) + "건을 비우는 시간 (주기 " + POLL_INTERVAL_MILLIS + "ms, 꽉 찬 바퀴는 바로 다음 바퀴)");
        report.line("| 릴레이 수 | 걸린 초 | 처리량 건/초 | 설계 상한 건/초 |");
        report.line("|---|---|---|---|");
        for (int relays : List.of(1, 2)) {
            clearPending();
            insertPending(topic, EVENTS);
            long startedAt = System.nanoTime();
            drain(relays);
            double seconds = (System.nanoTime() - startedAt) / 1e9;
            report.line("| %d | %.1f | %,.0f | 2,500 |".formatted(relays, seconds, EVENTS / seconds));
        }
    }

    private String phaseRow(int backlog) {
        int rounds = 10;
        long claimNanos = 0;
        long sendNanos = 0;
        long recordNanos = 0;
        for (int round = 0; round < rounds; round++) {
            long t0 = System.nanoTime();
            List<ClaimedEvent> claimed = outboxRelay.claim();
            long t1 = System.nanoTime();
            List<OutboxRelay.SendResult> sent = outboxRelay.send(claimed);
            long t2 = System.nanoTime();
            outboxRelay.record(sent);
            long t3 = System.nanoTime();
            claimNanos += t1 - t0;
            sendNanos += t2 - t1;
            recordNanos += t3 - t2;
        }
        double claim = claimNanos / 1e6 / rounds;
        double send = sendNanos / 1e6 / rounds;
        double record = recordNanos / 1e6 / rounds;
        double round = claim + send + record;
        return "| %,d | %.1f | %.1f | %.1f | %.1f | %,.0f |".formatted(backlog, claim, send, record, round,
                outboxProperties.batchSize() / (round / 1000));
    }

    // 운영 스케줄러와 같다. 꽉 찬 바퀴면 바로 다시 돌고, 아니면 주기만큼 쉰다
    private void drain(int relays) throws Exception {
        List<CompletableFuture<Void>> workers = new ArrayList<>();
        for (int relay = 0; relay < relays; relay++) {
            workers.add(CompletableFuture.runAsync(() -> {
                while (pendingCount() > 0) {
                    int relayed;
                    do {
                        relayed = outboxRelay.relayOnce();
                    } while (relayed == outboxProperties.batchSize());
                    sleep(POLL_INTERVAL_MILLIS);
                }
            }));
        }
        CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new)).get();
    }

    private void insertPending(String topic, int count) {
        List<Object[]> rows = new ArrayList<>(count);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now().minusSeconds(1));
        for (int index = 0; index < count; index++) {
            long key = index % EVENTS_PER_KEY == 0 ? NEXT_KEY.incrementAndGet() : NEXT_KEY.get();
            rows.add(new Object[] {UUID.randomUUID().toString(), topic, "order-" + key, PAYLOAD, now, now});
        }
        for (int from = 0; from < rows.size(); from += 5_000) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO outbox_event (event_id, topic, partition_key, payload, status, attempt_count, next_attempt_at, created_at)
                    VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)
                    """, rows.subList(from, Math.min(from + 5_000, rows.size())));
        }
    }

    private void clearPending() {
        jdbcClient.sql("DELETE FROM outbox_event").update();
    }

    private int pendingCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'").query(Integer.class).single();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interruptedException);
        }
    }
}
