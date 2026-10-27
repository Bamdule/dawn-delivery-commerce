package io.github.bamdule.dawn.inventory.consumer;

import io.github.bamdule.dawn.event.FulfillmentEvent;
import io.github.bamdule.dawn.event.FulfillmentEventType;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.inventory.stock.SellableStock;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 풀필먼트의 입고와 실사 차이를 판매 가능 재고에 반영한다. 값을 더하는 소비라 인박스로 중복을 막는다 (docs/design/02)
@Component
public class FulfillmentEventConsumer {

    static final String STOCK_CHANGED_CONSUMER = "inventory-stock-changed";

    private final JsonMapper jsonMapper;
    private final SellableStock sellableStock;
    private final InboxRepository inboxRepository;

    public FulfillmentEventConsumer(JsonMapper jsonMapper, SellableStock sellableStock, InboxRepository inboxRepository) {
        this.jsonMapper = jsonMapper;
        this.sellableStock = sellableStock;
        this.inboxRepository = inboxRepository;
    }

    // 같은 클래스의 handle 을 직접 부르면 프록시를 거치지 않아 handle 의 트랜잭션이 걸리지 않는다. 여기서 연다
    @Transactional
    @KafkaListener(topics = Topics.FULFILLMENT_EVENTS, groupId = "inventory-service")
    public void onMessage(String payload) {
        handle(jsonMapper.readValue(payload, FulfillmentEvent.class));
    }

    // 못 채운 품목과 관리자 취소는 주문 서비스가 CANCELLED 로 다시 알려 주므로 여기서는 넘긴다
    @Transactional
    public void handle(FulfillmentEvent fulfillmentEvent) {
        if (fulfillmentEvent.type() != FulfillmentEventType.STOCK_CHANGED) {
            return;
        }
        String eventId = fulfillmentEvent.eventId().toString();
        if (!inboxRepository.recordIfAbsent(STOCK_CHANGED_CONSUMER, eventId)) {
            return;
        }
        int quantityDelta = fulfillmentEvent.quantityDelta();
        if (quantityDelta > 0) {
            sellableStock.increaseForAdjustment(eventId, fulfillmentEvent.productId(), quantityDelta);
        } else if (quantityDelta < 0) {
            sellableStock.decreaseForStocktake(eventId, fulfillmentEvent.productId(), -quantityDelta);
        }
    }
}
