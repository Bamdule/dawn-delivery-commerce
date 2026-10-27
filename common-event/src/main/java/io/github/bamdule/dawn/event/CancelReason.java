package io.github.bamdule.dawn.event;

// 재고 복원 규칙은 사유와 관계없이 같다. 사유는 풀필먼트가 고객 취소만 골라 처리할 때 쓴다 (docs/design/05)
public enum CancelReason {
    CUSTOMER,
    UNFILLABLE,
    ADMIN,
    ORPHAN
}
