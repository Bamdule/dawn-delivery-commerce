package io.github.bamdule.dawn.inventory.reconciliation;

import java.util.List;

// 풀필먼트의 센터 실물 합과 출고 전 확정 수량. 응답을 못 받으면 RestClientException 을 던진다
public interface FulfillmentStockClient {

    List<StockSummary> summary(List<Long> productIds);

    record StockSummary(long productId, int physicalQuantity, int pendingQuantity) {
    }
}
