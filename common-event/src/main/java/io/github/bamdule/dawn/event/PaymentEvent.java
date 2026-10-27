package io.github.bamdule.dawn.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// 결제 서비스가 아웃박스로 발행하고 주문 서비스가 소비한다. 키는 주문번호다 (docs/design/03).
// settlementCorrection 이 참이면 정산 대사가 하루 뒤 바로잡은 결과다. 주문 서비스는 이런 승인을 늘 환불한다 (docs/design/04)
public record PaymentEvent(
        UUID eventId,
        PaymentEventType type,
        String orderNo,
        int paymentRound,
        long amount,
        String refundKey,
        boolean settlementCorrection,
        Instant occurredAt
) {

    public PaymentEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(orderNo, "orderNo");
    }
}
