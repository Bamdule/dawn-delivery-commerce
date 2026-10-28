package io.github.bamdule.dawn.order;

import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.messaging.DeadLetterTopics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

// 주문과 환불 요청은 거래 기록이라 지우지 않는다. 아웃박스 SENT 7일은 메시징 모듈이 등록한다
@Configuration
public class OrderRetention {

    @Bean
    public KafkaAdmin.NewTopics orderDeadLetterTopics() {
        return DeadLetterTopics.forSources(Topics.PAYMENT_EVENTS, Topics.FULFILLMENT_EVENTS);
    }
}
