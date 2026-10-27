package io.github.bamdule.dawn.payment.round;

import io.github.bamdule.dawn.event.PaymentEvent;
import io.github.bamdule.dawn.event.PaymentEventType;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.messaging.EventIds;
import io.github.bamdule.dawn.messaging.OutboxMessage;
import io.github.bamdule.dawn.messaging.OutboxWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// 이벤트 아이디는 멱등키나 환불 키에서 만든다. 같은 결과가 두 번 기록돼도 받는 쪽이 중복을 거른다 (docs/design/03 표)
@Component
public class PaymentEventPublisher {

    private final OutboxWriter outboxWriter;
    private final JsonMapper jsonMapper;

    public PaymentEventPublisher(OutboxWriter outboxWriter, JsonMapper jsonMapper) {
        this.outboxWriter = outboxWriter;
        this.jsonMapper = jsonMapper;
    }

    public void roundResult(PaymentEventType type, PaymentRow paymentRow, boolean settlementCorrection) {
        UUID eventId = EventIds.of("payment", paymentRow.idempotencyKey(), type.name(), settlementCorrection ? "settlement" : "live");
        publish(new PaymentEvent(eventId, type, paymentRow.orderNo(), paymentRow.paymentRound(), paymentRow.amount(),
                null, settlementCorrection, Instant.now()));
    }

    public void refundResult(PaymentEventType type, String orderNo, int paymentRound, String refundKey, long amount,
                             boolean settlementCorrection) {
        UUID eventId = EventIds.of("refund", refundKey, type.name(), settlementCorrection ? "settlement" : "live");
        publish(new PaymentEvent(eventId, type, orderNo, paymentRound, amount, refundKey, settlementCorrection, Instant.now()));
    }

    private void publish(PaymentEvent paymentEvent) {
        outboxWriter.write(new OutboxMessage(paymentEvent.eventId(), Topics.PAYMENT_EVENTS, paymentEvent.orderNo(),
                jsonMapper.writeValueAsString(paymentEvent)));
    }
}
