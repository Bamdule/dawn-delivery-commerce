package io.github.bamdule.dawn.messaging;

import java.time.Duration;
import java.util.Arrays;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

// 처리 실패 토픽은 원인을 고쳐 다시 넣을 시간으로 14일 둔다 (docs/design/03 "보존과 정리").
// 실패한 메시지는 원래 파티션 번호로 가므로 원래 토픽과 같은 12개 파티션으로 만든다
public final class DeadLetterTopics {

    private static final int PARTITIONS = 12;
    private static final Duration RETENTION = Duration.ofDays(14);

    private DeadLetterTopics() {
    }

    // 받는 서비스가 자기가 구독하는 토픽의 처리 실패 토픽을 선언한다. 이미 있으면 설정만 맞춘다
    public static KafkaAdmin.NewTopics forSources(String... sourceTopics) {
        return new KafkaAdmin.NewTopics(Arrays.stream(sourceTopics)
                .map(sourceTopic -> TopicBuilder.name(sourceTopic + "-dlt")
                        .partitions(PARTITIONS)
                        .replicas(1)
                        .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(RETENTION.toMillis()))
                        .build())
                .toArray(NewTopic[]::new));
    }
}
