package io.github.bamdule.dawn.order.refund;

public enum RefundReason {
    CUSTOMER_CANCEL,
    UNFILLABLE_PARTIAL,
    UNFILLABLE_CANCEL,
    ADMIN_CANCEL,
    ORPHAN_APPROVAL;

    // 쓰이지 않은 승인은 주문을 결제한 회차가 아니라서 주문의 환불 누적액에 넣지 않는다
    public boolean countsTowardOrder() {
        return this != ORPHAN_APPROVAL;
    }
}
