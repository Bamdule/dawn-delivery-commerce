package io.github.bamdule.dawn.inventory.reservation;

// 결제 서비스에 회차 봉인을 요청한 결과. 봉인됐거나 결제 실패가 확정된 것만 예약을 풀 수 있다 (docs/design/02 "회차 봉인")
public enum SealOutcome {
    SEALED,
    FAILED,
    EXPIRED,
    APPROVED,
    REQUESTED,
    UNKNOWN;

    boolean allowsRelease() {
        return this == SEALED || this == FAILED || this == EXPIRED;
    }
}
