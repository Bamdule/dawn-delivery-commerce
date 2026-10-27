package io.github.bamdule.dawn.inventory.reservation;

import java.time.LocalDateTime;

// 주문 서비스의 결제 흐름과 정리 배치가 갈래를 나눌 때 보는 예약의 현재 모습. 한 주문의 예약은 상태와 만료 시각이 하나다
public record ReservationState(ReservationStatus status, Integer paymentRound, LocalDateTime expiresAt, boolean expired) {
}
