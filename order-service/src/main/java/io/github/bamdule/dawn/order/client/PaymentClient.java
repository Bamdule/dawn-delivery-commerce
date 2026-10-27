package io.github.bamdule.dawn.order.client;

import java.util.Optional;

// 결제 서비스 호출. 응답을 받지 못하면 RestClientException 을 던진다
public interface PaymentClient {

    // 결제 서비스가 정한 값. 동시 호출 한도나 회로 차단기 때문에 대행사를 부르지 않은 거절이다
    String NOT_CALLED = "NOT_CALLED";

    ApproveResult approve(String orderNo, int paymentRound, long amount, String paymentMethod);

    SealOutcome seal(String orderNo, int paymentRound);

    // 그 회차가 아직 열리지 않았으면 비어 있다
    Optional<String> roundStatus(String orderNo, int paymentRound);

    RefundAcceptResult acceptRefund(String refundKey, String orderNo, int paymentRound, long amount);

    // 결제 서비스에 그 환불이 없으면 비어 있다
    Optional<String> refundStatus(String refundKey);

    record ApproveResult(ApproveOutcome outcome, int paymentRound, String failureCode) {
    }

    // accepted 가 참이면 status 는 결제 쪽 환불의 현재 상태, 거짓이면 거절 사유다
    record RefundAcceptResult(boolean accepted, String status) {
    }
}
