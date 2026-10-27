package io.github.bamdule.dawn.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// 주문 서비스가 아웃박스로 발행하고 재고, 회원, 알림, 풀필먼트가 소비하는 이벤트
public record OrderEvent(
        UUID eventId,
        OrderEventType type,
        String orderNo,
        long memberId,
        long totalAmount,
        List<OrderLine> lines,
        Instant occurredAt
) {

    public OrderEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(orderNo, "orderNo");
        lines = List.copyOf(lines);
    }

    public String partitionKey() {
        return orderNo;
    }

    public record OrderLine(long productId, String reservationId, int quantity) {
    }
}
