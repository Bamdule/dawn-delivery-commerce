package io.github.bamdule.dawn.payment.pg;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class HttpPgClient implements PgClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final int CONFLICT = 409;

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
            if (approveResponse == null) {
                return PgApproveResult.unknown(UNKNOWN_EMPTY_RESPONSE);
            }
            if ("APPROVED".equals(approveResponse.status())) {
                return PgApproveResult.approved();
            }
            return PgApproveResult.declined(approveResponse.declineCode());
        } catch (HttpClientErrorException clientErrorException) {
            // 409 는 같은 키의 요청이 처리 중이라는 뜻이라 모름이다. 그 밖의 4xx 는 요청을 받지 않은 것이라 확실한 거절이다
            if (clientErrorException.getStatusCode().value() == CONFLICT) {
                return PgApproveResult.unknown(UNKNOWN_IN_PROGRESS);
            }
            return PgApproveResult.declined(REJECTED_BY_PG + clientErrorException.getStatusCode().value());
        } catch (HttpServerErrorException serverErrorException) {
            return PgApproveResult.unknown(UNKNOWN_SERVER_ERROR);
        } catch (ResourceAccessException resourceAccessException) {
            return notSent(resourceAccessException) ? PgApproveResult.notSent() : PgApproveResult.unknown(UNKNOWN_TIMEOUT);
        }
    }

    // 404 는 요청 없음, 그 밖의 4xx 와 빈 응답은 모름이다. 모름은 10분이 지나면 사람이 확정한다
    @Override
    public String inquire(String idempotencyKey) {
        try {
            InquiryResponse inquiryResponse = restClient.get().uri("/pg/payments/{key}", idempotencyKey).retrieve()
                    .body(InquiryResponse.class);
            return inquiryResponse == null || inquiryResponse.status() == null ? INQUIRY_UNKNOWN : inquiryResponse.status();
        } catch (HttpClientErrorException.NotFound notFound) {
            return INQUIRY_NOT_FOUND;
        } catch (HttpClientErrorException clientErrorException) {
            return INQUIRY_UNKNOWN;
        }
    }

    @Override
    public String refund(String refundKey, String paymentKey, long amount) {
        try {
            RefundResponse refundResponse = restClient.post()
                    .uri("/pg/refunds")
                    .body(new RefundRequest(refundKey, paymentKey, amount))
                    .retrieve()
                    .body(RefundResponse.class);
            if (refundResponse == null || refundResponse.status() == null) {
                return "UNKNOWN";
            }
            return "COMPLETED".equals(refundResponse.status()) ? "COMPLETED" : "DECLINED";
        } catch (HttpClientErrorException clientErrorException) {
            // 대행사가 이 환불을 받아들이지 않았다(거래 없음 등). 다시 보내도 같으므로 실패로 남기고 사람이 확정한다
            return "DECLINED";
        } catch (HttpServerErrorException | ResourceAccessException exception) {
            return "UNKNOWN";
        }
    }

    // 연결을 맺지 못했으면 요청 바이트가 나가지 않았다. 연결 뒤의 끊김이나 응답 대기 시간 초과는 처리됐을 수 있어 모름이다
    private static boolean notSent(ResourceAccessException resourceAccessException) {
        for (Throwable cause = resourceAccessException.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
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
