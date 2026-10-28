package io.github.bamdule.dawn.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(afterName = {
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"})
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
    @ConditionalOnMissingBean
    public RetentionCleaner retentionCleaner(JdbcClient jdbcClient) {
        return new RetentionCleaner(jdbcClient);
    }

    @Bean
    @ConditionalOnProperty(prefix = "dawn.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RetentionRule outboxSentRetention() {
        return OutboxMaintenance.SENT_RETENTION;
    }

    // 서비스가 등록한 보존 규칙 묶음과 메시징 모듈의 아웃박스 규칙을 모두 돈다
    @Bean
    @ConditionalOnProperty(prefix = "dawn.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RetentionCleanupScheduler retentionCleanupScheduler(RetentionCleaner retentionCleaner,
                                                               ObjectProvider<RetentionRules> serviceRules,
                                                               ObjectProvider<RetentionRule> singleRules) {
        List<RetentionRule> rules = new ArrayList<>();
        serviceRules.orderedStream().forEach(retentionRules -> rules.addAll(retentionRules.rules()));
        singleRules.orderedStream().forEach(rules::add);
        return new RetentionCleanupScheduler(retentionCleaner, rules);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({KafkaTemplate.class, ConsumerFactory.class})
    public DeadLetterReplayer deadLetterReplayer(ConsumerFactory<String, String> consumerFactory,
                                                 KafkaTemplate<String, String> kafkaTemplate) {
        return new DeadLetterReplayer(consumerFactory, kafkaTemplate);
    }

    // 서비스가 선언한 처리 실패 토픽에서 원래 토픽 이름을 거둔다
    @Bean
    @ConditionalOnMissingBean
    public DeadLetterSources deadLetterSources(ObjectProvider<KafkaAdmin.NewTopics> declaredTopics) {
        List<String> originalTopics = declaredTopics.orderedStream()
                .filter(DeadLetterTopics.Declared.class::isInstance)
                .flatMap(newTopics -> ((DeadLetterTopics.Declared) newTopics).sourceTopics().stream())
                .distinct()
                .toList();
        return new DeadLetterSources(originalTopics);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnBean(DeadLetterReplayer.class)
    public DeadLetterAdminController deadLetterAdminController(DeadLetterReplayer deadLetterReplayer,
                                                               DeadLetterSources deadLetterSources) {
        return new DeadLetterAdminController(deadLetterReplayer, deadLetterSources);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaTemplate.class)
    public DefaultErrorHandler deadLetterErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                      ObjectProvider<MeterRegistry> meterRegistry) {
        return ConsumerErrorHandling.deadLetterErrorHandler(kafkaTemplate, meterRegistry.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MeterRegistry.class)
    public DbGauges dbGauges(MeterRegistry meterRegistry) {
        return new DbGauges(meterRegistry);
    }

    @Bean
    @ConditionalOnBean(DbGauges.class)
    @ConditionalOnProperty(prefix = "dawn.metrics.refresh", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DbGaugeRefresher dbGaugeRefresher(DbGauges dbGauges) {
        return new DbGaugeRefresher(dbGauges);
    }

    // 재고 서비스는 이벤트를 내지 않아 아웃박스 표가 없다. 그런 서비스는 dawn.outbox.enabled 를 꺼서 지표와 관리 API 를 만들지 않는다
    @Bean
    @ConditionalOnBean(DbGauges.class)
    @ConditionalOnProperty(prefix = "dawn.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SmartInitializingSingleton outboxMetrics(DbGauges dbGauges, JdbcClient jdbcClient, OutboxProperties outboxProperties) {
        return () -> OutboxMetrics.register(dbGauges, jdbcClient, outboxProperties);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "dawn.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OutboxAdminController outboxAdminController(OutboxMaintenance outboxMaintenance, OutboxProperties outboxProperties) {
        return new OutboxAdminController(outboxMaintenance, outboxProperties);
    }

    // 늘어난 수가 아니라 쌓여 있는 수를 낸다. 다시 넣을 때까지 경보가 꺼지지 않는다 (infra/prometheus/alerts.yml)
    @Bean
    @ConditionalOnBean({DbGauges.class, DeadLetterReplayer.class})
    public SmartInitializingSingleton deadLetterBacklogMetric(DbGauges dbGauges, DeadLetterReplayer deadLetterReplayer,
                                                              DeadLetterSources deadLetterSources) {
        return () -> {
            if (deadLetterSources.originalTopics().isEmpty()) {
                return;
            }
            dbGauges.register(ConsumerErrorHandling.DEAD_LETTER_METRIC + ".backlog",
                    "다시 넣지 않은 처리 실패 토픽의 메시지. 1건이라도 있으면 경보", "messages",
                    () -> deadLetterSources.originalTopics().stream().mapToLong(deadLetterReplayer::backlog).sum());
        };
    }
}
