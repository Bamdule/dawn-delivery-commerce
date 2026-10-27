package io.github.bamdule.dawn.payment;

// 타임아웃은 실패가 아니라 결과를 모르는 상태라 REQUESTED 로 남긴다. EXPIRED 는 봉인으로 생긴 끝 상태다 (docs/design/04)
public enum PaymentStatus {
    REQUESTED,
    APPROVED,
    FAILED,
    UNKNOWN,
    EXPIRED
}
