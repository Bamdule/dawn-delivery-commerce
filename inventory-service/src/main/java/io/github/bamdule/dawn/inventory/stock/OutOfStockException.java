package io.github.bamdule.dawn.inventory.stock;

public class OutOfStockException extends RuntimeException {

    private final long productId;

    public OutOfStockException(long productId) {
        super("재고가 부족합니다.");
        this.productId = productId;
    }

    public long getProductId() {
        return productId;
    }
}
