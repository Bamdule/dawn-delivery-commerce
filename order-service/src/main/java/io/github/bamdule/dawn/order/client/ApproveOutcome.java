package io.github.bamdule.dawn.order.client;

// 결제 서비스의 승인 응답. 이름이 같아야 응답을 그대로 읽는다 (docs/design/04 "새 회차를 여는 조건")
public enum ApproveOutcome {
    APPROVED,
    DECLINED,
    UNKNOWN,
    IN_PROGRESS,
    ALREADY_PAID,
    SEALED,
    STALE
}
