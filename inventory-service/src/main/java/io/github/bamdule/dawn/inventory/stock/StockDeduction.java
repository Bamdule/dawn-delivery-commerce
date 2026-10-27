package io.github.bamdule.dawn.inventory.stock;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockDeduction {

    private final StockRepository stockRepository;
    private final SlotStock slotStock;

    public StockDeduction(StockRepository stockRepository, SlotStock slotStock) {
        this.stockRepository = stockRepository;
        this.slotStock = slotStock;
    }

    // 일반 상품을 상품번호 순으로 먼저, 슬롯 상품을 마지막에 차감한다. 순서가 고정이라 교착이 없고,
    // 특가 행을 잡고 있는 시간이 커밋까지 한 문장으로 줄어든다 (docs/design/02 "상품이 여러 개인 주문").
    // 하나라도 모자라면 예외로 트랜잭션 전체를 되돌려, 일부만 차감된 주문이 남지 않는다
    @Transactional
    public Map<Long, List<SlotAllocation>> deduct(List<StockLine> stockLines) {
        return deduct(stockLines, false);
    }

    @Transactional
    public Map<Long, List<SlotAllocation>> deduct(List<StockLine> stockLines, boolean orderedSlotsOnly) {
        Map<Long, SlotMode> modes = stockRepository.findModes(stockLines.stream().map(StockLine::productId).toList());
        List<StockLine> ordered = stockLines.stream()
                .sorted(Comparator.comparing((StockLine line) -> modes.get(line.productId()) != SlotMode.NONE)
                        .thenComparingLong(StockLine::productId))
                .toList();
        Map<Long, List<SlotAllocation>> allocations = new LinkedHashMap<>();
        for (StockLine stockLine : ordered) {
            allocations.put(stockLine.productId(), deductOne(stockLine, modes.get(stockLine.productId()), orderedSlotsOnly));
        }
        return allocations;
    }

    private List<SlotAllocation> deductOne(StockLine stockLine, SlotMode readMode, boolean orderedSlotsOnly) {
        if (readMode == SlotMode.NONE) {
            if (stockRepository.decrease(stockLine.productId(), stockLine.quantity())) {
                return List.of();
            }
            // 모드가 그사이 바뀌었을 수 있다. 품절로 판단하기 전에 모드를 다시 읽는다
            SlotMode currentMode = stockRepository.findMode(stockLine.productId());
            if (currentMode == SlotMode.NONE) {
                throw new OutOfStockException(stockLine.productId());
            }
            return slotStock.deduct(stockLine.productId(), stockLine.quantity(), currentMode, orderedSlotsOnly);
        }
        return slotStock.deduct(stockLine.productId(), stockLine.quantity(), readMode, orderedSlotsOnly);
    }
}
