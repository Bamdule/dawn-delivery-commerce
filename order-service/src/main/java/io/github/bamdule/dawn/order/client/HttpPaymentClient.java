package io.github.bamdule.dawn.order.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

// 승인은 5초를 기다린다. 결제 서비스는 대행사를 한 번만 3초까지 부르므로 그 안에 답한다 (docs/design/01 "시간 예산")
@Component
public class HttpPaymentClient implements PaymentClient {

    private final RestClient approveClient;
    private final RestClient restClient;

    public HttpPaymentClient(RestClient.Builder restClientBuilder,
                             @Value("${dawn.payment.base-url:http://localhost:8083}") String baseUrl,
                             @Value("${dawn.payment.approve-timeout:5s}") Duration approveTimeout,
                             @Value("${dawn.payment.timeout:2s}") Duration timeout) {
        this.approveClient = HttpClients.create(restClientBuilder, baseUrl, approveTimeout);
        this.restClient = HttpClients.create(restClientBuilder, baseUrl, timeout);
    }

    @Override
    public ApproveResult approve(String orderNo, int paymentRound, long amount, String paymentMethod) {
        return approveClient.post()
                .uri("/payments")
                .body(new ApproveRequest(orderNo, paymentRound, amount, paymentMethod))
                .retrieve()
                .body(ApproveResult.class);
    }

    @Override
    public SealOutcome seal(String orderNo, int paymentRound) {
        return restClient.post()
                .uri("/payments/{orderNo}/rounds/{round}/seal", orderNo, paymentRound)
                .retrieve()
                .body(SealResponse.class)
                .outcome();
    }

    @Override
    public Optional<String> roundStatus(String orderNo, int paymentRound) {
        return restClient.get().uri("/payments/{orderNo}", orderNo).retrieve().body(RoundsResponse.class).rounds().stream()
                .filter(round -> round.paymentRound() == paymentRound)
                .map(RoundView::status)
                .findFirst();
    }

    @Override
    public RefundAcceptResult acceptRefund(String refundKey, String orderNo, int paymentRound, long amount) {
        AcceptResponse acceptResponse = restClient.post()
                .uri("/refunds")
                .body(new RefundRequest(refundKey, orderNo, paymentRound, amount))
                .retrieve()
                .body(AcceptResponse.class);
        return new RefundAcceptResult("ACCEPTED".equals(acceptResponse.outcome()), acceptResponse.status());
    }

    @Override
    public Optional<String> refundStatus(String refundKey) {
        try {
            return Optional.of(restClient.get().uri("/refunds/{refundKey}", refundKey).retrieve().body(RefundView.class).status());
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        }
    }

    record ApproveRequest(String orderNo, int paymentRound, long amount, String method) {
    }

    record SealResponse(SealOutcome outcome) {
    }

    record RoundsResponse(List<RoundView> rounds) {
    }

    record RoundView(int paymentRound, String status, long amount) {
    }

    record RefundRequest(String refundKey, String orderNo, int paymentRound, long amount) {
    }

    record AcceptResponse(String outcome, String status) {
    }

    record RefundView(String refundKey, String status, long amount) {
    }
}
