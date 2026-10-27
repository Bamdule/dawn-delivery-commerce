package io.github.bamdule.dawn.payment.round;

// 재고 서비스의 만료 배치와 주문 서비스의 정리 배치가 받는 답. 이름은 재고 서비스의 SealOutcome 과 같다
public enum SealOutcome {
    SEALED,
    REQUESTED,
    APPROVED,
    FAILED,
    UNKNOWN,
    EXPIRED
}
