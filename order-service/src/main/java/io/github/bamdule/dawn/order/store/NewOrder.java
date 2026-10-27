package io.github.bamdule.dawn.order.store;

import java.util.List;

public record NewOrder(String orderNo, String checkoutId, long memberId, String recipientName, String address,
                       String regionCode, List<NewOrderItem> items) {

    public NewOrder {
        items = List.copyOf(items);
    }

    public long totalAmount() {
        return items.stream().mapToLong(item -> item.unitPrice() * item.quantity()).sum();
    }

    public record NewOrderItem(long productId, int quantity, long unitPrice) {
    }
}
