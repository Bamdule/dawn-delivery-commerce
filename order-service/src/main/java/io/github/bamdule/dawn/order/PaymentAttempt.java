package io.github.bamdule.dawn.order;

// 주문당 결제 시도는 한 번에 하나다. 시도를 시작할 때 IN_PROGRESS 와 다음 회차로 바꾼다 (docs/design/01)
public enum PaymentAttempt {
    IDLE,
    IN_PROGRESS
}
