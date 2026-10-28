package io.github.bamdule.dawn.inventory.reconciliation;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpFulfillmentStockClient implements FulfillmentStockClient {

    private final RestClient restClient;

    public HttpFulfillmentStockClient(RestClient.Builder restClientBuilder,
                                      @Value("${dawn.fulfillment.base-url:http://localhost:8084}") String baseUrl) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = restClientBuilder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public List<StockSummary> summary(List<Long> productIds) {
        return Arrays.asList(restClient.post()
                .uri("/stocks/summary")
                .body(new SummaryRequest(productIds))
                .retrieve()
                .body(StockSummary[].class));
    }

    record SummaryRequest(List<Long> productIds) {
    }
}
