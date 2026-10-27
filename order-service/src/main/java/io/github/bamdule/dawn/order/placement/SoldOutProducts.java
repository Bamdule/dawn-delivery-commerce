package io.github.bamdule.dawn.order.placement;

import io.github.bamdule.dawn.order.client.InventoryClient;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

// 특가 품절 뒤의 시도를 주문 DB 에 쓰기 전에 거른다. 목록이 틀려도 손해는 한쪽뿐이다.
// 품절인데 통과하면 예약에서 걸러지고, 재고가 돌아왔는데 막히면 다음 갱신에 열린다 (docs/design/01 "특가 시도 거르기")
@Component
public class SoldOutProducts {

    private static final Logger log = LoggerFactory.getLogger(SoldOutProducts.class);

    private final InventoryClient inventoryClient;
    private volatile Set<Long> soldOutProductIds = Set.of();

    public SoldOutProducts(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    // 받지 못하면 직전 목록을 그대로 쓴다. 비우면 품절 상품 시도가 한꺼번에 주문 DB 로 몰린다
    public void refresh() {
        try {
            List<Long> productIds = inventoryClient.soldOutProductIds();
            soldOutProductIds = productIds == null ? Set.of() : Set.copyOf(productIds);
        } catch (RestClientException restClientException) {
            log.warn("품절 목록 갱신 실패 (cause={})", restClientException.getMessage());
        }
    }

    public Optional<Long> firstSoldOut(Collection<Long> productIds) {
        Set<Long> current = soldOutProductIds;
        return productIds.stream().filter(current::contains).findFirst();
    }
}
