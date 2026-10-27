package io.github.bamdule.dawn.inventory.stock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 한 행의 상한(초당 200건)을 넘는 특가 상품만 재고를 슬롯 8개로 나눈다 (docs/design/02 "특가 상품")
@Component
public class SlotStock {

    public static final int SLOT_COUNT = 8;
    public static final int MERGE_THRESHOLD = 20;
    private static final int SLOT_ZERO = 0;

    private final JdbcClient jdbcClient;

    public SlotStock(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // 처음에는 무작위 슬롯 하나에서 빼 본다. 모자라면 그 자리에서 나눠 빼지 않고 트랜잭션 전체를 되돌린다.
    // 기본 키로 찾은 행은 갱신이 0행이어도 잠금이 남아서(READ COMMITTED 에서도 같다), 그대로 슬롯 번호 순 잠금으로
    // 넘어가면 순서가 어긋나 교착이 난다. 다시 할 때는 무작위 시도 없이 번호 순으로만 잠근다 (orderedOnly)
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SlotAllocation> deduct(long productId, int quantity, SlotMode slotMode, boolean orderedOnly) {
        if (!orderedOnly) {
            int firstSlot = slotMode == SlotMode.MERGED ? SLOT_ZERO : ThreadLocalRandom.current().nextInt(SLOT_COUNT);
            if (decreaseSlot(productId, firstSlot, quantity)) {
                return List.of(new SlotAllocation(firstSlot, quantity));
            }
            throw new RandomSlotMissException(productId);
        }
        List<SlotRow> slots = lockSlots(productId);
        int total = slots.stream().mapToInt(SlotRow::quantity).sum();
        if (total < quantity) {
            throw new OutOfStockException(productId);
        }
        List<SlotAllocation> allocations = new ArrayList<>();
        int remaining = quantity;
        for (SlotRow slot : slots) {
            int taken = Math.min(slot.quantity(), remaining);
            if (taken > 0) {
                decreaseSlot(productId, slot.slotNo(), taken);
                allocations.add(new SlotAllocation(slot.slotNo(), taken));
                remaining -= taken;
            }
            if (remaining == 0) {
                break;
            }
        }
        return allocations;
    }

    // NONE 에서 SPREAD 로. stock 행을 먼저 잠가, 같은 순간의 되돌림이 읽은 모드가 커밋할 때까지 바뀌지 않게 한다
    @Transactional
    public void spread(long productId) {
        StockModeRow stock = lockStock(productId);
        if (stock.slotMode() == SlotMode.SPREAD) {
            return;
        }
        int total = stock.quantity();
        if (stock.slotMode() == SlotMode.MERGED) {
            total = lockSlots(productId).stream().mapToInt(SlotRow::quantity).sum();
        }
        for (int slotNo = 0; slotNo < SLOT_COUNT; slotNo++) {
            int share = total / SLOT_COUNT + (slotNo < total % SLOT_COUNT ? 1 : 0);
            jdbcClient.sql("""
                    INSERT INTO stock_slot (product_id, slot_no, quantity, updated_at) VALUES (:productId, :slotNo, :quantity, NOW(3))
                    ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), updated_at = NOW(3)
                    """)
                    .param("productId", productId)
                    .param("slotNo", slotNo)
                    .param("quantity", share)
                    .update();
        }
        jdbcClient.sql("UPDATE stock SET quantity = 0, slot_mode = 'SPREAD', updated_at = NOW(3) WHERE product_id = :productId")
                .param("productId", productId)
                .update();
    }

    // 잔량 합이 20 아래면 슬롯 0 으로 모은다. 슬롯을 잠그지 않고 합을 읽으면 그사이 빠진 수량을 덮어써 오버셀이 난다
    @Transactional
    public boolean mergeIfLow(long productId) {
        StockModeRow stock = lockStock(productId);
        if (stock.slotMode() != SlotMode.SPREAD) {
            return false;
        }
        List<SlotRow> slots = lockSlots(productId);
        int total = slots.stream().mapToInt(SlotRow::quantity).sum();
        if (total >= MERGE_THRESHOLD) {
            return false;
        }
        jdbcClient.sql("UPDATE stock_slot SET quantity = CASE WHEN slot_no = 0 THEN :total ELSE 0 END, updated_at = NOW(3) WHERE product_id = :productId")
                .param("total", total)
                .param("productId", productId)
                .update();
        jdbcClient.sql("UPDATE stock SET slot_mode = 'MERGED', updated_at = NOW(3) WHERE product_id = :productId")
                .param("productId", productId)
                .update();
        return true;
    }

    public List<Long> findSpreadProductsBelowThreshold() {
        return jdbcClient.sql("""
                SELECT s.product_id FROM stock s JOIN stock_slot ss ON ss.product_id = s.product_id
                 WHERE s.slot_mode = 'SPREAD'
                 GROUP BY s.product_id
                HAVING SUM(ss.quantity) < :threshold
                """)
                .param("threshold", MERGE_THRESHOLD)
                .query(Long.class)
                .list();
    }

    // 되돌리는 쪽. SPREAD 는 내역대로 원래 슬롯에, 내역이 없거나 MERGED 면 슬롯 0 에 (docs/design/02 슬롯 모드 표).
    // 슬롯마다 돌려놓을 양을 먼저 셈한 뒤 번호 순으로 갱신한다. 슬롯 0 을 마지막에 갱신하면 잠금 순서가 어긋난다
    @Transactional(propagation = Propagation.MANDATORY)
    public void giveBack(long productId, int quantity, SlotMode slotMode, List<SlotAllocation> allocations) {
        int[] returns = new int[SLOT_COUNT];
        int remaining = quantity;
        if (slotMode == SlotMode.SPREAD) {
            for (SlotAllocation allocation : allocations) {
                int returned = Math.min(allocation.quantity(), remaining);
                returns[allocation.slotNo()] += returned;
                remaining -= returned;
            }
        }
        returns[SLOT_ZERO] += remaining;
        for (int slotNo = 0; slotNo < SLOT_COUNT; slotNo++) {
            if (returns[slotNo] > 0) {
                increaseSlot(productId, slotNo, returns[slotNo]);
            }
        }
    }

    private boolean decreaseSlot(long productId, int slotNo, int quantity) {
        return jdbcClient.sql("""
                UPDATE stock_slot SET quantity = quantity - :quantity, updated_at = NOW(3)
                 WHERE product_id = :productId AND slot_no = :slotNo AND quantity >= :quantity
                """)
                .param("quantity", quantity)
                .param("productId", productId)
                .param("slotNo", slotNo)
                .update() == 1;
    }

    private void increaseSlot(long productId, int slotNo, int quantity) {
        jdbcClient.sql("UPDATE stock_slot SET quantity = quantity + :quantity, updated_at = NOW(3) WHERE product_id = :productId AND slot_no = :slotNo")
                .param("quantity", quantity)
                .param("productId", productId)
                .param("slotNo", slotNo)
                .update();
    }

    private List<SlotRow> lockSlots(long productId) {
        return jdbcClient.sql("SELECT slot_no, quantity FROM stock_slot WHERE product_id = :productId ORDER BY slot_no FOR UPDATE")
                .param("productId", productId)
                .query(SlotRow.class)
                .list();
    }

    private StockModeRow lockStock(long productId) {
        return jdbcClient.sql("SELECT quantity, slot_mode FROM stock WHERE product_id = :productId FOR UPDATE")
                .param("productId", productId)
                .query(StockModeRow.class)
                .single();
    }

    private record SlotRow(int slotNo, int quantity) {
    }

    private record StockModeRow(int quantity, SlotMode slotMode) {
    }
}
