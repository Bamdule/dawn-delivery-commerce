package io.github.bamdule.dawn.order.recovery;

import io.github.bamdule.dawn.event.PaymentEvent;
import io.github.bamdule.dawn.event.PaymentEventType;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.order.client.InventoryClient;
import io.github.bamdule.dawn.order.refund.RefundRequests;
import io.github.bamdule.dawn.order.store.OrderCompletion;
import io.github.bamdule.dawn.order.store.OrderRepository;
import io.github.bamdule.dawn.order.store.OrderRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

// 늦게 확정된 결제를 주문에 반영하고, 주문이 쓰지 않은 승인을 환불하고, 환불 결과를 반영한다.
// 모든 반영이 조건부 갱신이나 환불 키 유니크라 같은 이벤트가 두 번 와도 한 번만 바뀐다
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final JsonMapper jsonMapper;
    private final OrderRepository orderRepository;
    private final OrderCompletion orderCompletion;
    private final RefundRequests refundRequests;
    private final InventoryClient inventoryClient;
    private final TransactionTemplate transactionTemplate;

    public PaymentEventConsumer(JsonMapper jsonMapper, OrderRepository orderRepository, OrderCompletion orderCompletion,
                                RefundRequests refundRequests, InventoryClient inventoryClient,
                                TransactionTemplate transactionTemplate) {
        this.jsonMapper = jsonMapper;
        this.orderRepository = orderRepository;
        this.orderCompletion = orderCompletion;
        this.refundRequests = refundRequests;
        this.inventoryClient = inventoryClient;
        this.transactionTemplate = transactionTemplate;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "order-service")
    public void onMessage(String payload) {
        handle(jsonMapper.readValue(payload, PaymentEvent.class));
    }

    // 승인 반영은 주문 행을 잠근 채 상태를 보고 나눈다. 같은 주문의 동기 응답이나 정리 배치와 겹쳐도 한쪽씩 지나간다.
    // 실패 반영은 재고 서비스를 불러야 해서 트랜잭션 밖에서 한다. 주문 행을 잠근 채 다른 서비스를 기다리지 않는다
    public void handle(PaymentEvent paymentEvent) {
        if (paymentEvent.type() == PaymentEventType.FAILED) {
            onFailed(paymentEvent);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            switch (paymentEvent.type()) {
                case APPROVED -> onApproved(paymentEvent);
                case REFUNDED -> refundRequests.complete(paymentEvent.refundKey());
                case REFUND_FAILED -> {
                    if (paymentEvent.settlementCorrection()) {
                        refundRequests.reverseCompleted(paymentEvent.refundKey());
                    } else {
                        refundRequests.fail(paymentEvent.refundKey());
                    }
                }
                case FAILED -> throw new IllegalStateException("실패 이벤트는 위에서 처리합니다.");
            }
        });
    }

    // 실패로 끝난 회차의 시도를 바로 끝내 예약 유지 시간 안에 다음 회차를 받을 수 있게 한다. 주문은 PENDING 그대로 둔다.
    // 되돌림이 실패하면 시도를 그대로 두고 1분 뒤 정리 배치가 봉인해 끝낸다
    private void onFailed(PaymentEvent paymentEvent) {
        String orderNo = paymentEvent.orderNo();
        int paymentRound = paymentEvent.paymentRound();
        boolean inProgress = orderRepository.findByOrderNo(orderNo)
                .map(orderRow -> orderRow.attemptInProgress(paymentRound))
                .orElse(false);
        if (!inProgress) {
            return;
        }
        try {
            inventoryClient.revertPaying(orderNo, paymentRound);
        } catch (RestClientException restClientException) {
            log.warn("결제 실패 이벤트의 예약 되돌림 실패 (orderNo={}, paymentRound={})", orderNo, paymentRound);
            return;
        }
        orderRepository.endAttempt(orderNo, paymentRound);
    }

    // 정산 대사가 바로잡은 승인은 늘 환불한다. 하루 사이 그 회차의 재고는 이미 다시 팔렸을 수 있다 (docs/design/04 표)
    private void onApproved(PaymentEvent paymentEvent) {
        String orderNo = paymentEvent.orderNo();
        int paymentRound = paymentEvent.paymentRound();
        if (!paymentEvent.settlementCorrection()) {
            OrderRow orderRow = orderRepository.lockByOrderNo(orderNo).orElse(null);
            if (orderRow != null && orderRow.attemptInProgress(paymentRound)) {
                orderCompletion.completePaid(orderNo, paymentRound);
                return;
            }
            if (orderRow != null && orderRow.paidBy(paymentRound)) {
                return;
            }
        }
        refundRequests.requestOrphan(orderNo, paymentRound, paymentEvent.amount());
    }
}
