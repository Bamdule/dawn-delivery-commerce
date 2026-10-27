package io.github.bamdule.dawn.messaging.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

// MySqlContainers 와 같은 이유로 JVM 하나에 한 벌만 띄운다
@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainers {

    private static final KafkaContainer KAFKA_CONTAINER = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    static {
        KAFKA_CONTAINER.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    public KafkaContainer kafkaContainer() {
        return KAFKA_CONTAINER;
    }
}
