package io.github.bamdule.dawn.fakepg;

import io.github.bamdule.dawn.fakepg.FakePgStore.PaymentRecord;
import io.github.bamdule.dawn.fakepg.FakePgStore.RefundRecord;
import io.github.bamdule.dawn.fakepg.FakePgStore.ScheduledBehavior;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 결제 대행사 흉내. 지연, 응답 유실, 5xx, 늦은 처리를 테스트가 골라 재현한다 (docs/TASKS.md 4번)
@RestController
public class FakePgController {

    private static final long HOLD_MILLIS = 10_000;

    private final FakePgStore fakePgStore;

    public FakePgController(FakePgStore fakePgStore) {
        this.fakePgStore = fakePgStore;
    }

    @PostMapping("/pg/payments")
    public ResponseEntity<ApproveResponse> approve(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                   @RequestBody ApproveRequest approveRequest) throws InterruptedException {
        fakePgStore.countApproveCall();
        if (fakePgStore.idempotencySupported()) {
            var existing = fakePgStore.payment(idempotencyKey);
            if (existing.isPresent()) {
                return ResponseEntity.ok(ApproveResponse.of(existing.get()));
            }
        }
        ScheduledBehavior next = fakePgStore.nextApproveBehavior();
        return switch (next.behavior()) {
            case APPROVE -> ResponseEntity.ok(approveNow(idempotencyKey, approveRequest));
            case DECLINE -> {
                fakePgStore.savePayment(idempotencyKey, "DECLINED", approveRequest.amount());
                yield ResponseEntity.ok(new ApproveResponse("DECLINED", "INSUFFICIENT_BALANCE"));
            }
            case APPROVE_SLOWLY -> {
                ApproveResponse response = approveNow(idempotencyKey, approveRequest);
                Thread.sleep(next.delayMillis());
                yield ResponseEntity.ok(response);
            }
            case ERROR_BEFORE_PROCESSING -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ERROR_AFTER_APPROVE -> {
                approveNow(idempotencyKey, approveRequest);
                yield ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            case APPROVE_LATE -> {
                CompletableFuture.runAsync(() -> approveNow(idempotencyKey, approveRequest),
                        CompletableFuture.delayedExecutor(next.delayMillis(), TimeUnit.MILLISECONDS));
                Thread.sleep(HOLD_MILLIS);
                yield ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
            }
            case NEVER_RECEIVED -> {
                Thread.sleep(HOLD_MILLIS);
                yield ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
            }
        };
    }

    @GetMapping("/pg/payments/{idempotencyKey}")
    public InquiryResponse inquire(@PathVariable String idempotencyKey) {
        return fakePgStore.payment(idempotencyKey)
                .map(payment -> new InquiryResponse(payment.status()))
                .orElse(new InquiryResponse("NOT_FOUND"));
    }

    @PostMapping("/pg/refunds")
    public ResponseEntity<RefundResponse> refund(@RequestBody RefundRequest refundRequest) {
        var existing = fakePgStore.refund(refundRequest.refundKey());
        if (existing.isPresent()) {
            return ResponseEntity.ok(new RefundResponse(existing.get().status()));
        }
        String behavior = fakePgStore.nextRefundBehavior();
        if (behavior.equals("ERROR")) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        String status = behavior.equals("DECLINE") ? "DECLINED" : "COMPLETED";
        fakePgStore.saveRefund(refundRequest.refundKey(), refundRequest.paymentKey(), status, refundRequest.amount());
        return ResponseEntity.ok(new RefundResponse(status));
    }

    @GetMapping("/pg/settlements")
    public SettlementResponse settlement(@RequestParam LocalDate date) {
        return new SettlementResponse(fakePgStore.approvedOn(date), fakePgStore.refundsOn(date));
    }

    @PostMapping("/fake/behaviors/approve")
    public void enqueueApprove(@RequestParam FakePgBehavior behavior, @RequestParam(defaultValue = "0") long delayMillis) {
        fakePgStore.enqueueApprove(behavior, delayMillis);
    }

    @PostMapping("/fake/behaviors/refund")
    public void enqueueRefund(@RequestParam String behavior) {
        fakePgStore.enqueueRefund(behavior);
    }

    @PostMapping("/fake/idempotency")
    public void idempotency(@RequestParam boolean supported) {
        fakePgStore.setIdempotencySupported(supported);
    }

    // 실패로 닫힌 회차를 대행사가 아주 늦게 승인한 경우를 만든다
    @PostMapping("/fake/payments/{idempotencyKey}/approve")
    public void forceApprove(@PathVariable String idempotencyKey, @RequestParam long amount) {
        fakePgStore.savePayment(idempotencyKey, "APPROVED", amount);
    }

    @PostMapping("/fake/refunds/{refundKey}/status")
    public void forceRefundStatus(@PathVariable String refundKey, @RequestParam String status) {
        RefundRecord refund = fakePgStore.refund(refundKey).orElseThrow();
        fakePgStore.saveRefund(refundKey, refund.paymentKey(), status, refund.amount());
    }

    @PostMapping("/fake/reset")
    public void reset() {
        fakePgStore.reset();
    }

    @GetMapping("/fake/stats")
    public StatsResponse stats() {
        return new StatsResponse(fakePgStore.approveCalls());
    }

    private ApproveResponse approveNow(String idempotencyKey, ApproveRequest approveRequest) {
        fakePgStore.savePayment(idempotencyKey, "APPROVED", approveRequest.amount());
        return new ApproveResponse("APPROVED", null);
    }

    public record ApproveRequest(long amount, String method) {
    }

    public record ApproveResponse(String status, String declineCode) {

        static ApproveResponse of(PaymentRecord payment) {
            return new ApproveResponse(payment.status(), payment.status().equals("DECLINED") ? "INSUFFICIENT_BALANCE" : null);
        }
    }

    public record InquiryResponse(String status) {
    }

    public record RefundRequest(String refundKey, String paymentKey, long amount) {
    }

    public record RefundResponse(String status) {
    }

    public record SettlementResponse(List<PaymentRecord> approvals, List<RefundRecord> refunds) {
    }

    public record StatsResponse(int approveCalls) {
    }
}
