package io.github.bamdule.dawn.order.store;

import io.github.bamdule.dawn.order.OrderStatus;
import io.github.bamdule.dawn.order.PaymentAttempt;
import java.time.LocalDateTime;

public record OrderRow(long id, String orderNo, String checkoutId, long memberId, OrderStatus status, long totalAmount,
                       long refundedAmount, int paymentRound, PaymentAttempt paymentAttempt, Integer paidRound,
                       String recipientName, String address, String regionCode, String cancelReason,
                       LocalDateTime reservationExpiresAt, LocalDateTime createdAt, LocalDateTime paidAt) {

    public boolean attemptInProgress(int round) {
        return status == OrderStatus.PENDING && paymentAttempt == PaymentAttempt.IN_PROGRESS && paymentRound == round;
    }

    // 결제한 뒤 취소된 주문도 그 회차로 결제한 주문이다. 같은 회차의 승인이 다시 와도 쓰이지 않은 승인이 아니다
    public boolean paidBy(int round) {
        return paidRound != null && paidRound == round;
    }
}
