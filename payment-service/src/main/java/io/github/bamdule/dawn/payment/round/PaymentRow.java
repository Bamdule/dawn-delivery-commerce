package io.github.bamdule.dawn.payment.round;

import io.github.bamdule.dawn.payment.PaymentStatus;

public record PaymentRow(long id, String orderNo, int paymentRound, String idempotencyKey, long amount,
                         PaymentStatus status, String failureCode, int attemptCount) {
}
