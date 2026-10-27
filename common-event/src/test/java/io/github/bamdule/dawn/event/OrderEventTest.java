package io.github.bamdule.dawn.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderEventTest {

    @Test
    void 파티션_키는_주문번호다() {
        OrderEvent event = paidEvent(new ArrayList<>());

        assertThat(event.partitionKey()).isEqualTo("20261028-000123");
    }

    @Test
    void 생성_후_원본_목록을_바꿔도_이벤트는_바뀌지_않는다() {
        List<OrderEvent.OrderLine> lines = new ArrayList<>();
        lines.add(new OrderEvent.OrderLine(1001L, "r-1", 2));
        OrderEvent event = paidEvent(lines);

        lines.add(new OrderEvent.OrderLine(1002L, "r-2", 1));

        assertThat(event.lines()).hasSize(1);
    }

    @Test
    void 주문번호가_없으면_만들_수_없다() {
        assertThatThrownBy(() -> new OrderEvent(
                UUID.randomUUID(), OrderEventType.PAID, null, 1L, 45_000L, List.of(), Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    private OrderEvent paidEvent(List<OrderEvent.OrderLine> lines) {
        return new OrderEvent(
                UUID.randomUUID(), OrderEventType.PAID, "20261028-000123", 1L, 45_000L, lines, Instant.now());
    }
}
