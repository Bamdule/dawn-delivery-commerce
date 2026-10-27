package io.github.bamdule.dawn.inventory;

// HELD 와 PAYING 을 나눠 결제 시작과 만료가 엇갈려도 하나만 이기게 한다 (docs/design/02)
public enum ReservationStatus {
    HELD,
    PAYING,
    CONFIRMED,
    RELEASED,
    RESTORED
}
