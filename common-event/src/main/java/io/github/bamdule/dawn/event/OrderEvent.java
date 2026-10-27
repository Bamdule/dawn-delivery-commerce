package io.github.bamdule.dawn.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// 주문 서비스가 아웃박스로 발행하고 재고, 풀필먼트 등이 소비하는 이벤트.
// PAID 는 주문 전체 품목을, CANCELLED 는 이번에 취소된 품목과 수량만 싣는다
public record OrderEvent(
        UUID eventId,
        OrderEventType type,
        String orderNo,
        long memberId,
        long totalAmount,
        List<OrderLine> lines,
        CancelReason cancelReason,
        Instant occurredAt
) {

    public OrderEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(orderNo, "orderNo");
        lines = List.copyOf(lines);
        if (type == OrderEventType.CANCELLED && cancelReason == null) {
            throw new IllegalArgumentException("취소 이벤트에는 사유가 필요합니다.");
        }
    }

    public String partitionKey() {
        return orderNo;
    }

    public record OrderLine(long productId, int quantity) {
    }
}
