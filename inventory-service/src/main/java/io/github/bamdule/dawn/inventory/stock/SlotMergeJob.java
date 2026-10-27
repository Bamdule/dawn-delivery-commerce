package io.github.bamdule.dawn.inventory.stock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 차감 경로에서 합치기를 하면 특가 행 경쟁이 늘어나서, 잔량이 기준 아래인 상품을 따로 주기적으로 합친다
@Component
public class SlotMergeJob {

    private final SlotStock slotStock;

    public SlotMergeJob(SlotStock slotStock) {
        this.slotStock = slotStock;
    }

    @Scheduled(fixedDelayString = "${dawn.slot.merge-interval:10s}")
    public void run() {
        slotStock.findSpreadProductsBelowThreshold().forEach(slotStock::mergeIfLow);
    }
}
