package io.github.bamdule.dawn.fakepg;

// 다음 요청에 적용할 동작. 테스트가 미리 줄 세워 둔다
public enum FakePgBehavior {
    // 승인하고 바로 답한다
    APPROVE,
    // 카드 거절로 답한다
    DECLINE,
    // 승인은 기록하지만 응답을 delayMillis 뒤에 준다. 호출 쪽 타임아웃보다 길면 응답 유실과 같다
    APPROVE_SLOWLY,
    // 기록 없이 500 으로 답한다
    ERROR_BEFORE_PROCESSING,
    // 승인을 기록한 뒤 500 으로 답한다. 처리한 뒤 오류를 낸 경우다
    ERROR_AFTER_APPROVE,
    // 지금은 기록하지 않고 늦게 답한다. delayMillis 뒤에야 승인을 기록한다. 대행사가 늦게 받은 요청을 처리하는 경우다
    APPROVE_LATE,
    // 기록 없이 오래 붙잡는다. 요청이 대행사에 닿지 않은 것과 같다
    NEVER_RECEIVED
}
