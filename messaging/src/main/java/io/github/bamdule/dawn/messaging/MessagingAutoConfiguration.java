package io.github.bamdule.dawn.messaging;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(afterName = {
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration"})
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class MessagingAutoConfiguration {

    // 전송 제한 시간(20초)과 블록 시간(5초)의 합이 선점 30초 안이어야, 선점이 끝나기 전에 전송 결과가 정해진다
    private static final int DELIVERY_TIMEOUT_MILLIS = 20_000;
    private static final int REQUEST_TIMEOUT_MILLIS = 15_000;
    private static final int MAX_BLOCK_MILLIS = 5_000;

    @Bean
    public DefaultKafkaProducerFactoryCustomizer outboxProducerSettings() {
        return producerFactory -> producerFactory.updateConfigs(java.util.Map.of(
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, DELIVERY_TIMEOUT_MILLIS,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, REQUEST_TIMEOUT_MILLIS,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MILLIS));
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(JdbcClient jdbcClient) {
        return new OutboxWriter(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaTemplate.class)
    public OutboxRelay outboxRelay(JdbcClient jdbcClient, TransactionTemplate transactionTemplate,
                                   KafkaTemplate<String, String> kafkaTemplate, OutboxProperties outboxProperties) {
        return new OutboxRelay(jdbcClient, transactionTemplate, kafkaTemplate, outboxProperties);
    }

    @Bean
    @ConditionalOnBean(OutboxRelay.class)
    @ConditionalOnProperty(prefix = "dawn.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OutboxRelayScheduler outboxRelayScheduler(OutboxRelay outboxRelay, OutboxProperties outboxProperties) {
        return new OutboxRelayScheduler(outboxRelay, outboxProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxMaintenance outboxMaintenance(JdbcClient jdbcClient) {
        return new OutboxMaintenance(jdbcClient);
    }

    @Bean
    @ConditionalOnProperty(prefix = "dawn.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OutboxCleanupScheduler outboxCleanupScheduler(OutboxMaintenance outboxMaintenance) {
        return new OutboxCleanupScheduler(outboxMaintenance);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({KafkaTemplate.class, ConsumerFactory.class})
    public DeadLetterReplayer deadLetterReplayer(ConsumerFactory<String, String> consumerFactory,
                                                 KafkaTemplate<String, String> kafkaTemplate) {
        return new DeadLetterReplayer(consumerFactory, kafkaTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaTemplate.class)
    public DefaultErrorHandler deadLetterErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        return ConsumerErrorHandling.deadLetterErrorHandler(kafkaTemplate);
    }
}
