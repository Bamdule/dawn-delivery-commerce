package io.github.bamdule.dawn.payment.round;

import io.github.bamdule.dawn.event.PaymentEventType;
import io.github.bamdule.dawn.payment.PaymentStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 회차를 열고, 봉인하고, 결과를 기록한다. 셋 다 주문 행을 먼저 잠가 한 줄로 선다 (docs/design/04)
@Service
public class PaymentRounds {

    private final PaymentRepository paymentRepository;
    private final PaymentEventPublisher paymentEventPublisher;

    public PaymentRounds(PaymentRepository paymentRepository, PaymentEventPublisher paymentEventPublisher) {
        this.paymentRepository = paymentRepository;
        this.paymentEventPublisher = paymentEventPublisher;
    }

    public static String idempotencyKey(String orderNo, int paymentRound) {
        return "order-" + orderNo + "-payment-" + paymentRound;
    }

    // 새 회차는 기존 회차보다 큰 번호이고, 가장 최근 회차가 FAILED 나 EXPIRED 일 때만 연다
    @Transactional
    public OpenResult open(String orderNo, int paymentRound, long amount) {
        paymentRepository.lockOrder(orderNo);
        List<PaymentRow> rounds = paymentRepository.findByOrderNo(orderNo);
        for (PaymentRow existing : rounds) {
            if (existing.paymentRound() == paymentRound) {
                return existing.status() == PaymentStatus.EXPIRED
                        ? new OpenResult(OpenOutcome.SEALED, existing)
                        : new OpenResult(OpenOutcome.EXISTING, existing);
            }
        }
        if (!rounds.isEmpty()) {
            PaymentRow latest = rounds.getLast();
            if (paymentRound < latest.paymentRound()) {
                return new OpenResult(OpenOutcome.STALE, latest);
            }
            if (latest.status() == PaymentStatus.APPROVED) {
                return new OpenResult(OpenOutcome.ALREADY_PAID, latest);
            }
            if (latest.status() == PaymentStatus.REQUESTED || latest.status() == PaymentStatus.UNKNOWN) {
                return new OpenResult(OpenOutcome.IN_PROGRESS, latest);
            }
        }
        paymentRepository.insert(orderNo, paymentRound, idempotencyKey(orderNo, paymentRound), amount, PaymentStatus.REQUESTED);
        return new OpenResult(OpenOutcome.OPENED, paymentRepository.findRound(orderNo, paymentRound).orElseThrow());
    }

    // 아직 열리지 않은 회차 자리에 EXPIRED 를 넣어, 늦게 온 승인 요청이 그 회차를 열지 못하게 한다 (docs/design/02 "회차 봉인")
    @Transactional
    public SealOutcome seal(String orderNo, int paymentRound) {
        paymentRepository.lockOrder(orderNo);
        return paymentRepository.findRound(orderNo, paymentRound)
                .map(existing -> SealOutcome.valueOf(existing.status().name()))
                .orElseGet(() -> {
                    paymentRepository.insert(orderNo, paymentRound, idempotencyKey(orderNo, paymentRound), 0, PaymentStatus.EXPIRED);
                    return SealOutcome.SEALED;
                });
    }

    @Transactional
    public boolean recordApproved(PaymentRow paymentRow) {
        return record(paymentRow, PaymentStatus.REQUESTED, PaymentStatus.APPROVED, null, PaymentEventType.APPROVED);
    }

    @Transactional
    public boolean recordFailed(PaymentRow paymentRow, String failureCode) {
        return record(paymentRow, PaymentStatus.REQUESTED, PaymentStatus.FAILED, failureCode, PaymentEventType.FAILED);
    }

    // 사람이 대행사에 확인한 뒤 UNKNOWN 을 확정한다. 일반 전이처럼 아웃박스를 거친다
    @Transactional
    public boolean resolveUnknown(PaymentRow paymentRow, PaymentStatus resolved) {
        PaymentEventType eventType = resolved == PaymentStatus.APPROVED ? PaymentEventType.APPROVED : PaymentEventType.FAILED;
        return record(paymentRow, PaymentStatus.UNKNOWN, resolved, "MANUAL", eventType);
    }

    @Transactional
    public boolean markUnknown(PaymentRow paymentRow) {
        paymentRepository.lockOrder(paymentRow.orderNo());
        return paymentRepository.transition(paymentRow.id(), PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN, paymentRow.failureCode());
    }

    // 정산 대사만 FAILED 를 APPROVED 로 바꾼다. 대행사의 정산이 사실이기 때문이다
    @Transactional
    public boolean correctToApproved(PaymentRow paymentRow) {
        paymentRepository.lockOrder(paymentRow.orderNo());
        if (!paymentRepository.transition(paymentRow.id(), PaymentStatus.FAILED, PaymentStatus.APPROVED, "SETTLEMENT")) {
            return false;
        }
        paymentEventPublisher.roundResult(PaymentEventType.APPROVED, paymentRow, true);
        return true;
    }

    private boolean record(PaymentRow paymentRow, PaymentStatus from, PaymentStatus to, String failureCode,
                           PaymentEventType eventType) {
        paymentRepository.lockOrder(paymentRow.orderNo());
        if (!paymentRepository.transition(paymentRow.id(), from, to, failureCode)) {
            return false;
        }
        paymentEventPublisher.roundResult(eventType, paymentRow, false);
        return true;
    }

    public record OpenResult(OpenOutcome outcome, PaymentRow paymentRow) {
    }
}
