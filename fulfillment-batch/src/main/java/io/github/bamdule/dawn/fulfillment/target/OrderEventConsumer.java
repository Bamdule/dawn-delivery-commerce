package io.github.bamdule.dawn.fulfillment.target;

import io.github.bamdule.dawn.event.CancelReason;
import io.github.bamdule.dawn.event.OrderEvent;
import io.github.bamdule.dawn.event.OrderEvent.OrderLine;
import io.github.bamdule.dawn.event.OrderEventType;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.fulfillment.target.ShipmentTargetRepository.NewTarget;
import java.util.Comparator;
import java.util.List;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 마감에 한꺼번에 읽지 않으려고 PAID 를 받을 때마다 출고 대상을 쌓는다. 배정 회차는 비워 두고 대상 확정이 정한다 (docs/design/05)
@Component
public class OrderEventConsumer {

    static final String CANCEL_CONSUMER = "fulfillment-customer-cancel";

    private final JsonMapper jsonMapper;
    private final ShipmentTargetRepository shipmentTargetRepository;
    private final InboxRepository inboxRepository;

    public OrderEventConsumer(JsonMapper jsonMapper, ShipmentTargetRepository shipmentTargetRepository,
                              InboxRepository inboxRepository) {
        this.jsonMapper = jsonMapper;
        this.shipmentTargetRepository = shipmentTargetRepository;
        this.inboxRepository = inboxRepository;
    }

    // 같은 클래스의 handle 을 직접 부르면 프록시를 거치지 않아 handle 의 트랜잭션이 걸리지 않는다. 여기서 연다
    @Transactional
    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "fulfillment-batch")
    public void onMessage(String payload) {
        handle(jsonMapper.readValue(payload, OrderEvent.class));
    }

    @Transactional
    public void handle(OrderEvent orderEvent) {
        if (orderEvent.type() == OrderEventType.PAID) {
            shipmentTargetRepository.insertWaiting(toTarget(orderEvent));
            return;
        }
        // 출고 불가와 관리자 취소는 풀필먼트가 이미 수량을 옮긴 뒤에 주문 서비스가 알려 준 것이다
        if (orderEvent.cancelReason() != CancelReason.CUSTOMER
                || !inboxRepository.recordIfAbsent(CANCEL_CONSUMER, orderEvent.eventId().toString())) {
            return;
        }
        if (!shipmentTargetRepository.lockTarget(orderEvent.orderNo())) {
            shipmentTargetRepository.insertCancelledMark(orderEvent.orderNo());
            return;
        }
        List<OrderLine> byProduct = orderEvent.lines().stream().sorted(Comparator.comparingLong(OrderLine::productId)).toList();
        for (OrderLine orderLine : byProduct) {
            int movable = Math.min(orderLine.quantity(),
                    shipmentTargetRepository.waitingOf(orderEvent.orderNo(), orderLine.productId()));
            if (movable > 0) {
                shipmentTargetRepository.cancelWaiting(orderEvent.orderNo(), orderLine.productId(), movable);
            }
        }
        shipmentTargetRepository.markCancelledIfNothingLeft(orderEvent.orderNo());
    }

    private static NewTarget toTarget(OrderEvent orderEvent) {
        return new NewTarget(orderEvent.orderNo(), orderEvent.delivery().regionCode(), orderEvent.delivery().recipientName(),
                orderEvent.delivery().address(), orderEvent.paidAt(),
                orderEvent.lines().stream().map(line -> new NewTarget.Line(line.productId(), line.quantity(), 0)).toList());
    }
}
