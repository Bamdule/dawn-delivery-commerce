package io.github.bamdule.dawn.inventory.stock;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

// 특가 판매 등록 전에 슬롯으로 편다. 합쳐진(MERGED) 상품도 다시 편다 (docs/design/02 "다시 펼 때")
@RestController
public class SlotModeController {

    private final SlotStock slotStock;

    public SlotModeController(SlotStock slotStock) {
        this.slotStock = slotStock;
    }

    @PutMapping("/admin/stocks/{productId}/slot-mode/spread")
    public ResponseEntity<Void> spread(@PathVariable long productId) {
        slotStock.spread(productId);
        return ResponseEntity.noContent().build();
    }
}
