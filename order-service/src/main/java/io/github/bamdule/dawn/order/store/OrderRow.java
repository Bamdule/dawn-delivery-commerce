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

    public boolean paidBy(int round) {
        return status == OrderStatus.PAID && paidRound != null && paidRound == round;
    }
}
