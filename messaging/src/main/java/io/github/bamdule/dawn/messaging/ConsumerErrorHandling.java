package io.github.bamdule.dawn.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

// 기본 처리기는 재시도를 다 쓰면 로그만 남기고 넘어가 그 메시지가 조용히 사라진다.
// 1, 2, 4초로 세 번 다시 시도한 뒤 {토픽}-dlt 로 같은 키와 함께 보낸다 (docs/design/03). 이름은 Spring Kafka 기본값이다
public final class ConsumerErrorHandling {

    public static final String DEAD_LETTER_METRIC = "dawn.consumer.dead.letter";
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_INTERVAL_MILLIS = 1_000;
    private static final double MULTIPLIER = 2.0;

    private ConsumerErrorHandling() {
    }

    public static DefaultErrorHandler deadLetterErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        return deadLetterErrorHandler(kafkaTemplate, null);
    }

    // 처리 실패 토픽에 1건이라도 쌓이면 경보다. 보낸 뒤에 토픽별로 센다 (docs/design/01 "운영 지표")
    public static DefaultErrorHandler deadLetterErrorHandler(KafkaTemplate<?, ?> kafkaTemplate, MeterRegistry meterRegistry) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MILLIS);
        backOff.setMultiplier(MULTIPLIER);
        DeadLetterPublishingRecoverer deadLetterPublishingRecoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        if (meterRegistry == null) {
            return new DefaultErrorHandler(deadLetterPublishingRecoverer, backOff);
        }
        ConsumerRecordRecoverer countingRecoverer = (consumerRecord, exception) -> {
            deadLetterPublishingRecoverer.accept(consumerRecord, exception);
            meterRegistry.counter(DEAD_LETTER_METRIC, "topic", consumerRecord.topic()).increment();
        };
        return new DefaultErrorHandler(countingRecoverer, backOff);
    }
}
