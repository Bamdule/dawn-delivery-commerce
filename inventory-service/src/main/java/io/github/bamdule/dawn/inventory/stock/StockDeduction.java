package io.github.bamdule.dawn.inventory.stock;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockDeduction {

    private final StockRepository stockRepository;

    public StockDeduction(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    // 상품번호 순으로 잠가 {A, B} 와 {B, A} 를 담은 주문이 서로를 기다리는 교착을 막는다.
    // 하나라도 모자라면 예외로 트랜잭션 전체를 되돌려, 일부만 차감된 주문이 남지 않는다
    @Transactional
    public void deduct(List<StockLine> stockLines) {
        List<StockLine> ordered = stockLines.stream()
                .sorted(Comparator.comparingLong(StockLine::productId))
                .toList();
        for (StockLine stockLine : ordered) {
            if (!stockRepository.decrease(stockLine.productId(), stockLine.quantity())) {
                throw new OutOfStockException(stockLine.productId());
            }
        }
    }
}
