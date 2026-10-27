package io.github.bamdule.dawn.order.client;

import io.github.bamdule.dawn.event.OrderEvent.OrderLine;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

// 예약과 결제 시작 표시는 500ms 에 한 번 다시 보낸다. 예약은 (주문번호, 상품) 유니크라 다시 보내도 한 벌이다 (docs/design/01 "시간 예산")
@Component
public class HttpInventoryClient implements InventoryClient {

    private final RestClient restClient;

    public HttpInventoryClient(RestClient.Builder restClientBuilder,
                               @Value("${dawn.inventory.base-url:http://localhost:8082}") String baseUrl,
                               @Value("${dawn.inventory.timeout:500ms}") Duration timeout) {
        this.restClient = HttpClients.create(restClientBuilder, baseUrl, timeout);
    }

    @Override
    public List<Long> soldOutProductIds() {
        return restClient.get().uri("/stocks/sold-out").retrieve().body(SoldOutResponse.class).productIds();
    }

    @Override
    public ReserveResult reserve(String orderNo, String regionCode, List<OrderLine> lines) {
        try {
            ReserveResponse reserveResponse = retryOnce(() -> restClient.post()
                    .uri("/reservations")
                    .body(new ReserveRequest(orderNo, regionCode, lines))
                    .retrieve()
                    .body(ReserveResponse.class));
            return ReserveResult.reserved(reserveResponse.expiresAt());
        } catch (HttpClientErrorException.Conflict conflict) {
            return ReserveResult.soldOut(conflict.getResponseBodyAs(OutOfStockResponse.class).productId());
        }
    }

    @Override
    public MarkPayingResult markPaying(String orderNo, int paymentRound) {
        try {
            MarkPayingResponse markPayingResponse = retryOnce(() -> restClient.post()
                    .uri("/reservations/{orderNo}/paying", orderNo)
                    .body(new MarkPayingRequest(paymentRound))
                    .retrieve()
                    .body(MarkPayingResponse.class));
            return new MarkPayingResult(true, markPayingResponse.marked(), markPayingResponse.currentStatus(),
                    markPayingResponse.currentRound(), markPayingResponse.expiresAt(), markPayingResponse.expired());
        } catch (HttpClientErrorException.NotFound notFound) {
            return MarkPayingResult.notFound();
        }
    }

    @Override
    public boolean revertPaying(String orderNo, int paymentRound) {
        return restClient.post()
                .uri("/reservations/{orderNo}/paying/{round}/revert", orderNo, paymentRound)
                .retrieve()
                .body(RevertResponse.class)
                .reverted();
    }

    @Override
    public Optional<ReservationState> state(String orderNo) {
        try {
            return Optional.of(restClient.get().uri("/reservations/{orderNo}", orderNo).retrieve().body(ReservationState.class));
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        }
    }

    private static <T> T retryOnce(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException firstFailure) {
            return call.get();
        }
    }

    record SoldOutResponse(List<Long> productIds) {
    }

    record ReserveRequest(String orderNo, String regionCode, List<OrderLine> lines) {
    }

    record ReserveResponse(LocalDateTime expiresAt) {
    }

    record OutOfStockResponse(long productId) {
    }

    record MarkPayingRequest(int paymentRound) {
    }

    record MarkPayingResponse(boolean marked, ReservationStatus currentStatus, Integer currentRound,
                              LocalDateTime expiresAt, boolean expired) {
    }

    record RevertResponse(boolean reverted) {
    }
}
