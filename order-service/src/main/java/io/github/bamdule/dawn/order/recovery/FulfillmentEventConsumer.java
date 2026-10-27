package io.github.bamdule.dawn.order.recovery;

import io.github.bamdule.dawn.event.FulfillmentEvent;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.order.cancel.OrderCancellation;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// 판정은 풀필먼트가 하고 환불은 주문 서비스가 한다. 중복은 인박스 대신 환불 키 유니크로 막는다 (docs/design/03)
@Component
public class FulfillmentEventConsumer {

    private final JsonMapper jsonMapper;
    private final OrderCancellation orderCancellation;

    public FulfillmentEventConsumer(JsonMapper jsonMapper, OrderCancellation orderCancellation) {
        this.jsonMapper = jsonMapper;
        this.orderCancellation = orderCancellation;
    }

    @KafkaListener(topics = Topics.FULFILLMENT_EVENTS, groupId = "order-service")
    public void onMessage(String payload) {
        handle(jsonMapper.readValue(payload, FulfillmentEvent.class));
    }

    public void handle(FulfillmentEvent fulfillmentEvent) {
        switch (fulfillmentEvent.type()) {
            case UNFILLABLE -> orderCancellation.cancelUnfillable(fulfillmentEvent);
            case EXCEPTION_CANCELLED -> orderCancellation.cancelByAdmin(fulfillmentEvent);
            // 판매 가능 재고에 반영하는 쪽은 재고 서비스다
            case STOCK_CHANGED -> {
            }
        }
    }
}
