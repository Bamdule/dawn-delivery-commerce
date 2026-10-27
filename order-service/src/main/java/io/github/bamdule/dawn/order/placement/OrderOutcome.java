package io.github.bamdule.dawn.order.placement;

// 사용자 화면에 보여 줄 결과. 결과를 모르면 실패로 단정하지 않고 확인 중으로 답한다 (docs/design/01 "동기 구간")
public enum OrderOutcome {
    PAID,
    SOLD_OUT,
    FAILED,
    CANCELLED,
    PAYMENT_DECLINED,
    PAYMENT_DELAYED,
    PAYMENT_CHECKING,
    PAYMENT_IN_PROGRESS,
    RETRY_PAYMENT,
    RETRY_LATER
}
