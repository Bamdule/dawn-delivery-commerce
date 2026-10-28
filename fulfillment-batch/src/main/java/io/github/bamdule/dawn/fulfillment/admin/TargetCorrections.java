package io.github.bamdule.dawn.fulfillment.admin;

import io.github.bamdule.dawn.fulfillment.target.OrderClient;
import io.github.bamdule.dawn.fulfillment.target.OrderClient.OrderDetail;
import io.github.bamdule.dawn.fulfillment.target.ShipmentTargetRepository;
import io.github.bamdule.dawn.fulfillment.target.ShipmentTargetRepository.NewTarget;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 대상 확정이 세 번 어긋나 남긴 차이 목록을 보고 사람이 출고 대상을 주문 서비스 기준으로 바로잡는다 (docs/design/01 "수렴 책임")
@Service
public class TargetCorrections {

    private final OrderClient orderClient;
    private final ShipmentTargetRepository shipmentTargetRepository;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public TargetCorrections(OrderClient orderClient, ShipmentTargetRepository shipmentTargetRepository, JdbcClient jdbcClient,
                             TransactionTemplate transactionTemplate) {
        this.orderClient = orderClient;
        this.shipmentTargetRepository = shipmentTargetRepository;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
    }

    // 대상이 없으면 넣는다. 취소 표시인데 주문이 PAID 이고 아직 아무 수량도 처리되지 않았으면 대기로 되돌린다
    public boolean resync(String orderNo) {
        Optional<OrderDetail> detail = orderClient.detail(orderNo);
        if (detail.isEmpty() || !"PAID".equals(detail.get().status())) {
            return false;
        }
        OrderDetail orderDetail = detail.get();
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!shipmentTargetRepository.exists(orderNo)) {
                return shipmentTargetRepository.insertWaiting(toTarget(orderDetail));
            }
            shipmentTargetRepository.lockTarget(orderNo);
            boolean untouched = jdbcClient.sql("""
                    SELECT COUNT(*) FROM shipment_target_line
                     WHERE order_no = :orderNo AND (shipped_qty > 0 OR unfilled_qty > 0 OR exception_qty > 0)
                    """).param("orderNo", orderNo).query(Integer.class).single() == 0;
            if (!untouched) {
                return false;
            }
            // 취소 표시를 지운 때만 품목을 지운다. 이미 대기 중인 대상을 다시 보정하면 아무것도 바꾸지 않고 409 다
            int removedMark = jdbcClient.sql("DELETE FROM shipment_target WHERE order_no = :orderNo AND status = 'CANCELLED'")
                    .param("orderNo", orderNo).update();
            if (removedMark == 0) {
                return false;
            }
            jdbcClient.sql("DELETE FROM shipment_target_line WHERE order_no = :orderNo").param("orderNo", orderNo).update();
            if (!shipmentTargetRepository.insertWaiting(toTarget(orderDetail))) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    private static NewTarget toTarget(OrderDetail orderDetail) {
        return new NewTarget(orderDetail.orderNo(), orderDetail.regionCode(), orderDetail.recipientName(), orderDetail.address(),
                orderDetail.paidAt(), orderDetail.items().stream()
                .map(item -> new NewTarget.Line(item.productId(), item.quantity(), item.cancelledQuantity()))
                .toList());
    }
}
