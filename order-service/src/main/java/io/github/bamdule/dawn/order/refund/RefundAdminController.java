package io.github.bamdule.dawn.order.refund;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 실패로 끝났거나 접수가 거절된 환불을 사람이 금액을 확인해 새 키로 다시 요청한다 (docs/design/01 "수렴 책임")
@RestController
public class RefundAdminController {

    private final RefundRequests refundRequests;

    public RefundAdminController(RefundRequests refundRequests) {
        this.refundRequests = refundRequests;
    }

    @PostMapping("/admin/refunds/{refundKey}/reissue")
    public ResponseEntity<ReissueResponse> reissue(@PathVariable String refundKey,
                                                   @RequestBody(required = false) ReissueRequest reissueRequest) {
        Long correctedAmount = reissueRequest == null ? null : reissueRequest.amount();
        return refundRequests.reissue(refundKey, correctedAmount)
                .map(reissueKey -> ResponseEntity.ok(new ReissueResponse(reissueKey)))
                .orElse(ResponseEntity.status(409).build());
    }

    // 금액을 비우면 원래 요청 금액으로 다시 요청한다
    public record ReissueRequest(Long amount) {
    }

    public record ReissueResponse(String refundKey) {
    }
}
