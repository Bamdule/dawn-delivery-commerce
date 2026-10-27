package io.github.bamdule.dawn.inventory.stock;

public record StockLine(long productId, int quantity) {

    public StockLine {
        if (quantity <= 0) {
            throw new IllegalArgumentException("차감 수량은 1 이상이어야 합니다.");
        }
    }
}
