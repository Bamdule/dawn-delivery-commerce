package io.github.bamdule.dawn.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

// 기본 처리기는 재시도를 다 쓰면 로그만 남기고 넘어가 그 메시지가 조용히 사라진다.
// 1, 2, 4초로 세 번 다시 시도한 뒤 {토픽}-dlt 로 같은 키와 함께 보낸다 (docs/design/03). 이름은 Spring Kafka 기본값이다
public final class ConsumerErrorHandling {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_INTERVAL_MILLIS = 1_000;
    private static final double MULTIPLIER = 2.0;

    private ConsumerErrorHandling() {
    }

    public static DefaultErrorHandler deadLetterErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MILLIS);
        backOff.setMultiplier(MULTIPLIER);
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate), backOff);
    }
}
