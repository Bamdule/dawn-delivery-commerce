package io.github.bamdule.dawn.inventory.consumer;

import io.github.bamdule.dawn.event.OrderEvent;
import io.github.bamdule.dawn.event.OrderEventType;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.inventory.reservation.ReservationLifecycle;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OrderEventConsumer {

    static final String RESTORE_CONSUMER = "inventory-restore";

    private final JsonMapper jsonMapper;
    private final ReservationLifecycle reservationLifecycle;
    private final InboxRepository inboxRepository;

    public OrderEventConsumer(JsonMapper jsonMapper, ReservationLifecycle reservationLifecycle,
                              InboxRepository inboxRepository) {
        this.jsonMapper = jsonMapper;
        this.reservationLifecycle = reservationLifecycle;
        this.inboxRepository = inboxRepository;
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "inventory-service")
    public void onMessage(String payload) {
        handle(jsonMapper.readValue(payload, OrderEvent.class));
    }

    // 확정은 조건부 갱신이라 중복에 안전하고, 복원은 수량을 더하는 소비라 인박스로 중복을 막는다 (docs/design/03)
    @Transactional
    public void handle(OrderEvent orderEvent) {
        if (orderEvent.type() == OrderEventType.PAID) {
            reservationLifecycle.confirm(orderEvent.orderNo());
            return;
        }
        if (!inboxRepository.recordIfAbsent(RESTORE_CONSUMER, orderEvent.eventId().toString())) {
            return;
        }
        for (OrderEvent.OrderLine orderLine : orderEvent.lines()) {
            reservationLifecycle.restore(orderEvent.orderNo(), orderLine.productId(), orderLine.quantity());
        }
    }
}
