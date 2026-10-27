package io.github.bamdule.dawn.order.refund;

// FAILED 와 REJECTED 는 사람이 확정한다 (docs/design/01 "상태 모델")
public enum RefundStatus {
    REQUESTED,
    ACCEPTED,
    COMPLETED,
    FAILED,
    REJECTED
}
