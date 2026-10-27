package io.github.bamdule.dawn.order.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class HttpClients {

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    private HttpClients() {
    }

    static RestClient create(RestClient.Builder restClientBuilder, String baseUrl, Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(readTimeout);
        return restClientBuilder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
