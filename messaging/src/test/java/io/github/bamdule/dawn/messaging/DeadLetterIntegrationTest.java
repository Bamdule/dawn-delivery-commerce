package io.github.bamdule.dawn.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest
@Import({MySqlContainers.class, KafkaContainers.class, DeadLetterIntegrationTest.BrokenListenerConfig.class})
class DeadLetterIntegrationTest {

    static final String TOPIC = "dead-letter-test";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void 재시도해도_처리하지_못한_메시지는_처리_실패_토픽에_같은_키로_남는다() throws Exception {
        kafkaTemplate.send(TOPIC, "order-9", "{\"broken\":true}").get();

        List<ConsumerRecord<String, String>> deadLetters = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("verify-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(TOPIC + "-dlt"));
            long deadline = System.currentTimeMillis() + 40_000;
            while (deadLetters.isEmpty() && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(deadLetters::add);
            }
        }

        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.getFirst().topic()).isEqualTo(TOPIC + "-dlt");
        assertThat(deadLetters.getFirst().key()).isEqualTo("order-9");
        assertThat(deadLetters.getFirst().value()).isEqualTo("{\"broken\":true}");
        assertThat(meterRegistry.counter(ConsumerErrorHandling.DEAD_LETTER_METRIC, "topic", TOPIC).count())
                .as("처리 실패 토픽에 보낸 만큼 토픽별 카운터가 오른다")
                .isEqualTo(1.0);
    }

    @Autowired
    private DeadLetterReplayer deadLetterReplayer;

    @Test
    void 처리_실패_토픽의_메시지를_같은_키로_원래_토픽에_다시_넣는다() throws Exception {
        String original = "replay-" + UUID.randomUUID();
        kafkaTemplate.send(original + "-dlt", "order-7", "{\"fixed\":true}").get();
        kafkaTemplate.send(original + "-dlt", "order-8", "{\"fixed\":true}").get();
        // 서비스 설정처럼 읽기 시작 위치를 정하지 않은 소비자 팩토리로 만든다. Kafka 기본값은 최신 위치다
        Map<String, Object> serviceDefaults = new HashMap<>(consumerFactory.getConfigurationProperties());
        serviceDefaults.remove(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
        DeadLetterReplayer replayer = new DeadLetterReplayer(
                new DefaultKafkaConsumerFactory<>(serviceDefaults, new StringDeserializer(), new StringDeserializer()), kafkaTemplate);
        assertThat(replayer.backlog(original)).isEqualTo(2);

        assertThat(replayer.replay(original, 1)).as("한 번에 다시 넣는 수를 넘지 않는다").isEqualTo(1);
        assertThat(replayer.backlog(original)).as("다시 넣을 때까지 쌓인 수가 줄지 않는다").isEqualTo(1);
        int replayed = replayer.replay(original, 100);

        assertThat(replayed).isEqualTo(1);
        assertThat(replayer.backlog(original)).isZero();
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("verify-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(original));
            long deadline = System.currentTimeMillis() + 20_000;
            while (records.size() < 2 && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        assertThat(records).extracting(ConsumerRecord::key).containsExactlyInAnyOrder("order-7", "order-8");
        assertThat(deadLetterReplayer.replay(original, 100)).as("이미 다시 넣은 메시지는 또 넣지 않는다").isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BrokenListenerConfig {

        // 처리 실패 토픽 적재 카운터를 보려고 지표 저장소를 둔다. 서비스에서는 actuator 가 만든다
        @Bean
        SimpleMeterRegistry simpleMeterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        BrokenListener brokenListener() {
            return new BrokenListener();
        }
    }

    static class BrokenListener {

        @KafkaListener(topics = TOPIC, groupId = "broken-listener")
        void listen(String payload) {
            throw new IllegalStateException("처리할 수 없는 메시지");
        }
    }
}
