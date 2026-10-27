package io.github.bamdule.dawn.order.client;

import java.time.LocalDateTime;

// 만료 여부는 재고 서비스가 자기 DB 시계로 판단해 실어 준다
public record ReservationState(ReservationStatus status, Integer paymentRound, LocalDateTime expiresAt, boolean expired) {

    // 정리 배치가 주문을 닫는 조건이다. 만료된 PAYING 은 재고 만료 배치가 회차를 봉인한 뒤 풀어서 여기 들지 않는다
    public boolean releasedOrExpiredHeld() {
        return status == ReservationStatus.RELEASED || status == ReservationStatus.RESTORED
                || (status == ReservationStatus.HELD && expired);
    }
}
