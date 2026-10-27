package io.github.bamdule.dawn.fulfillment.event;

import io.github.bamdule.dawn.event.FulfillmentEvent;
import io.github.bamdule.dawn.event.FulfillmentEventType;
import io.github.bamdule.dawn.event.OrderEvent.OrderLine;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.messaging.EventIds;
import io.github.bamdule.dawn.messaging.OutboxMessage;
import io.github.bamdule.dawn.messaging.OutboxWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// 이벤트 아이디는 사실에서 만든다. UNFILLABLE 은 (주문, 마감 시각), EXCEPTION_CANCELLED 는 예외 번호, STOCK_CHANGED 는 조정 번호다.
// 판정 발행을 다시 돌려도 같은 아이디라 아웃박스 유니크가 두 번째 기록을 막는다 (docs/design/03 표)
@Component
public class FulfillmentEventPublisher {

    private final OutboxWriter outboxWriter;
    private final JsonMapper jsonMapper;

    public FulfillmentEventPublisher(OutboxWriter outboxWriter, JsonMapper jsonMapper) {
        this.outboxWriter = outboxWriter;
        this.jsonMapper = jsonMapper;
    }

    public void unfillable(String orderNo, LocalDateTime cutoffAt, List<OrderLine> lines) {
        publish(new FulfillmentEvent(EventIds.of("fulfillment", orderNo, cutoffAt.toString(), "UNFILLABLE"),
                FulfillmentEventType.UNFILLABLE, orderNo, cutoffAt, null, lines, null, null, null, Instant.now()));
    }

    public void exceptionCancelled(String orderNo, long exceptionNo, List<OrderLine> lines) {
        publish(new FulfillmentEvent(EventIds.of("fulfillment", "exception", String.valueOf(exceptionNo), "CANCELLED"),
                FulfillmentEventType.EXCEPTION_CANCELLED, orderNo, null, exceptionNo, lines, null, null, null, Instant.now()));
    }

    public void stockChanged(long adjustmentNo, long productId, int quantityDelta) {
        publish(new FulfillmentEvent(EventIds.of("fulfillment", "adjustment", String.valueOf(adjustmentNo)),
                FulfillmentEventType.STOCK_CHANGED, null, null, null, List.of(), adjustmentNo, productId, quantityDelta,
                Instant.now()));
    }

    private void publish(FulfillmentEvent fulfillmentEvent) {
        outboxWriter.write(new OutboxMessage(fulfillmentEvent.eventId(), Topics.FULFILLMENT_EVENTS, fulfillmentEvent.partitionKey(),
                jsonMapper.writeValueAsString(fulfillmentEvent)));
    }
}
