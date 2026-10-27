package io.github.bamdule.dawn.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// 풀필먼트가 아웃박스로 발행한다. UNFILLABLE 과 EXCEPTION_CANCELLED 는 주문 서비스가 품목을 취소하고 환불하며,
// STOCK_CHANGED 는 재고 서비스가 판매 가능 재고에 반영한다. 키는 앞의 둘이 주문번호, 뒤의 것이 상품번호다 (docs/design/03)
public record FulfillmentEvent(
        UUID eventId,
        FulfillmentEventType type,
        String orderNo,
        LocalDateTime cutoffAt,
        Long exceptionNo,
        List<OrderEvent.OrderLine> lines,
        Long adjustmentNo,
        Long productId,
        Integer quantityDelta,
        Instant occurredAt
) {

    public FulfillmentEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        lines = lines == null ? List.of() : List.copyOf(lines);
        switch (type) {
            case UNFILLABLE -> require(orderNo != null && cutoffAt != null, "못 채운 품목 이벤트에는 주문번호와 마감 시각이 필요합니다.");
            case EXCEPTION_CANCELLED -> require(orderNo != null && exceptionNo != null, "관리자 취소 이벤트에는 주문번호와 예외 번호가 필요합니다.");
            case STOCK_CHANGED -> require(productId != null && quantityDelta != null && adjustmentNo != null,
                    "재고 조정 이벤트에는 상품번호와 수량 변화, 조정 번호가 필요합니다.");
        }
    }

    public String partitionKey() {
        return type == FulfillmentEventType.STOCK_CHANGED ? String.valueOf(productId) : orderNo;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
