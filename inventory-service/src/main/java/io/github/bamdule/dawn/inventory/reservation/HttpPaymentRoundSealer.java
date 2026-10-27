package io.github.bamdule.dawn.inventory.reservation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpPaymentRoundSealer implements PaymentRoundSealer {

    private final RestClient restClient;

    public HttpPaymentRoundSealer(RestClient.Builder restClientBuilder,
                                  @Value("${dawn.payment.base-url:http://localhost:8083}") String paymentBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(paymentBaseUrl).build();
    }

    @Override
    public SealOutcome seal(String orderNo, int paymentRound) {
        SealResponse sealResponse = restClient.post()
                .uri("/payments/{orderNo}/rounds/{round}/seal", orderNo, paymentRound)
                .retrieve()
                .body(SealResponse.class);
        return sealResponse.outcome();
    }

    record SealResponse(SealOutcome outcome) {
    }
}
