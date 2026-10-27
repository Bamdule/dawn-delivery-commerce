package io.github.bamdule.dawn.order.refund;

public record RefundRequestRow(long id, String orderNo, String refundKey, int paymentRound, long amount,
                               RefundReason reason, RefundStatus status, int attemptCount, boolean overdue) {
}
