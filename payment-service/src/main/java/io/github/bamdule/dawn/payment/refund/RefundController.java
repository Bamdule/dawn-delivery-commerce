package io.github.bamdule.dawn.payment.refund;

import io.github.bamdule.dawn.payment.refund.RefundRepository.RefundRow;
import io.github.bamdule.dawn.payment.refund.Refunds.AcceptResult;
import io.github.bamdule.dawn.payment.refund.Refunds.RefundCommand;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefundController {

    private final Refunds refunds;
    private final RefundRepository refundRepository;

    public RefundController(Refunds refunds, RefundRepository refundRepository) {
        this.refunds = refunds;
        this.refundRepository = refundRepository;
    }

    @PostMapping("/refunds")
    public AcceptResult accept(@RequestBody RefundCommand refundCommand) {
        return refunds.accept(refundCommand);
    }

    @GetMapping("/refunds/{refundKey}")
    public ResponseEntity<RefundView> status(@PathVariable String refundKey) {
        return refundRepository.findByKey(refundKey)
                .map(refund -> ResponseEntity.ok(new RefundView(refund.refundKey(), refund.status(), refund.amount())))
                .orElse(ResponseEntity.notFound().build());
    }

    // 대행사가 거절했거나 결과를 모르는 환불을 사람이 확인해 확정한다. 대행사에서 직접 처리했으면 COMPLETED 다
    @PostMapping("/admin/refunds/{refundKey}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable String refundKey, @RequestBody ResolveRequest resolveRequest) {
        RefundRow refundRow = refundRepository.findByKey(refundKey).orElseThrow();
        List<String> from = List.of("FAILED", "UNKNOWN");
        boolean changed = switch (resolveRequest.status()) {
            case "COMPLETED" -> refunds.complete(refundRow, from, false);
            case "FAILED" -> refunds.fail(refundRow, List.of("UNKNOWN"), false);
            default -> false;
        };
        return changed ? ResponseEntity.noContent().build() : ResponseEntity.status(409).build();
    }

    public record RefundView(String refundKey, String status, long amount) {
    }

    public record ResolveRequest(String status) {
    }
}
