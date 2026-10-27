package io.github.bamdule.dawn.order.client;

// 봉인 요청의 답. 봉인에 성공했거나, 이미 있던 회차의 상태다
public enum SealOutcome {
    SEALED,
    REQUESTED,
    APPROVED,
    FAILED,
    UNKNOWN,
    EXPIRED
}
