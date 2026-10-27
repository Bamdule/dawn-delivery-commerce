package io.github.bamdule.dawn.fulfillment.target;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class HttpOrderClient implements OrderClient {

    private final RestClient restClient;

    public HttpOrderClient(RestClient.Builder restClientBuilder,
                           @Value("${dawn.order.base-url:http://localhost:8081}") String baseUrl,
                           @Value("${dawn.order.timeout:5s}") Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public PaidPage paidOrders(LocalDateTime cutoffAt, LocalDateTime cursorPaidAt, Long cursorId, int size) {
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/orders/paid").queryParam("cutoffAt", cutoffAt).queryParam("size", size);
                    if (cursorId != null) {
                        uriBuilder.queryParam("cursorPaidAt", cursorPaidAt).queryParam("cursorId", cursorId);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(PaidPage.class);
    }

    @Override
    public Optional<OrderDetail> detail(String orderNo) {
        try {
            return Optional.ofNullable(restClient.get().uri("/orders/{orderNo}", orderNo).retrieve().body(OrderDetail.class));
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        }
    }
}
