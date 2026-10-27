package io.github.bamdule.dawn.inventory.stock;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 판매 가능 재고를 늘리고 줄이는 유일한 입구. 부족분 규칙을 여기 한 곳에 둔다 (docs/design/02 "취소 복원과 부족분")
@Component
public class SellableStock {

    private final JdbcClient jdbcClient;
    private final SlotStock slotStock;

    public SellableStock(JdbcClient jdbcClient, SlotStock slotStock) {
        this.jdbcClient = jdbcClient;
        this.slotStock = slotStock;
    }

    // 입고처럼 슬롯 내역이 없는 증가
    @Transactional(propagation = Propagation.MANDATORY)
    public void increase(long productId, int quantity) {
        increase(productId, quantity, List.of());
    }

    // 만료 해제, 취소 복원, 입고는 모두 이 길로 온다. 부족분부터 갚고 남은 것만 판매 가능 재고에 더한다
    @Transactional(propagation = Propagation.MANDATORY)
    public void increase(long productId, int quantity, List<SlotAllocation> allocations) {
        StockState stockState = lock(productId);
        int repaid = Math.min(stockState.deficit(), quantity);
        int remaining = quantity - repaid;
        jdbcClient.sql("UPDATE stock SET deficit = deficit - :repaid, updated_at = NOW(3) WHERE product_id = :productId")
                .param("repaid", repaid)
                .param("productId", productId)
                .update();
        if (remaining == 0) {
            return;
        }
        if (stockState.slotMode() == SlotMode.NONE) {
            jdbcClient.sql("UPDATE stock SET quantity = quantity + :quantity WHERE product_id = :productId")
                    .param("quantity", remaining)
                    .param("productId", productId)
                    .update();
            return;
        }
        slotStock.giveBack(productId, remaining, stockState.slotMode(), allocations);
    }

    // 입고나 실사 증가. 부족분부터 갚는 길은 취소 복원과 같고, 조정 이력을 함께 남긴다
    @Transactional(propagation = Propagation.MANDATORY)
    public void increaseForAdjustment(String eventId, long productId, int quantity) {
        increase(productId, quantity);
        log(eventId, productId, quantity, quantity);
    }

    // 실사 감소. 판매 가능 재고는 0 아래로 내리지 않고, 내리지 못한 만큼을 부족분으로 들고 있는다
    @Transactional(propagation = Propagation.MANDATORY)
    public void decreaseForStocktake(String eventId, long productId, int quantity) {
        StockState stockState = lock(productId);
        int applied = stockState.slotMode() == SlotMode.NONE
                ? Math.min(stockState.quantity(), quantity)
                : slotStock.takeForStocktake(productId, quantity, stockState.slotMode());
        jdbcClient.sql("""
                UPDATE stock
                   SET quantity = quantity - :fromQuantity, deficit = deficit + :shortfall, updated_at = NOW(3)
                 WHERE product_id = :productId
                """)
                .param("fromQuantity", stockState.slotMode() == SlotMode.NONE ? applied : 0)
                .param("shortfall", quantity - applied)
                .param("productId", productId)
                .update();
        log(eventId, productId, -quantity, -applied);
    }

    private void log(String eventId, long productId, int requested, int applied) {
        jdbcClient.sql("""
                INSERT INTO stock_adjustment_log (event_id, product_id, requested_delta, applied_delta, created_at)
                VALUES (:eventId, :productId, :requested, :applied, NOW(3))
                """)
                .param("eventId", eventId)
                .param("productId", productId)
                .param("requested", requested)
                .param("applied", applied)
                .update();
    }

    // 모드를 바꾸는 작업과 되돌리는 작업이 같은 행을 먼저 잠가, 읽은 모드가 커밋할 때까지 바뀌지 않게 한다
    private StockState lock(long productId) {
        return jdbcClient.sql("SELECT quantity, deficit, slot_mode FROM stock WHERE product_id = :productId FOR UPDATE")
                .param("productId", productId)
                .query(StockState.class)
                .single();
    }

    private record StockState(int quantity, int deficit, SlotMode slotMode) {
    }
}
