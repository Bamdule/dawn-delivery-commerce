package io.github.bamdule.dawn.inventory.reservation;

public interface PaymentRoundSealer {

    SealOutcome seal(String orderNo, int paymentRound);
}
