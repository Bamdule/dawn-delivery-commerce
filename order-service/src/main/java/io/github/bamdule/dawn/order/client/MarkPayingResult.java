package io.github.bamdule.dawn.order.client;

import java.time.LocalDateTime;

// 0행이면 예약의 현재 상태와 회차를 함께 받는다. 예약이 없으면 found 가 거짓이다
public record MarkPayingResult(boolean found, boolean marked, ReservationStatus currentStatus, Integer currentRound,
                               LocalDateTime expiresAt, boolean expired) {

    public static MarkPayingResult notFound() {
        return new MarkPayingResult(false, false, null, null, null, false);
    }

    public ReservationState currentState() {
        return new ReservationState(currentStatus, currentRound, expiresAt, expired);
    }
}
