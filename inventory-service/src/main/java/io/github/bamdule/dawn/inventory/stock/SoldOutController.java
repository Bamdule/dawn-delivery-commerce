package io.github.bamdule.dawn.inventory.stock;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 주문 서비스가 500ms 마다 받아 주문 저장 전에 품절 시도를 거른다. 캐시하지 않고 요청 때마다 계산한다 (docs/design/01)
@RestController
public class SoldOutController {

    private final StockRepository stockRepository;

    public SoldOutController(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @GetMapping("/stocks/sold-out")
    public SoldOutResponse soldOut() {
        return new SoldOutResponse(stockRepository.findSoldOutProductIds());
    }

    public record SoldOutResponse(List<Long> productIds) {
    }
}
