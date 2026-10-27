package io.github.bamdule.dawn.payment.pg;

import java.time.LocalDate;
import java.util.List;

public interface PgClient {

    String UNKNOWN_TIMEOUT = "PG_TIMEOUT";
    String UNKNOWN_SERVER_ERROR = "PG_5XX";

    PgApproveResult approve(String idempotencyKey, long amount, String method);

    // APPROVED, DECLINED, NOT_FOUND. 대행사가 답하지 못하면 예외를 던진다
    String inquire(String idempotencyKey);

    // COMPLETED, DECLINED, UNKNOWN
    String refund(String refundKey, String paymentKey, long amount);

    Settlement settlement(LocalDate date);

    boolean idempotencySupported();

    record Settlement(List<SettledPayment> approvals, List<SettledRefund> refunds) {
    }

    record SettledPayment(String idempotencyKey, String status, long amount) {
    }

    record SettledRefund(String refundKey, String paymentKey, String status, long amount) {
    }
}
