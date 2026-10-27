package io.github.bamdule.dawn.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest
@Import({MySqlContainers.class, KafkaContainers.class, DeadLetterIntegrationTest.BrokenListenerConfig.class})
class DeadLetterIntegrationTest {

    static final String TOPIC = "dead-letter-test";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

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
    }

    @Autowired
    private DeadLetterReplayer deadLetterReplayer;

    @Test
    void 처리_실패_토픽의_메시지를_같은_키로_원래_토픽에_다시_넣는다() throws Exception {
        String original = "replay-" + UUID.randomUUID();
        kafkaTemplate.send(original + "-dlt", "order-7", "{\"fixed\":true}").get();

        int replayed = deadLetterReplayer.replay(original, 100);

        assertThat(replayed).isEqualTo(1);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("verify-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(original));
            long deadline = System.currentTimeMillis() + 20_000;
            while (records.isEmpty() && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        assertThat(records).extracting(ConsumerRecord::key).containsExactly("order-7");
        assertThat(deadLetterReplayer.replay(original, 100)).as("이미 다시 넣은 메시지는 또 넣지 않는다").isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BrokenListenerConfig {

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
