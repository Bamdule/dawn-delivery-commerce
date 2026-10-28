package io.github.bamdule.dawn.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaAdmin;

class DeadLetterTopicTest extends OrderIntegrationTest {

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Test
    void 구독하는_토픽의_처리_실패_토픽을_14일_보존으로_만든다() throws Exception {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            ConfigResource topic = new ConfigResource(ConfigResource.Type.TOPIC, "payment.events-dlt");
            Config config = adminClient.describeConfigs(List.of(topic)).all().get().get(topic);

            assertThat(config.get("retention.ms").value()).isEqualTo(String.valueOf(14L * 24 * 60 * 60 * 1000));
            assertThat(adminClient.describeTopics(List.of("payment.events-dlt")).allTopicNames().get()
                    .get("payment.events-dlt").partitions()).hasSize(12);
        }
    }
}
