package io.github.bamdule.dawn.fulfillment.target;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.event.CancelReason;
import io.github.bamdule.dawn.event.OrderEvent;
import io.github.bamdule.dawn.event.OrderEventType;
import io.github.bamdule.dawn.fulfillment.FulfillmentIntegrationTest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderEventConsumerTest extends FulfillmentIntegrationTest {

    @Test
    void PAID_는_출고_대상을_대기_수량으로_한_번만_쌓고_배정_회차는_비워_둔다() {
        long product = newProductId();
        String orderNo = newOrderNo();
        OrderEvent paid = paidEvent(orderNo, LocalDateTime.now(), "SEOUL", line(product, 3));

        orderEventConsumer.handle(paid);
        orderEventConsumer.handle(paid);

        assertThat(quantities(orderNo, product)).containsExactly(3, 0, 0, 0, 0);
        assertThat(jdbcClient.sql("SELECT assigned_cutoff_at FROM shipment_target_line WHERE order_no = :orderNo")
                .param("orderNo", orderNo).query(LocalDateTime.class).optional()).isEmpty();
    }

    @Test
    void 고객_취소는_대기_수량을_취소로_옮기고_같은_이벤트가_다시_와도_한_번이다() {
        long product = newProductId();
        String orderNo = newOrderNo();
        orderEventConsumer.handle(paidEvent(orderNo, LocalDateTime.now(), "SEOUL", line(product, 3)));
        OrderEvent cancelled = cancelled(orderNo, CancelReason.CUSTOMER, line(product, 3));

        orderEventConsumer.handle(cancelled);
        orderEventConsumer.handle(cancelled);

        assertThat(quantities(orderNo, product)).containsExactly(0, 0, 0, 0, 3);
        assertThat(targetStatus(orderNo)).isEqualTo("CANCELLED");
    }

    @Test
    void 출고_불가와_관리자_취소로_온_취소는_풀필먼트가_이미_옮겼으므로_넘긴다() {
        long product = newProductId();
        String orderNo = newOrderNo();
        orderEventConsumer.handle(paidEvent(orderNo, LocalDateTime.now(), "SEOUL", line(product, 3)));

        orderEventConsumer.handle(cancelled(orderNo, CancelReason.UNFILLABLE, line(product, 1)));
        orderEventConsumer.handle(cancelled(orderNo, CancelReason.ADMIN, line(product, 1)));

        assertThat(quantities(orderNo, product)).containsExactly(3, 0, 0, 0, 0);
    }

    @Test
    void 모르는_주문의_취소가_먼저_오면_취소_표시만_남고_뒤늦은_PAID_가_되살리지_않는다() {
        long product = newProductId();
        String orderNo = newOrderNo();

        orderEventConsumer.handle(cancelled(orderNo, CancelReason.CUSTOMER, line(product, 1)));
        orderEventConsumer.handle(paidEvent(orderNo, LocalDateTime.now(), "SEOUL", line(product, 1)));

        assertThat(targetStatus(orderNo)).isEqualTo("CANCELLED");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM shipment_target_line WHERE order_no = :orderNo")
                .param("orderNo", orderNo).query(Integer.class).single()).isZero();
    }

    private static OrderEvent cancelled(String orderNo, CancelReason cancelReason, io.github.bamdule.dawn.event.OrderEvent.OrderLine line) {
        return new OrderEvent(UUID.randomUUID(), OrderEventType.CANCELLED, orderNo, 1L, 10_000L, List.of(line), cancelReason,
                null, null, Instant.now());
    }

    private String targetStatus(String orderNo) {
        return jdbcClient.sql("SELECT status FROM shipment_target WHERE order_no = :orderNo")
                .param("orderNo", orderNo).query(String.class).single();
    }
}
