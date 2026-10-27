package io.github.bamdule.dawn.payment.pg;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class HttpPgClient implements PgClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

    private final RestClient restClient;
    private final boolean idempotencySupported;

    public HttpPgClient(RestClient.Builder restClientBuilder,
                        @Value("${dawn.pg.base-url:http://localhost:8090}") String baseUrl,
                        @Value("${dawn.pg.timeout:3s}") Duration timeout,
                        @Value("${dawn.pg.idempotency-supported:true}") boolean idempotencySupported) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.idempotencySupported = idempotencySupported;
    }

    // 타임아웃, 연결 끊김, 5xx 는 모두 모름이다. 대행사가 처리한 뒤 오류를 냈을 수 있기 때문이다
    @Override
    public PgApproveResult approve(String idempotencyKey, long amount, String method) {
        try {
            ApproveResponse approveResponse = restClient.post()
                    .uri("/pg/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new ApproveRequest(amount, method))
                    .retrieve()
                    .body(ApproveResponse.class);
            if ("APPROVED".equals(approveResponse.status())) {
                return PgApproveResult.approved();
            }
            return PgApproveResult.declined(approveResponse.declineCode());
        } catch (HttpServerErrorException serverErrorException) {
            return PgApproveResult.unknown(UNKNOWN_SERVER_ERROR);
        } catch (ResourceAccessException resourceAccessException) {
            return PgApproveResult.unknown(UNKNOWN_TIMEOUT);
        }
    }

    @Override
    public String inquire(String idempotencyKey) {
        return restClient.get().uri("/pg/payments/{key}", idempotencyKey).retrieve().body(InquiryResponse.class).status();
    }

    @Override
    public String refund(String refundKey, String paymentKey, long amount) {
        try {
            String status = restClient.post()
                    .uri("/pg/refunds")
                    .body(new RefundRequest(refundKey, paymentKey, amount))
                    .retrieve()
                    .body(RefundResponse.class)
                    .status();
            return "COMPLETED".equals(status) ? "COMPLETED" : "DECLINED";
        } catch (HttpServerErrorException | ResourceAccessException exception) {
            return "UNKNOWN";
        }
    }

    @Override
    public Settlement settlement(LocalDate date) {
        return restClient.get().uri("/pg/settlements?date={date}", date).retrieve().body(Settlement.class);
    }

    @Override
    public boolean idempotencySupported() {
        return idempotencySupported;
    }

    record ApproveRequest(long amount, String method) {
    }

    record ApproveResponse(String status, String declineCode) {
    }

    record InquiryResponse(String status) {
    }

    record RefundRequest(String refundKey, String paymentKey, long amount) {
    }

    record RefundResponse(String status) {
    }
}
