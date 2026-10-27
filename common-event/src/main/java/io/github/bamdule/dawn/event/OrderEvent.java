package io.github.bamdule.dawn.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import java.util.UUID;

// 주문 서비스가 아웃박스로 발행하고 재고, 풀필먼트 등이 소비하는 이벤트.
// PAID 는 주문 전체 품목과 배송지, PAID 커밋 시각을 싣는다. 풀필먼트가 이 값으로 출고 대상을 미리 쌓는다 (docs/design/05).
// CANCELLED 는 이번에 취소된 품목과 수량만 싣는다
public record OrderEvent(
        UUID eventId,
        OrderEventType type,
        String orderNo,
        long memberId,
        long totalAmount,
        List<OrderLine> lines,
        CancelReason cancelReason,
        Delivery delivery,
        LocalDateTime paidAt,
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
        if (type == OrderEventType.PAID && (delivery == null || paidAt == null)) {
            throw new IllegalArgumentException("결제 완료 이벤트에는 배송지와 결제 완료 시각이 필요합니다.");
        }
    }

    public String partitionKey() {
        return orderNo;
    }

    public record OrderLine(long productId, int quantity) {
    }

    // 권역은 할당의 센터 선택에, 수령인과 주소는 출고 지시에 쓴다
    public record Delivery(String recipientName, String address, String regionCode) {
    }
}
