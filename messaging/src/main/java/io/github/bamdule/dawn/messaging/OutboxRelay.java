package io.github.bamdule.dawn.messaging;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// 선점, 전송, 기록을 서로 다른 단계로 나눈다. 락을 쥔 채 Kafka 를 기다리지 않기 위해서다 (docs/design/03)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long FIRST_BACKOFF_MILLIS = 1_000;

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties outboxProperties;

    public OutboxRelay(JdbcClient jdbcClient, TransactionTemplate transactionTemplate,
                       KafkaTemplate<String, String> kafkaTemplate, OutboxProperties outboxProperties) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.outboxProperties = outboxProperties;
    }

    public int relayOnce() {
        List<ClaimedEvent> claimedEvents = claim();
        if (claimedEvents.isEmpty()) {
            return 0;
        }
        List<SendResult> sendResults = send(claimedEvents);
        record(sendResults);
        return claimedEvents.size();
    }

    List<ClaimedEvent> claim() {
        return transactionTemplate.execute(status -> {
            // 같은 키에 앞선 PENDING 이 있으면 뒤의 것을 집지 않는다. 앞선 것이 재시도를 기다려도 순서를 지킨다
            List<ClaimedRow> rows = jdbcClient.sql("""
                    SELECT o.seq, o.topic, o.partition_key, o.payload, o.attempt_count
                      FROM outbox_event o
                     WHERE o.status = 'PENDING'
                       AND o.next_attempt_at <= NOW(3)
                       AND NOT EXISTS (SELECT 1 FROM outbox_event p
                                        WHERE p.partition_key = o.partition_key
                                          AND p.status = 'PENDING'
                                          AND p.seq < o.seq)
                     ORDER BY o.seq
                     LIMIT :limit
                     FOR UPDATE SKIP LOCKED
                    """)
                    .param("limit", outboxProperties.batchSize())
                    .query(ClaimedRow.class)
                    .list();
            if (rows.isEmpty()) {
                return List.of();
            }
            LocalDateTime claimedUntil = databaseNow().plus(outboxProperties.claimDuration());
            List<Long> seqs = rows.stream().map(ClaimedRow::seq).toList();
            jdbcClient.sql("UPDATE outbox_event SET next_attempt_at = :claimedUntil WHERE seq IN (:seqs)")
                    .param("claimedUntil", claimedUntil)
                    .param("seqs", seqs)
                    .update();
            return rows.stream()
                    .map(row -> new ClaimedEvent(row.seq(), row.topic(), row.partitionKey(), row.payload(),
                            row.attemptCount(), claimedUntil))
                    .toList();
        });
    }

    List<SendResult> send(List<ClaimedEvent> claimedEvents) {
        List<CompletableFuture<?>> futures = new ArrayList<>(claimedEvents.size());
        for (ClaimedEvent claimedEvent : claimedEvents) {
            futures.add(kafkaTemplate.send(claimedEvent.topic(), claimedEvent.partitionKey(), claimedEvent.payload()));
        }
        List<SendResult> sendResults = new ArrayList<>(claimedEvents.size());
        for (int index = 0; index < claimedEvents.size(); index++) {
            sendResults.add(new SendResult(claimedEvents.get(index), awaitQuietly(futures.get(index))));
        }
        return sendResults;
    }

    void record(List<SendResult> sendResults) {
        transactionTemplate.executeWithoutResult(status -> {
            for (SendResult sendResult : sendResults) {
                ClaimedEvent claimedEvent = sendResult.claimedEvent();
                if (sendResult.succeeded()) {
                    markSent(claimedEvent);
                } else {
                    scheduleRetry(claimedEvent);
                }
            }
        });
    }

    // 선점이 끝나 다른 릴레이가 다시 집은 행을 늦게 덮어쓰지 않도록 내 선점 값을 조건으로 건다
    private void markSent(ClaimedEvent claimedEvent) {
        jdbcClient.sql("""
                UPDATE outbox_event SET status = 'SENT', sent_at = NOW(3)
                 WHERE seq = :seq AND status = 'PENDING' AND next_attempt_at = :claimedUntil
                """)
                .param("seq", claimedEvent.seq())
                .param("claimedUntil", claimedEvent.claimedUntil())
                .update();
    }

    private void scheduleRetry(ClaimedEvent claimedEvent) {
        int nextAttemptCount = claimedEvent.attemptCount() + 1;
        LocalDateTime nextAttemptAt = databaseNow().plus(backoffMillis(nextAttemptCount), ChronoUnit.MILLIS);
        jdbcClient.sql("""
                UPDATE outbox_event SET attempt_count = :attemptCount, next_attempt_at = :nextAttemptAt
                 WHERE seq = :seq AND status = 'PENDING' AND next_attempt_at = :claimedUntil
                """)
                .param("attemptCount", nextAttemptCount)
                .param("nextAttemptAt", nextAttemptAt)
                .param("seq", claimedEvent.seq())
                .param("claimedUntil", claimedEvent.claimedUntil())
                .update();
        if (nextAttemptCount >= outboxProperties.alertAttemptCount()) {
            log.error("아웃박스 발행 반복 실패 (seq={}, attemptCount={})", claimedEvent.seq(), nextAttemptCount);
        }
    }

    // 1초부터 두 배씩, 최대 60초. 동시에 실패한 이벤트가 같은 시각에 다시 몰리지 않게 지터를 더한다
    long backoffMillis(int attemptCount) {
        long exponential = FIRST_BACKOFF_MILLIS << Math.min(attemptCount - 1, 16);
        long capped = Math.min(exponential, outboxProperties.maxBackoff().toMillis());
        return capped + ThreadLocalRandom.current().nextLong(capped / 4 + 1);
    }

    private boolean awaitQuietly(CompletableFuture<?> future) {
        try {
            future.get(outboxProperties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            log.warn("아웃박스 이벤트 전송 실패 ({})", exception.getMessage());
            return false;
        }
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("SELECT NOW(3)").query(LocalDateTime.class).single();
    }

    record SendResult(ClaimedEvent claimedEvent, boolean succeeded) {
    }

    private record ClaimedRow(long seq, String topic, String partitionKey, String payload, int attemptCount) {
    }
}
