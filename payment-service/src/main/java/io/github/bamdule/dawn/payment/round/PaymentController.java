package io.github.bamdule.dawn.payment.round;

import io.github.bamdule.dawn.payment.PaymentStatus;
import io.github.bamdule.dawn.payment.round.PaymentApproval.ApproveCommand;
import io.github.bamdule.dawn.payment.round.PaymentApproval.ApproveResult;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final PaymentApproval paymentApproval;
    private final PaymentRounds paymentRounds;
    private final PaymentRepository paymentRepository;

    public PaymentController(PaymentApproval paymentApproval, PaymentRounds paymentRounds,
                             PaymentRepository paymentRepository) {
        this.paymentApproval = paymentApproval;
        this.paymentRounds = paymentRounds;
        this.paymentRepository = paymentRepository;
    }

    @PostMapping("/payments")
    public ApproveResult approve(@RequestBody ApproveCommand approveCommand) {
        return paymentApproval.approve(approveCommand);
    }

    @PostMapping("/payments/{orderNo}/rounds/{paymentRound}/seal")
    public SealResponse seal(@PathVariable String orderNo, @PathVariable int paymentRound) {
        return new SealResponse(paymentRounds.seal(orderNo, paymentRound));
    }

    @GetMapping("/payments/{orderNo}")
    public RoundsResponse rounds(@PathVariable String orderNo) {
        return new RoundsResponse(paymentRepository.findByOrderNo(orderNo).stream()
                .map(row -> new RoundView(row.paymentRound(), row.status(), row.amount()))
                .toList());
    }

    // 사람이 대행사에 확인한 뒤 UNKNOWN 을 APPROVED 나 FAILED 로 확정한다
    @PostMapping("/admin/payments/{orderNo}/rounds/{paymentRound}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable String orderNo, @PathVariable int paymentRound,
                                        @RequestBody ResolveRequest resolveRequest) {
        if (resolveRequest.status() != PaymentStatus.APPROVED && resolveRequest.status() != PaymentStatus.FAILED) {
            return ResponseEntity.badRequest().build();
        }
        PaymentRow paymentRow = paymentRepository.findRound(orderNo, paymentRound).orElseThrow();
        return paymentRounds.resolveUnknown(paymentRow, resolveRequest.status())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(409).build();
    }

    public record SealResponse(SealOutcome outcome) {
    }

    public record RoundsResponse(List<RoundView> rounds) {
    }

    public record RoundView(int paymentRound, PaymentStatus status, long amount) {
    }

    public record ResolveRequest(PaymentStatus status) {
    }
}
