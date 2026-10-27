package io.github.bamdule.dawn.fakepg;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class FakePgStore {

    private final Map<String, PaymentRecord> payments = new ConcurrentHashMap<>();
    private final Map<String, RefundRecord> refunds = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ScheduledBehavior> approveBehaviors = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> refundBehaviors = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean idempotencySupported = new AtomicBoolean(true);
    private final AtomicInteger approveCalls = new AtomicInteger();

    public ScheduledBehavior nextApproveBehavior() {
        ScheduledBehavior next = approveBehaviors.poll();
        return next == null ? new ScheduledBehavior(FakePgBehavior.APPROVE, 0) : next;
    }

    public String nextRefundBehavior() {
        String next = refundBehaviors.poll();
        return next == null ? "COMPLETE" : next;
    }

    public void enqueueApprove(FakePgBehavior behavior, long delayMillis) {
        approveBehaviors.add(new ScheduledBehavior(behavior, delayMillis));
    }

    public void enqueueRefund(String behavior) {
        refundBehaviors.add(behavior);
    }

    public Optional<PaymentRecord> payment(String idempotencyKey) {
        return Optional.ofNullable(payments.get(idempotencyKey));
    }

    public void savePayment(String idempotencyKey, String status, long amount) {
        payments.put(idempotencyKey, new PaymentRecord(idempotencyKey, status, amount, LocalDate.now()));
    }

    public Optional<RefundRecord> refund(String refundKey) {
        return Optional.ofNullable(refunds.get(refundKey));
    }

    public void saveRefund(String refundKey, String paymentKey, String status, long amount) {
        refunds.put(refundKey, new RefundRecord(refundKey, paymentKey, status, amount, LocalDate.now()));
    }

    public List<PaymentRecord> approvedOn(LocalDate date) {
        return new ArrayList<>(payments.values().stream()
                .filter(payment -> payment.status().equals("APPROVED") && payment.date().equals(date)).toList());
    }

    public List<RefundRecord> refundsOn(LocalDate date) {
        return new ArrayList<>(refunds.values().stream().filter(refund -> refund.date().equals(date)).toList());
    }

    public boolean idempotencySupported() {
        return idempotencySupported.get();
    }

    public void setIdempotencySupported(boolean supported) {
        idempotencySupported.set(supported);
    }

    public int countApproveCall() {
        return approveCalls.incrementAndGet();
    }

    public int approveCalls() {
        return approveCalls.get();
    }

    public long approvedCount(String idempotencyKey) {
        return payments.containsKey(idempotencyKey) && payments.get(idempotencyKey).status().equals("APPROVED") ? 1 : 0;
    }

    public void reset() {
        payments.clear();
        refunds.clear();
        approveBehaviors.clear();
        refundBehaviors.clear();
        idempotencySupported.set(true);
        approveCalls.set(0);
    }

    public record ScheduledBehavior(FakePgBehavior behavior, long delayMillis) {
    }

    public record PaymentRecord(String idempotencyKey, String status, long amount, LocalDate date) {
    }

    public record RefundRecord(String refundKey, String paymentKey, String status, long amount, LocalDate date) {
    }
}
