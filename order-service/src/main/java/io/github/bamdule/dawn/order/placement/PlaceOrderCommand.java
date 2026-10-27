package io.github.bamdule.dawn.order.placement;

import java.util.List;
import java.util.Objects;

// 주문서 검증(배송 가능 지역, 최소 금액, 쿠폰)을 마친 값이 실려 온다고 둔다. 단가는 할인을 품목에 나눠 반영한 값이다
public record PlaceOrderCommand(String checkoutId, long memberId, String recipientName, String address, String regionCode,
                                String paymentMethod, List<Item> items) {

    public PlaceOrderCommand {
        Objects.requireNonNull(checkoutId, "checkoutId");
        Objects.requireNonNull(regionCode, "regionCode");
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("주문할 품목이 없습니다.");
        }
    }

    public List<Long> productIds() {
        return items.stream().map(Item::productId).toList();
    }

    public record Item(long productId, int quantity, long unitPrice) {

        public Item {
            if (quantity <= 0) {
                throw new IllegalArgumentException("주문 수량은 1 이상이어야 합니다.");
            }
        }
    }
}
