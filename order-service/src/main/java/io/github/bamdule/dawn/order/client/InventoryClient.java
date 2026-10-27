package io.github.bamdule.dawn.order.client;

import io.github.bamdule.dawn.event.OrderEvent.OrderLine;
import java.util.List;
import java.util.Optional;

// 재고 서비스 호출. 응답을 받지 못하면 RestClientException 을 던지고, 부르는 쪽이 결과를 모르는 상태로 다룬다
public interface InventoryClient {

    List<Long> soldOutProductIds();

    ReserveResult reserve(String orderNo, String regionCode, List<OrderLine> lines);

    MarkPayingResult markPaying(String orderNo, int paymentRound);

    boolean revertPaying(String orderNo, int paymentRound);

    Optional<ReservationState> state(String orderNo);
}
