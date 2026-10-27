package io.github.bamdule.dawn.inventory.stock;

// 무작위로 고른 슬롯이 모자랐다는 신호. 쥔 잠금을 모두 풀려고 트랜잭션을 되돌리고, 번호 순 잠금으로 다시 한다
public class RandomSlotMissException extends RuntimeException {

    public RandomSlotMissException(long productId) {
        super("무작위 슬롯 재고 부족 (productId=" + productId + ")");
    }
}
