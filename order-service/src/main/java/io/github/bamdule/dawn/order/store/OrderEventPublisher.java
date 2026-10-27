package io.github.bamdule.dawn.order.store;

import io.github.bamdule.dawn.event.CancelReason;
import io.github.bamdule.dawn.event.OrderEvent;
import io.github.bamdule.dawn.event.OrderEvent.Delivery;
import io.github.bamdule.dawn.event.OrderEvent.OrderLine;
import io.github.bamdule.dawn.event.OrderEventType;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.messaging.EventIds;
import io.github.bamdule.dawn.messaging.OutboxMessage;
import io.github.bamdule.dawn.messaging.OutboxWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// paid_at 은 occurredAt(앱 시계)이 아니라 주문 DB 가 커밋한 시각이다. 마감 회차를 이 값으로 정한다 (docs/design/05).
// PAID 의 아이디는 주문번호, CANCELLED 의 아이디는 그 취소의 키에서 만든다.
// 한 주문에 부분 취소가 여러 번 와도 아이디가 겹치지 않고, 같은 취소가 두 번 기록되면 받는 쪽이 거른다 (docs/design/03 표)
@Component
public class OrderEventPublisher {

    private final OutboxWriter outboxWriter;
    private final JsonMapper jsonMapper;

    public OrderEventPublisher(OutboxWriter outboxWriter, JsonMapper jsonMapper) {
        this.outboxWriter = outboxWriter;
        this.jsonMapper = jsonMapper;
    }

    public void paid(OrderRow orderRow, List<OrderItemRow> items) {
        List<OrderLine> lines = items.stream().map(item -> new OrderLine(item.productId(), item.quantity())).toList();
        Delivery delivery = new Delivery(orderRow.recipientName(), orderRow.address(), orderRow.regionCode());
        publish(new OrderEvent(EventIds.of("order", orderRow.orderNo(), "PAID"), OrderEventType.PAID, orderRow.orderNo(),
                orderRow.memberId(), orderRow.totalAmount(), lines, null, delivery, orderRow.paidAt(), Instant.now()));
    }

    public void cancelled(OrderRow orderRow, List<OrderLine> cancelledLines, CancelReason cancelReason, String cancelKey) {
        UUID eventId = EventIds.of("order", orderRow.orderNo(), "CANCELLED", cancelKey);
        publish(new OrderEvent(eventId, OrderEventType.CANCELLED, orderRow.orderNo(), orderRow.memberId(),
                orderRow.totalAmount(), cancelledLines, cancelReason, null, null, Instant.now()));
    }

    private void publish(OrderEvent orderEvent) {
        outboxWriter.write(new OutboxMessage(orderEvent.eventId(), Topics.ORDER_EVENTS, orderEvent.partitionKey(),
                jsonMapper.writeValueAsString(orderEvent)));
    }
}
