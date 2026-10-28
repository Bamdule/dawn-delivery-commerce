package io.github.bamdule.dawn.inventory.reservation;

import io.github.bamdule.dawn.inventory.reservation.ReservationRepository.ExpiredPayingOrder;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// HELD 는 결제가 시작되지 않았다는 뜻이라 바로 푼다. PAYING 은 그 회차를 봉인한 뒤에만 푼다 (docs/design/02)
@Component
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);
    private static final int BATCH_LIMIT = 500;
    private static final String FIRST_KEY = "";

    private final ReservationRepository reservationRepository;
    private final ReservationLifecycle reservationLifecycle;
    private final PaymentRoundSealer paymentRoundSealer;
    private final AtomicReference<Instant> lastCompletedAt = new AtomicReference<>(Instant.now());

    public ReservationExpiryJob(ReservationRepository reservationRepository, ReservationLifecycle reservationLifecycle,
                                PaymentRoundSealer paymentRoundSealer) {
        this.reservationRepository = reservationRepository;
        this.reservationLifecycle = reservationLifecycle;
        this.paymentRoundSealer = paymentRoundSealer;
    }

    @Scheduled(fixedDelayString = "${dawn.reservation.expiry-interval:1m}")
    public void run() {
        List<ReservationRow> held = reservationRepository.findExpiredHeld(FIRST_KEY, BATCH_LIMIT);
        while (!held.isEmpty()) {
            held.forEach(reservationLifecycle::releaseExpiredHeld);
            held = reservationRepository.findExpiredHeld(held.getLast().reservationId(), BATCH_LIMIT);
        }
        List<ExpiredPayingOrder> paying = reservationRepository.findExpiredPayingOrders(FIRST_KEY, BATCH_LIMIT);
        while (!paying.isEmpty()) {
            paying.forEach(this::handleExpiredPayingSafely);
            paying = reservationRepository.findExpiredPayingOrders(paying.getLast().orderNo(), BATCH_LIMIT);
        }
        lastCompletedAt.set(Instant.now());
    }

    // 결제 서비스가 답하지 않는 주문 하나가 나머지 주문의 만료를 막지 않게 한다
    private void handleExpiredPayingSafely(ExpiredPayingOrder expiredPayingOrder) {
        try {
            handleExpiredPaying(expiredPayingOrder);
        } catch (RuntimeException exception) {
            log.warn("만료 예약의 회차 봉인 실패 (orderNo={}, paymentRound={})",
                    expiredPayingOrder.orderNo(), expiredPayingOrder.paymentRound());
        }
    }

    private void handleExpiredPaying(ExpiredPayingOrder expiredPayingOrder) {
        String orderNo = expiredPayingOrder.orderNo();
        int paymentRound = expiredPayingOrder.paymentRound();
        SealOutcome sealOutcome = paymentRoundSealer.seal(orderNo, paymentRound);
        if (sealOutcome.allowsRelease()) {
            reservationLifecycle.releasePaying(orderNo, paymentRound);
        } else if (sealOutcome == SealOutcome.APPROVED) {
            reservationLifecycle.confirmPaying(orderNo, paymentRound);
        } else if (sealOutcome == SealOutcome.UNKNOWN) {
            log.error("결과를 모르는 결제의 예약 만료 (orderNo={}, paymentRound={})", orderNo, paymentRound);
        }
        // REQUESTED 는 결제 서비스 조회 배치가 확정하면 다음 주기에 처리한다
    }

    // 만료 배치가 5분 넘게 돌지 않으면 경보를 낸다 (docs/design/01 운영 지표)
    public Instant lastCompletedAt() {
        return lastCompletedAt.get();
    }
}
