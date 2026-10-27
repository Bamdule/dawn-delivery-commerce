package io.github.bamdule.dawn.messaging;

import java.time.LocalDateTime;

// 선점한 행. claimedUntil 은 기록 단계에서 "아직 내 선점인가"를 확인하는 값이다
record ClaimedEvent(long seq, String topic, String partitionKey, String payload, int attemptCount,
                    LocalDateTime claimedUntil) {
}
