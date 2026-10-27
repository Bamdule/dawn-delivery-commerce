package io.github.bamdule.dawn.order.placement;

import io.github.bamdule.dawn.order.OrderStatus;
import io.github.bamdule.dawn.order.PaymentAttempt;
import io.github.bamdule.dawn.order.store.OrderRow;

public record OrderResult(String orderNo, OrderStatus status, OrderOutcome outcome, String failureCode) {

    static OrderResult of(String orderNo, OrderStatus status, OrderOutcome outcome) {
        return new OrderResult(orderNo, status, outcome, null);
    }

    static OrderResult soldOut(String orderNo, long productId) {
        return new OrderResult(orderNo, orderNo == null ? null : OrderStatus.FAILED, OrderOutcome.SOLD_OUT,
                "SOLD_OUT_" + productId);
    }

    // 같은 주문서 ID 로 다시 왔거나 결제를 다시 눌렀을 때 주문의 지금 모습으로 답한다
    static OrderResult current(OrderRow orderRow) {
        OrderOutcome outcome = switch (orderRow.status()) {
            case PAID -> OrderOutcome.PAID;
            case FAILED -> OrderOutcome.FAILED;
            case CANCELLED -> OrderOutcome.CANCELLED;
            case PENDING -> orderRow.paymentAttempt() == PaymentAttempt.IN_PROGRESS
                    ? OrderOutcome.PAYMENT_IN_PROGRESS
                    : OrderOutcome.RETRY_PAYMENT;
        };
        return of(orderRow.orderNo(), orderRow.status(), outcome);
    }
}
