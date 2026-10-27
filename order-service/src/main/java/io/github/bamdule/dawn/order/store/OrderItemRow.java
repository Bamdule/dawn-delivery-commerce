package io.github.bamdule.dawn.order.store;

public record OrderItemRow(long id, long orderId, long productId, int quantity, long unitPrice, int cancelledQuantity) {

    public int remainingQuantity() {
        return quantity - cancelledQuantity;
    }
}
