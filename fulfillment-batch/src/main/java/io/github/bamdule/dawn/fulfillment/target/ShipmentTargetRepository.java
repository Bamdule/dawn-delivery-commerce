package io.github.bamdule.dawn.fulfillment.target;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// 품목의 상태는 다섯 수량으로 센다. 모든 이동은 옮길 쪽 수량을 조건으로 건 갱신이라 음수가 되지 않는다 (docs/design/05)
@Repository
public class ShipmentTargetRepository {

    private final JdbcClient jdbcClient;

    public ShipmentTargetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // 이미 있으면 넣지 않는다. 중복 PAID 도, 먼저 온 취소 표시도 그대로 둔다
    public boolean insertWaiting(NewTarget newTarget) {
        int inserted = jdbcClient.sql("""
                INSERT IGNORE INTO shipment_target (order_no, status, region_code, recipient_name, address, paid_at, updated_at)
                VALUES (:orderNo, 'WAITING', :regionCode, :recipientName, :address, :paidAt, NOW(3))
                """)
                .param("orderNo", newTarget.orderNo())
                .param("regionCode", newTarget.regionCode())
                .param("recipientName", newTarget.recipientName())
                .param("address", newTarget.address())
                .param("paidAt", newTarget.paidAt())
                .update();
        if (inserted == 0) {
            return false;
        }
        for (NewTarget.Line line : newTarget.lines()) {
            jdbcClient.sql("""
                    INSERT INTO shipment_target_line (order_no, product_id, quantity, waiting_qty, cancelled_qty)
                    VALUES (:orderNo, :productId, :quantity, :waitingQty, :cancelledQty)
                    """)
                    .param("orderNo", newTarget.orderNo())
                    .param("productId", line.productId())
                    .param("quantity", line.quantity())
                    .param("waitingQty", line.quantity() - line.cancelledQuantity())
                    .param("cancelledQty", line.cancelledQuantity())
                    .update();
        }
        return true;
    }

    // 모르는 주문의 취소가 먼저 왔다. 뒤늦게 온 PAID 가 이 주문을 되살리지 않게 표시만 남긴다
    public void insertCancelledMark(String orderNo) {
        jdbcClient.sql("INSERT IGNORE INTO shipment_target (order_no, status, updated_at) VALUES (:orderNo, 'CANCELLED', NOW(3))")
                .param("orderNo", orderNo)
                .update();
    }

    // 주문 키의 쓰기는 모두 이 행을 먼저 잠근다. 그다음 품목 행, 센터 재고 행 순서다 (docs/design/03 "키 단위 순서의 조건")
    public boolean lockTarget(String orderNo) {
        return jdbcClient.sql("SELECT order_no FROM shipment_target WHERE order_no = :orderNo FOR UPDATE")
                .param("orderNo", orderNo).query(String.class).optional().isPresent();
    }

    public boolean exists(String orderNo) {
        return jdbcClient.sql("SELECT COUNT(*) FROM shipment_target WHERE order_no = :orderNo")
                .param("orderNo", orderNo).query(Integer.class).single() > 0;
    }

    public List<String> findExisting(Collection<String> orderNos) {
        return jdbcClient.sql("SELECT order_no FROM shipment_target WHERE order_no IN (:orderNos)")
                .param("orderNos", orderNos)
                .query(String.class)
                .list();
    }

    public int cancelWaiting(String orderNo, long productId, int quantity) {
        return jdbcClient.sql("""
                UPDATE shipment_target_line
                   SET waiting_qty = waiting_qty - :quantity, cancelled_qty = cancelled_qty + :quantity
                 WHERE order_no = :orderNo AND product_id = :productId AND waiting_qty >= :quantity
                """)
                .param("orderNo", orderNo)
                .param("productId", productId)
                .param("quantity", quantity)
                .update();
    }

    public int waitingOf(String orderNo, long productId) {
        return jdbcClient.sql("SELECT waiting_qty FROM shipment_target_line WHERE order_no = :orderNo AND product_id = :productId FOR UPDATE")
                .param("orderNo", orderNo)
                .param("productId", productId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    // 남은 대기 수량을 모두 취소로 옮기고, 출고되거나 처리 중인 수량이 없으면 대상도 취소로 바꾼다
    public void cancelAllWaiting(String orderNo) {
        jdbcClient.sql("""
                UPDATE shipment_target_line SET cancelled_qty = cancelled_qty + waiting_qty, waiting_qty = 0
                 WHERE order_no = :orderNo AND waiting_qty > 0
                """)
                .param("orderNo", orderNo)
                .update();
        markCancelledIfNothingLeft(orderNo);
    }

    public void markCancelledIfNothingLeft(String orderNo) {
        jdbcClient.sql("""
                UPDATE shipment_target SET status = 'CANCELLED', updated_at = NOW(3)
                 WHERE order_no = :orderNo AND status = 'WAITING'
                   AND NOT EXISTS (SELECT 1 FROM shipment_target_line l
                                    WHERE l.order_no = :orderNo AND l.cancelled_qty < l.quantity)
                """)
                .param("orderNo", orderNo)
                .update();
    }

    public void markCancelled(String orderNo) {
        jdbcClient.sql("UPDATE shipment_target SET status = 'CANCELLED', updated_at = NOW(3) WHERE order_no = :orderNo AND status = 'WAITING'")
                .param("orderNo", orderNo)
                .update();
    }

    public void updateAddress(String orderNo, String recipientName, String address) {
        jdbcClient.sql("UPDATE shipment_target SET recipient_name = :recipientName, address = :address, updated_at = NOW(3) WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .param("recipientName", recipientName)
                .param("address", address)
                .update();
    }

    public record NewTarget(String orderNo, String regionCode, String recipientName, String address, LocalDateTime paidAt,
                            List<Line> lines) {

        public NewTarget {
            lines = List.copyOf(lines);
        }

        public record Line(long productId, int quantity, int cancelledQuantity) {
        }
    }
}
