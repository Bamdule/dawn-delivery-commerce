package io.github.bamdule.dawn.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({MySqlContainers.class, KafkaContainers.class})
class OutboxRelayIntegrationTest {

    private static final int KEY_COUNT = 100;
    private static final int EVENTS_PER_KEY = 10;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OutboxProperties outboxProperties;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Test
    void 릴레이_두_대가_동시에_돌아도_이벤트마다_한_번씩_같은_키의_순서대로_발행된다() throws Exception {
        String topic = uniqueTopic();
        transactionTemplate.executeWithoutResult(status -> {
            for (int sequence = 0; sequence < EVENTS_PER_KEY; sequence++) {
                for (int key = 0; key < KEY_COUNT; key++) {
                    write(topic, "key-" + key, "{\"key\":" + key + ",\"sequence\":" + sequence + "}");
                }
            }
        });

        runRelaysConcurrently(2, topic);

        List<ConsumerRecord<String, String>> records = consumeAll(topic, KEY_COUNT * EVENTS_PER_KEY);
        assertThat(records).hasSize(KEY_COUNT * EVENTS_PER_KEY);
        Map<String, List<Integer>> sequencesByKey = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            sequencesByKey.computeIfAbsent(record.key(), key -> new ArrayList<>()).add(sequenceOf(record.value()));
        }
        assertThat(sequencesByKey).hasSize(KEY_COUNT);
        sequencesByKey.values().forEach(sequences ->
                assertThat(sequences).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        assertThat(pendingCount(topic)).isZero();
    }

    @Test
    void 앞선_이벤트가_재시도를_기다리면_같은_키의_뒤_이벤트를_보내지_않는다() {
        String topic = uniqueTopic();
        transactionTemplate.executeWithoutResult(status -> {
            write(topic, "order-1", "{\"sequence\":0}");
            write(topic, "order-1", "{\"sequence\":1}");
        });
        jdbcClient.sql("""
                UPDATE outbox_event SET next_attempt_at = NOW(3) + INTERVAL 1 HOUR
                 WHERE topic = :topic AND payload->>'$.sequence' = '0'
                """).param("topic", topic).update();

        relayUntilQuiet(topic);

        assertThat(pendingCount(topic)).isEqualTo(2);
    }

    @Test
    void 전송_뒤_기록_전에_죽으면_선점이_끝난_뒤_같은_이벤트가_다시_나간다() throws Exception {
        String topic = uniqueTopic();
        transactionTemplate.executeWithoutResult(status -> write(topic, "order-2", "{\"sequence\":0}"));
        OutboxRelay shortClaimRelay = new OutboxRelay(jdbcClient, transactionTemplate, kafkaTemplate,
                new OutboxProperties(false, null, null, Duration.ofSeconds(1), null, null, null));

        List<ClaimedEvent> claimed = shortClaimRelay.claim();
        shortClaimRelay.send(claimed);
        // 기록 단계 없이 끝난다. 릴레이가 여기서 죽은 것과 같다
        assertThat(pendingCount(topic)).isEqualTo(1);
        assertThat(outboxRelay.relayOnce()).as("선점 중인 행은 다른 릴레이가 집지 않는다").isZero();

        Thread.sleep(1_200);
        relayUntilQuiet(topic);

        assertThat(consumeAll(topic, 2)).hasSize(2);
        assertThat(pendingCount(topic)).isZero();
    }

    @Test
    void 바깥_트랜잭션_없이_아웃박스에_쓰면_실패한다() {
        assertThatThrownBy(() -> write(uniqueTopic(), "order-3", "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 재시도_간격은_1초부터_두_배씩_늘고_60초를_넘지_않는다() {
        assertThat(outboxRelay.backoffMillis(1)).isBetween(1_000L, 1_250L);
        assertThat(outboxRelay.backoffMillis(2)).isBetween(2_000L, 2_500L);
        assertThat(outboxRelay.backoffMillis(20)).isBetween(60_000L, 75_000L);
        assertThat(outboxProperties.batchSize()).isEqualTo(500);
    }

    private void write(String topic, String key, String payload) {
        outboxWriter.write(new OutboxMessage(UUID.randomUUID(), topic, key, payload));
    }

    private void runRelaysConcurrently(int relayCount, String topic) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(relayCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < relayCount; index++) {
            futures.add(executorService.submit(() -> {
                start.await();
                relayUntilQuiet(topic);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        executorService.shutdown();
    }

    private void relayUntilQuiet(String topic) {
        long deadline = System.currentTimeMillis() + 30_000;
        int quietRounds = 0;
        while (System.currentTimeMillis() < deadline && quietRounds < 3) {
            quietRounds = outboxRelay.relayOnce() == 0 ? quietRounds + 1 : 0;
        }
    }

    private List<ConsumerRecord<String, String>> consumeAll(String topic, int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("verify-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (records.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            // 기대보다 더 들어오는지 잠깐 더 본다
            consumer.poll(Duration.ofSeconds(1)).forEach(records::add);
        }
        return records;
    }

    private long pendingCount(String topic) {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox_event WHERE topic = :topic AND status = 'PENDING'")
                .param("topic", topic).query(Long.class).single();
    }

    private int sequenceOf(String payload) {
        return Integer.parseInt(payload.replaceAll(".*\"sequence\":\\s*(\\d+).*", "$1"));
    }

    private String uniqueTopic() {
        return "test-" + UUID.randomUUID();
    }
}
