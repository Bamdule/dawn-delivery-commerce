package io.github.bamdule.dawn.event;

public final class Topics {

    // 같은 주문의 이벤트 순서를 지키려고 주문번호를 파티션 키로 쓴다 (docs/design/03)
    public static final String ORDER_EVENTS = "order.events";
    public static final String PAYMENT_EVENTS = "payment.events";
    public static final String FULFILLMENT_EVENTS = "fulfillment.events";

    private Topics() {
    }
}
