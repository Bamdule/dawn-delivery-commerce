package io.github.bamdule.dawn.inventory.reservation;

import java.time.LocalDateTime;

// 결제 시작 표시가 0행이면 현재 상태와 회차를 함께 돌려준다. 주문 서비스는 이 값으로 갈래를 나눈다 (docs/design/01)
public record MarkPayingResult(boolean marked, ReservationStatus currentStatus, Integer currentRound,
                               LocalDateTime expiresAt) {

    static MarkPayingResult marked(int round, LocalDateTime expiresAt) {
        return new MarkPayingResult(true, ReservationStatus.PAYING, round, expiresAt);
    }

    static MarkPayingResult notMarked(ReservationStatus currentStatus, Integer currentRound, LocalDateTime expiresAt) {
        return new MarkPayingResult(false, currentStatus, currentRound, expiresAt);
    }
}
