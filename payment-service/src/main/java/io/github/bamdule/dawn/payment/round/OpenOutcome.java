package io.github.bamdule.dawn.payment.round;

public enum OpenOutcome {
    // 새 회차를 열었다. 이제 대행사를 부른다
    OPENED,
    // 같은 번호의 회차가 이미 있다. 응답이 유실돼 다시 온 요청이라 그 회차의 상태를 돌려준다
    EXISTING,
    // 그 번호가 봉인돼 있다
    SEALED,
    // 가장 최근 회차의 결과를 아직 모른다
    IN_PROGRESS,
    // 이미 승인된 회차가 있다
    ALREADY_PAID,
    // 기존 회차보다 작은 번호다. 오래 멈췄던 요청이다
    STALE
}
