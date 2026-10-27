package io.github.bamdule.dawn.fulfillment.admin;

import io.github.bamdule.dawn.event.OrderEvent.OrderLine;
import io.github.bamdule.dawn.fulfillment.event.FulfillmentEventPublisher;
import io.github.bamdule.dawn.fulfillment.target.ShipmentTargetRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 사람이 처리하는 입구는 풀필먼트 하나다. 모든 처리는 OPEN 이나 CREATED 를 조건으로 건 갱신이 1행일 때만 일어나,
// 한 예외에 이월과 취소가 동시에 와도 한쪽만 이긴다 (docs/design/05 "출고 예외")
@Service
public class ShipmentExceptions {

    private final JdbcClient jdbcClient;
    private final ShipmentTargetRepository shipmentTargetRepository;
    private final FulfillmentEventPublisher fulfillmentEventPublisher;

    public ShipmentExceptions(JdbcClient jdbcClient, ShipmentTargetRepository shipmentTargetRepository,
                              FulfillmentEventPublisher fulfillmentEventPublisher) {
        this.jdbcClient = jdbcClient;
        this.shipmentTargetRepository = shipmentTargetRepository;
        this.fulfillmentEventPublisher = fulfillmentEventPublisher;
    }

    // 배정 회차는 그대로 둔다. 남은 지난 회차 값을 다음 대상 확정이 보고 가져간다
    @Transactional
    public boolean defer(long exceptionNo) {
        if (!transition(exceptionNo, "DEFERRED")) {
            return false;
        }
        lines(exceptionNo).forEach(line -> moveFromException(orderNoOf(exceptionNo), line, "waiting_qty"));
        return true;
    }

    // 결제가 끝난 주문이라 환불은 주문 서비스가 이벤트를 받아 한다. 키는 refund-{주문번호}-admin-{예외 번호} 다
    @Transactional
    public boolean cancel(long exceptionNo) {
        if (!transition(exceptionNo, "CANCELLED")) {
            return false;
        }
        String orderNo = orderNoOf(exceptionNo);
        List<OrderLine> lines = lines(exceptionNo);
        lines.forEach(line -> moveFromException(orderNo, line, "cancelled_qty"));
        shipmentTargetRepository.markCancelledIfNothingLeft(orderNo);
        fulfillmentEventPublisher.exceptionCancelled(orderNo, exceptionNo, lines);
        return true;
    }

    // 잘못 만든 출고 지시를 배차 전에 되돌려 출고 예외로 만든다. 그 뒤는 다른 예외처럼 이월하거나 취소한다
    @Transactional
    public boolean cancelShipmentOrder(long shipmentOrderId) {
        int cancelled = jdbcClient.sql("UPDATE shipment_order SET status = 'CANCELLED' WHERE id = :id AND status = 'CREATED'")
                .param("id", shipmentOrderId).update();
        if (cancelled == 0) {
            return false;
        }
        ShipmentOrderRow shipmentOrder = jdbcClient.sql("SELECT cutoff_at, order_no, center_id FROM shipment_order WHERE id = :id")
                .param("id", shipmentOrderId).query(ShipmentOrderRow.class).single();
        List<OrderLine> lines = jdbcClient.sql("SELECT product_id, quantity FROM shipment_order_line WHERE shipment_order_id = :id ORDER BY product_id")
                .param("id", shipmentOrderId).query(OrderLine.class).list();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                INSERT INTO shipment_exception (cutoff_at, order_no, center_id, reason, status, created_at)
                VALUES (:cutoffAt, :orderNo, :centerId, 'SHIPMENT_CANCELLED', 'OPEN', NOW(3))
                """)
                .param("cutoffAt", shipmentOrder.cutoffAt())
                .param("orderNo", shipmentOrder.orderNo())
                .param("centerId", shipmentOrder.centerId())
                .update(keyHolder, "id");
        long exceptionNo = keyHolder.getKey().longValue();
        for (OrderLine line : lines) {
            jdbcClient.sql("UPDATE center_stock SET quantity = quantity + :quantity, updated_at = NOW(3) WHERE center_id = :centerId AND product_id = :productId")
                    .param("quantity", line.quantity()).param("centerId", shipmentOrder.centerId()).param("productId", line.productId())
                    .update();
            jdbcClient.sql("""
                    UPDATE shipment_target_line SET shipped_qty = shipped_qty - :quantity, exception_qty = exception_qty + :quantity
                     WHERE order_no = :orderNo AND product_id = :productId AND shipped_qty >= :quantity
                    """)
                    .param("quantity", line.quantity()).param("orderNo", shipmentOrder.orderNo()).param("productId", line.productId())
                    .update();
            jdbcClient.sql("INSERT INTO shipment_exception_line (shipment_exception_id, product_id, quantity) VALUES (:id, :productId, :quantity)")
                    .param("id", exceptionNo).param("productId", line.productId()).param("quantity", line.quantity())
                    .update();
        }
        return true;
    }

    private boolean transition(long exceptionNo, String to) {
        return jdbcClient.sql("UPDATE shipment_exception SET status = :to WHERE id = :id AND status = 'OPEN'")
                .param("id", exceptionNo).param("to", to).update() == 1;
    }

    private String orderNoOf(long exceptionNo) {
        return jdbcClient.sql("SELECT order_no FROM shipment_exception WHERE id = :id").param("id", exceptionNo).query(String.class).single();
    }

    private List<OrderLine> lines(long exceptionNo) {
        return jdbcClient.sql("SELECT product_id, quantity FROM shipment_exception_line WHERE shipment_exception_id = :id ORDER BY product_id")
                .param("id", exceptionNo).query(OrderLine.class).list();
    }

    private void moveFromException(String orderNo, OrderLine line, String targetColumn) {
        jdbcClient.sql("""
                UPDATE shipment_target_line SET exception_qty = exception_qty - :quantity, %s = %s + :quantity
                 WHERE order_no = :orderNo AND product_id = :productId AND exception_qty >= :quantity
                """.formatted(targetColumn, targetColumn))
                .param("quantity", line.quantity()).param("orderNo", orderNo).param("productId", line.productId())
                .update();
    }

    private record ShipmentOrderRow(LocalDateTime cutoffAt, String orderNo, String centerId) {
    }
}
