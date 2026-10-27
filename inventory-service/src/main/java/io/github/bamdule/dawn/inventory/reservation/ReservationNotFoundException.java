package io.github.bamdule.dawn.inventory.reservation;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(String orderNo) {
        super("예약이 없습니다. (orderNo=" + orderNo + ")");
    }
}
