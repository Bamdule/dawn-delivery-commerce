package io.github.bamdule.dawn.order.store;

import io.github.bamdule.dawn.event.CancelReason;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

// 모든 전이는 이전 상태를 조건으로 거는 조건부 갱신이다. 경합하면 하나만 1행을 받는다 (docs/design/01 "상태 모델")
@Repository
public class OrderRepository {

    private static final String COLUMNS = """
            id, order_no, checkout_id, member_id, status, total_amount, refunded_amount, payment_round, payment_attempt,
            paid_round, recipient_name, address, region_code, cancel_reason, reservation_expires_at, created_at, paid_at
            """;
    // 예약 응답을 받기 전에 죽어 만료 시각이 비었으면 주문 시각에 가장 긴 유지 시간을 더해 쓴다 (docs/design/04)
    private static final int MAX_HOLD_MINUTES = 10;
    private static final int STUCK_ATTEMPT_SECONDS = 60;
    private static final int OVERDUE_MINUTES = 15;

    private final JdbcClient jdbcClient;

    public OrderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long insert(NewOrder newOrder) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                INSERT INTO orders (order_no, checkout_id, member_id, status, total_amount, recipient_name, address,
                                    region_code, created_at, updated_at)
                VALUES (:orderNo, :checkoutId, :memberId, 'PENDING', :totalAmount, :recipientName, :address,
                        :regionCode, NOW(3), NOW(3))
                """)
                .param("orderNo", newOrder.orderNo())
                .param("checkoutId", newOrder.checkoutId())
                .param("memberId", newOrder.memberId())
                .param("totalAmount", newOrder.totalAmount())
                .param("recipientName", newOrder.recipientName())
                .param("address", newOrder.address())
                .param("regionCode", newOrder.regionCode())
                .update(keyHolder, "id");
        long orderId = keyHolder.getKey().longValue();
        for (NewOrder.NewOrderItem item : newOrder.items()) {
            jdbcClient.sql("""
                    INSERT INTO order_item (order_id, product_id, quantity, unit_price)
                    VALUES (:orderId, :productId, :quantity, :unitPrice)
                    """)
                    .param("orderId", orderId)
                    .param("productId", item.productId())
                    .param("quantity", item.quantity())
                    .param("unitPrice", item.unitPrice())
                    .update();
        }
        return orderId;
    }

    public Optional<OrderRow> findByCheckoutId(String checkoutId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM orders WHERE checkout_id = :checkoutId")
                .param("checkoutId", checkoutId)
                .query(OrderRow.class)
                .optional();
    }

    public Optional<OrderRow> findByOrderNo(String orderNo) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM orders WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(OrderRow.class)
                .optional();
    }

    // 품목 취소와 결제 이벤트 반영은 주문 행을 잠근 뒤 품목을 읽는다. 두 사유가 겹쳐도 품목 수량을 넘지 않는다
    public Optional<OrderRow> lockByOrderNo(String orderNo) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM orders WHERE order_no = :orderNo FOR UPDATE")
                .param("orderNo", orderNo)
                .query(OrderRow.class)
                .optional();
    }

    public List<OrderItemRow> findItems(long orderId) {
        return jdbcClient.sql("""
                SELECT id, order_id, product_id, quantity, unit_price, cancelled_quantity
                  FROM order_item WHERE order_id = :orderId ORDER BY product_id
                """)
                .param("orderId", orderId)
                .query(OrderItemRow.class)
                .list();
    }

    public void saveReservationExpiry(String orderNo, LocalDateTime expiresAt) {
        jdbcClient.sql("UPDATE orders SET reservation_expires_at = :expiresAt, updated_at = NOW(3) WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .param("expiresAt", expiresAt)
                .update();
    }

    // 결제를 연달아 눌러도 한 요청만 1행을 받는다. 나머지는 "결제 진행 중"이다
    public boolean startAttempt(String orderNo, int currentRound) {
        return jdbcClient.sql("""
                UPDATE orders
                   SET payment_attempt = 'IN_PROGRESS', payment_round = payment_round + 1,
                       attempt_started_at = NOW(3), updated_at = NOW(3)
                 WHERE order_no = :orderNo AND status = 'PENDING' AND payment_attempt = 'IDLE'
                   AND payment_round = :currentRound
                """)
                .param("orderNo", orderNo)
                .param("currentRound", currentRound)
                .update() == 1;
    }

    // 오래 멈췄던 요청이 깨어나 끝내려 해도, 그사이 다음 회차가 시작됐으면 회차 조건 때문에 0행이다
    public boolean endAttempt(String orderNo, int round) {
        return jdbcClient.sql("""
                UPDATE orders SET payment_attempt = 'IDLE', attempt_started_at = NULL, updated_at = NOW(3)
                 WHERE order_no = :orderNo AND payment_attempt = 'IN_PROGRESS' AND payment_round = :round
                """)
                .param("orderNo", orderNo)
                .param("round", round)
                .update() == 1;
    }

    // paid_at 은 이 커밋의 주문 DB 시각이다. 마감 회차를 모든 서비스가 이 한 시계로 정한다 (docs/design/05)
    public boolean markPaid(String orderNo, int round) {
        return jdbcClient.sql("""
                UPDATE orders
                   SET status = 'PAID', paid_round = :round, paid_at = NOW(3), payment_attempt = 'IDLE',
                       attempt_started_at = NULL, updated_at = NOW(3)
                 WHERE order_no = :orderNo AND status = 'PENDING' AND payment_attempt = 'IN_PROGRESS'
                   AND payment_round = :round
                """)
                .param("orderNo", orderNo)
                .param("round", round)
                .update() == 1;
    }

    // 시도가 끝난 주문만 실패로 닫는다. 진행 중인 시도가 있으면 열려 있는 회차가 승인될 수 있다
    public boolean markFailed(String orderNo) {
        return jdbcClient.sql("""
                UPDATE orders SET status = 'FAILED', updated_at = NOW(3)
                 WHERE order_no = :orderNo AND status = 'PENDING' AND payment_attempt = 'IDLE'
                """)
                .param("orderNo", orderNo)
                .update() == 1;
    }

    public void addCancelledQuantity(long itemId, int quantity) {
        jdbcClient.sql("""
                UPDATE order_item SET cancelled_quantity = cancelled_quantity + :quantity
                 WHERE id = :itemId AND cancelled_quantity + :quantity <= quantity
                """)
                .param("itemId", itemId)
                .param("quantity", quantity)
                .update();
    }

    public boolean markCancelled(long orderId, CancelReason cancelReason) {
        return jdbcClient.sql("""
                UPDATE orders SET status = 'CANCELLED', cancel_reason = :cancelReason, updated_at = NOW(3)
                 WHERE id = :orderId AND status = 'PAID'
                """)
                .param("orderId", orderId)
                .param("cancelReason", cancelReason.name())
                .update() == 1;
    }

    // 정산 대사가 완료를 실패로 바로잡으면 음수로 불러 되돌린다
    public void addRefunded(String orderNo, long amount) {
        jdbcClient.sql("UPDATE orders SET refunded_amount = refunded_amount + :amount, updated_at = NOW(3) WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .param("amount", amount)
                .update();
    }

    // (시각, id) 다음부터 읽는다. 아직 확정되지 않아 남는 행이 앞을 막아도 그 뒤의 행까지 한 주기에 닿는다
    public List<StuckAttempt> findStuckAttempts(PageCursor after, int limit) {
        return jdbcClient.sql("""
                SELECT id, order_no, payment_round, attempt_started_at AS sort_at,
                       attempt_started_at < NOW(3) - INTERVAL :overdueMinutes MINUTE AS overdue
                  FROM orders
                 WHERE payment_attempt = 'IN_PROGRESS'
                   AND attempt_started_at < NOW(3) - INTERVAL :stuckSeconds SECOND
                   AND (attempt_started_at > :afterAt OR (attempt_started_at = :afterAt AND id > :afterId))
                 ORDER BY attempt_started_at, id
                 LIMIT :limit
                """)
                .param("stuckSeconds", STUCK_ATTEMPT_SECONDS)
                .param("overdueMinutes", OVERDUE_MINUTES)
                .param("afterAt", after.sortAt())
                .param("afterId", after.id())
                .param("limit", limit)
                .query(StuckAttempt.class)
                .list();
    }

    public List<ExpiredPending> findExpiredIdlePending(PageCursor after, int limit) {
        return jdbcClient.sql("""
                SELECT id, order_no, created_at AS sort_at, created_at < NOW(3) - INTERVAL :overdueMinutes MINUTE AS overdue
                  FROM orders
                 WHERE status = 'PENDING' AND payment_attempt = 'IDLE'
                   AND COALESCE(reservation_expires_at, created_at + INTERVAL :maxHoldMinutes MINUTE) < NOW(3)
                   AND (created_at > :afterAt OR (created_at = :afterAt AND id > :afterId))
                 ORDER BY created_at, id
                 LIMIT :limit
                """)
                .param("maxHoldMinutes", MAX_HOLD_MINUTES)
                .param("overdueMinutes", OVERDUE_MINUTES)
                .param("afterAt", after.sortAt())
                .param("afterId", after.id())
                .param("limit", limit)
                .query(ExpiredPending.class)
                .list();
    }

    // (paid_at, id) 순서는 (status, paid_at) 인덱스의 순서 그대로라 페이지마다 정렬하지 않는다
    public List<PaidOrder> findPaid(LocalDateTime paidAfter, LocalDateTime paidUntil, LocalDateTime cursorPaidAt,
                                    long cursorId, int limit) {
        return jdbcClient.sql("""
                SELECT id, order_no, paid_at
                  FROM orders
                 WHERE status = 'PAID' AND paid_at > :paidAfter AND paid_at <= :paidUntil
                   AND (paid_at > :cursorPaidAt OR (paid_at = :cursorPaidAt AND id > :cursorId))
                 ORDER BY paid_at, id
                 LIMIT :limit
                """)
                .param("paidAfter", paidAfter)
                .param("paidUntil", paidUntil)
                .param("cursorPaidAt", cursorPaidAt)
                .param("cursorId", cursorId)
                .param("limit", limit)
                .query(PaidOrder.class)
                .list();
    }

    public LocalDateTime now() {
        return jdbcClient.sql("SELECT NOW(3)").query(LocalDateTime.class).single();
    }

    public record StuckAttempt(long id, String orderNo, int paymentRound, LocalDateTime sortAt, boolean overdue) {

        public PageCursor cursor() {
            return new PageCursor(sortAt, id);
        }
    }

    public record ExpiredPending(long id, String orderNo, LocalDateTime sortAt, boolean overdue) {

        public PageCursor cursor() {
            return new PageCursor(sortAt, id);
        }
    }

    public record PageCursor(LocalDateTime sortAt, long id) {

        public static final PageCursor FIRST = new PageCursor(LocalDateTime.of(2000, 1, 1, 0, 0), 0);
    }

    public record PaidOrder(long id, String orderNo, LocalDateTime paidAt) {
    }
}
