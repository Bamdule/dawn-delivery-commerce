package io.github.bamdule.dawn.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FulfillmentEventTest {

    @Test
    void 품목_이벤트의_키는_주문번호이고_재고_조정의_키는_상품번호다() {
        FulfillmentEvent unfillable = new FulfillmentEvent(UUID.randomUUID(), FulfillmentEventType.UNFILLABLE,
                "20261028-000123", LocalDateTime.of(2026, 10, 28, 23, 0), null,
                List.of(new OrderEvent.OrderLine(1001L, 1)), null, null, null, Instant.now());
        FulfillmentEvent stockChanged = new FulfillmentEvent(UUID.randomUUID(), FulfillmentEventType.STOCK_CHANGED,
                null, null, null, null, 7L, 1001L, -2, Instant.now());

        assertThat(unfillable.partitionKey()).isEqualTo("20261028-000123");
        assertThat(stockChanged.partitionKey()).isEqualTo("1001");
        assertThat(stockChanged.lines()).isEmpty();
    }

    @Test
    void 못_채운_품목_이벤트는_마감_시각이_있어야_한다() {
        assertThatThrownBy(() -> new FulfillmentEvent(UUID.randomUUID(), FulfillmentEventType.UNFILLABLE,
                "20261028-000123", null, null, List.of(), null, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
