package io.github.bamdule.dawn.order.client;

// 재고 서비스의 예약 상태. 이름이 같아야 응답을 그대로 읽는다
public enum ReservationStatus {
    HELD,
    PAYING,
    CONFIRMED,
    RELEASED,
    RESTORED
}
