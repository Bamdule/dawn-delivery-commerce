package io.github.bamdule.dawn.order.placement;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderNo) {
        super("주문이 없습니다. (orderNo=" + orderNo + ")");
    }
}
