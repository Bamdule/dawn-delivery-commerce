package io.github.bamdule.dawn.order.refund;

import io.github.bamdule.dawn.order.store.OrderRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 환불 요청의 상태 전이를 모은다. 누적액은 완료로 바뀐 1행일 때만 같은 트랜잭션에서 더한다 (docs/design/04 "환불")
@Service
public class RefundRequests {

    private static final Logger log = LoggerFactory.getLogger(RefundRequests.class);

    private final RefundRequestRepository refundRequestRepository;
    private final OrderRepository orderRepository;

    public RefundRequests(RefundRequestRepository refundRequestRepository, OrderRepository orderRepository) {
        this.refundRequestRepository = refundRequestRepository;
        this.orderRepository = orderRepository;
    }

    public void requestOrphan(String orderNo, int paymentRound, long amount) {
        if (refundRequestRepository.insert(orderNo, RefundKeys.orphan(orderNo, paymentRound), paymentRound, amount,
                RefundReason.ORPHAN_APPROVAL)) {
            log.error("주문이 쓰지 않은 승인 환불 요청 (orderNo={}, paymentRound={}, amount={})", orderNo, paymentRound, amount);
        }
    }

    // 접수 응답이 유실되면 REQUESTED 에서, 사람이 대행사에서 처리해 확정하면 FAILED 에서 완료가 온다
    @Transactional
    public boolean complete(String refundKey) {
        RefundRequestRow refundRequestRow = refundRequestRepository.findByKey(refundKey).orElse(null);
        if (refundRequestRow == null || !refundRequestRepository.transition(refundKey,
                List.of(RefundStatus.REQUESTED, RefundStatus.ACCEPTED, RefundStatus.FAILED), RefundStatus.COMPLETED)) {
            return false;
        }
        if (refundRequestRow.reason().countsTowardOrder()) {
            orderRepository.addRefunded(refundRequestRow.orderNo(), refundRequestRow.amount());
        }
        return true;
    }

    public boolean fail(String refundKey) {
        boolean failed = refundRequestRepository.transition(refundKey,
                List.of(RefundStatus.REQUESTED, RefundStatus.ACCEPTED), RefundStatus.FAILED);
        if (failed) {
            log.error("환불 실패, 사람 확인 필요 (refundKey={})", refundKey);
        }
        return failed;
    }

    // 정산 대사가 완료를 실패로 바로잡았다. 누적액을 되돌리고 사람이 새 키로 다시 요청한다
    @Transactional
    public boolean reverseCompleted(String refundKey) {
        RefundRequestRow refundRequestRow = refundRequestRepository.findByKey(refundKey).orElse(null);
        if (refundRequestRow == null) {
            return false;
        }
        if (!refundRequestRepository.transition(refundKey, List.of(RefundStatus.COMPLETED), RefundStatus.FAILED)) {
            return fail(refundKey);
        }
        if (refundRequestRow.reason().countsTowardOrder()) {
            orderRepository.addRefunded(refundRequestRow.orderNo(), -refundRequestRow.amount());
        }
        log.error("정산 대사로 환불 완료 취소, 재요청 필요 (refundKey={}, amount={})", refundKey, refundRequestRow.amount());
        return true;
    }

    public boolean reject(String refundKey, String rejectReason) {
        boolean rejected = refundRequestRepository.transition(refundKey, List.of(RefundStatus.REQUESTED), RefundStatus.REJECTED);
        if (rejected) {
            log.error("결제 서비스의 환불 접수 거절 (refundKey={}, reason={})", refundKey, rejectReason);
        }
        return rejected;
    }

    // 실패나 접수 거절로 끝난 환불을 사람이 금액을 확인해 다시 요청한다. 원래 요청 번호로 키를 만들어 두 번 눌러도 한 건이다
    public Optional<String> reissue(String refundKey, Long correctedAmount) {
        RefundRequestRow original = refundRequestRepository.findByKey(refundKey).orElse(null);
        if (original == null || (original.status() != RefundStatus.FAILED && original.status() != RefundStatus.REJECTED)) {
            return Optional.empty();
        }
        String reissueKey = RefundKeys.reissue(original.orderNo(), original.id());
        long amount = correctedAmount != null ? correctedAmount : original.amount();
        refundRequestRepository.insert(original.orderNo(), reissueKey, original.paymentRound(), amount, original.reason());
        return Optional.of(reissueKey);
    }
}
