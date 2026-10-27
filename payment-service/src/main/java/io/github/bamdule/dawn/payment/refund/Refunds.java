package io.github.bamdule.dawn.payment.refund;

import io.github.bamdule.dawn.event.PaymentEventType;
import io.github.bamdule.dawn.payment.PaymentStatus;
import io.github.bamdule.dawn.payment.refund.RefundRepository.RefundRow;
import io.github.bamdule.dawn.payment.round.PaymentEventPublisher;
import io.github.bamdule.dawn.payment.round.PaymentRepository;
import io.github.bamdule.dawn.payment.round.PaymentRow;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 환불 키는 주문 서비스가 정한다. 결제 서비스는 접수한 환불을 대행사와 확정할 때까지 맡는다 (docs/design/04)
@Service
public class Refunds {

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventPublisher paymentEventPublisher;

    public Refunds(RefundRepository refundRepository, PaymentRepository paymentRepository,
                   PaymentEventPublisher paymentEventPublisher) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.paymentEventPublisher = paymentEventPublisher;
    }

    // 같은 키를 먼저 본다. 상한을 먼저 보면, 접수 응답이 유실돼 다시 온 요청이 자기 금액과 더해져 거절된다
    @Transactional
    public AcceptResult accept(RefundCommand refundCommand) {
        paymentRepository.lockOrder(refundCommand.orderNo());
        var existing = refundRepository.findByKey(refundCommand.refundKey());
        if (existing.isPresent()) {
            return new AcceptResult(AcceptOutcome.ACCEPTED, existing.get().status());
        }
        PaymentRow paymentRow = paymentRepository.findRound(refundCommand.orderNo(), refundCommand.paymentRound()).orElse(null);
        if (paymentRow == null || paymentRow.status() != PaymentStatus.APPROVED) {
            return new AcceptResult(AcceptOutcome.REJECTED, "NOT_APPROVED");
        }
        if (refundRepository.sumNotFailed(paymentRow.id()) + refundCommand.amount() > paymentRow.amount()) {
            return new AcceptResult(AcceptOutcome.REJECTED, "EXCEEDS_APPROVED_AMOUNT");
        }
        refundRepository.insertRequested(paymentRow.id(), refundCommand.refundKey(), refundCommand.amount());
        return new AcceptResult(AcceptOutcome.ACCEPTED, "REQUESTED");
    }

    @Transactional
    public boolean complete(RefundRow refundRow, List<String> from, boolean settlementCorrection) {
        return finish(refundRow, from, "COMPLETED", PaymentEventType.REFUNDED, settlementCorrection);
    }

    @Transactional
    public boolean fail(RefundRow refundRow, List<String> from, boolean settlementCorrection) {
        return finish(refundRow, from, "FAILED", PaymentEventType.REFUND_FAILED, settlementCorrection);
    }

    @Transactional
    public boolean markUnknown(RefundRow refundRow) {
        paymentRepository.lockOrder(refundRow.orderNo());
        return refundRepository.transition(refundRow.id(), List.of("REQUESTED"), "UNKNOWN");
    }

    private boolean finish(RefundRow refundRow, List<String> from, String to, PaymentEventType eventType,
                           boolean settlementCorrection) {
        paymentRepository.lockOrder(refundRow.orderNo());
        if (!refundRepository.transition(refundRow.id(), from, to)) {
            return false;
        }
        paymentEventPublisher.refundResult(eventType, refundRow.orderNo(), refundRow.paymentRound(), refundRow.refundKey(),
                refundRow.amount(), settlementCorrection);
        return true;
    }

    public record RefundCommand(String refundKey, String orderNo, int paymentRound, long amount) {
    }

    public enum AcceptOutcome {
        ACCEPTED,
        REJECTED
    }

    // ACCEPTED 면 status 는 결제 쪽 환불의 현재 상태다. 이미 COMPLETED 면 주문 서비스가 바로 완료로 반영한다
    public record AcceptResult(AcceptOutcome outcome, String status) {
    }
}
