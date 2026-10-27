package io.github.bamdule.dawn.inventory.reservation;

import java.time.LocalDateTime;

public record ReservationRow(String reservationId, String orderNo, long productId, int quantity,
                             int cancelledQuantity, ReservationStatus status, Integer paymentRound,
                             LocalDateTime expiresAt) {

    // 이미 취소로 되돌린 수량을 빼고 남은, 풀 때 되돌릴 수량
    int remainingQuantity() {
        return quantity - cancelledQuantity;
    }
}
