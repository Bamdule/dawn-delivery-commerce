package io.github.bamdule.dawn.order.refund;

import io.github.bamdule.dawn.order.client.PaymentClient;
import io.github.bamdule.dawn.order.client.PaymentClient.RefundAcceptResult;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

// 주문 서비스 환불 배치. REQUESTED 는 결제 서비스가 접수할 때까지 같은 키로 보내고,
// 1시간 넘은 ACCEPTED 는 결제 서비스에 상태를 물어 맞춘다. 결과 이벤트가 유실돼도 수렴한다 (docs/design/04 "환불")
@Component
public class RefundSender {

    private static final Logger log = LoggerFactory.getLogger(RefundSender.class);
    private static final int BATCH_LIMIT = 500;
    private static final long INITIAL_BACKOFF_MILLIS = 1_000;
    private static final long MAX_BACKOFF_MILLIS = 60_000;
    private static final long ACCEPTED_RECHECK_MILLIS = 3_600_000;

    private final RefundRequestRepository refundRequestRepository;
    private final RefundRequests refundRequests;
    private final PaymentClient paymentClient;

    public RefundSender(RefundRequestRepository refundRequestRepository, RefundRequests refundRequests,
                        PaymentClient paymentClient) {
        this.refundRequestRepository = refundRequestRepository;
        this.refundRequests = refundRequests;
        this.paymentClient = paymentClient;
    }

    public void run() {
        for (RefundRequestRow refundRequestRow : refundRequestRepository.findDue(BATCH_LIMIT)) {
            if (refundRequestRow.overdue()) {
                log.error("환불 요청 지연 (refundKey={}, status={})", refundRequestRow.refundKey(), refundRequestRow.status());
            }
            try {
                if (refundRequestRow.status() == RefundStatus.REQUESTED) {
                    send(refundRequestRow);
                } else {
                    recheck(refundRequestRow);
                }
            } catch (RestClientException restClientException) {
                refundRequestRepository.postpone(refundRequestRow.refundKey(), backoffMillis(refundRequestRow.attemptCount()));
            } catch (RuntimeException runtimeException) {
                // 한 건의 예상 못 한 오류가 나머지 환불을 막지 않게 그 건만 미루고 넘어간다
                log.error("환불 요청 처리 오류 (refundKey={})", refundRequestRow.refundKey(), runtimeException);
                refundRequestRepository.postpone(refundRequestRow.refundKey(), MAX_BACKOFF_MILLIS);
            }
        }
    }

    // 접수 응답에는 결제 쪽의 현재 상태가 실린다. 응답이 유실됐다 다시 보낸 경우 이미 완료일 수 있다
    private void send(RefundRequestRow refundRequestRow) {
        String refundKey = refundRequestRow.refundKey();
        RefundAcceptResult refundAcceptResult = paymentClient.acceptRefund(refundKey, refundRequestRow.orderNo(),
                refundRequestRow.paymentRound(), refundRequestRow.amount());
        if (!refundAcceptResult.accepted()) {
            refundRequests.reject(refundKey, refundAcceptResult.status());
            return;
        }
        if (!applyFinalStatus(refundKey, refundAcceptResult.status())) {
            refundRequestRepository.markAccepted(refundKey);
        }
    }

    private void recheck(RefundRequestRow refundRequestRow) {
        String refundKey = refundRequestRow.refundKey();
        Optional<String> paymentStatus = paymentClient.refundStatus(refundKey);
        if (paymentStatus.isEmpty()) {
            log.error("접수된 환불이 결제 서비스에 없음 (refundKey={})", refundKey);
        }
        if (paymentStatus.isEmpty() || !applyFinalStatus(refundKey, paymentStatus.get())) {
            refundRequestRepository.postpone(refundKey, ACCEPTED_RECHECK_MILLIS);
        }
    }

    private boolean applyFinalStatus(String refundKey, String paymentStatus) {
        return switch (paymentStatus) {
            case "COMPLETED" -> {
                refundRequests.complete(refundKey);
                yield true;
            }
            case "FAILED" -> {
                refundRequests.fail(refundKey);
                yield true;
            }
            default -> false;
        };
    }

    private static long backoffMillis(int attemptCount) {
        long backoff = Math.min(MAX_BACKOFF_MILLIS, INITIAL_BACKOFF_MILLIS << Math.min(attemptCount, 16));
        return backoff + ThreadLocalRandom.current().nextLong(backoff / 2 + 1);
    }
}
