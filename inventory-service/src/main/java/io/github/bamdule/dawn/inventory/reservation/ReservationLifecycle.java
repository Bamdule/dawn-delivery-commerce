package io.github.bamdule.dawn.inventory.reservation;

import io.github.bamdule.dawn.inventory.stock.SellableStock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 확정, 취소 복원, 만료 해제처럼 예약을 끝내는 전이를 모은다
@Service
public class ReservationLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ReservationLifecycle.class);

    private final ReservationRepository reservationRepository;
    private final SellableStock sellableStock;

    public ReservationLifecycle(ReservationRepository reservationRepository, SellableStock sellableStock) {
        this.reservationRepository = reservationRepository;
        this.sellableStock = sellableStock;
    }

    // PAID 를 받으면 PAYING 을 확정한다. 0행이면 다시 읽어, 결제한 주문에 재고가 없는 경우만 경보를 낸다
    @Transactional
    public void confirm(String orderNo) {
        if (reservationRepository.confirmPaying(orderNo) > 0) {
            return;
        }
        for (ReservationRow reservationRow : reservationRepository.findByOrderNo(orderNo)) {
            if (reservationRow.status() == ReservationStatus.RELEASED || reservationRow.status() == ReservationStatus.HELD) {
                log.error("풀린 예약에 온 확정 시도 (orderNo={}, productId={}, status={})",
                        orderNo, reservationRow.productId(), reservationRow.status());
            }
        }
    }

    // 취소 사유와 관계없이 되돌린다. 이미 RELEASED 나 RESTORED 면 되돌릴 것이 없다 (docs/design/02)
    @Transactional
    public void restore(String orderNo, long productId, int quantity) {
        ReservationRow reservationRow = reservationRepository.findForUpdate(orderNo, productId).orElse(null);
        if (reservationRow == null) {
            log.warn("복원할 예약 없음 (orderNo={}, productId={})", orderNo, productId);
            return;
        }
        if (reservationRow.status() == ReservationStatus.RELEASED || reservationRow.status() == ReservationStatus.RESTORED) {
            return;
        }
        int restorable = Math.min(quantity, reservationRow.remainingQuantity());
        giveBack(reservationRow, restorable);
        reservationRepository.addCancelled(reservationRow.reservationId(), restorable);
    }

    @Transactional
    public void releaseExpiredHeld(ReservationRow reservationRow) {
        if (reservationRepository.releaseHeld(reservationRow.reservationId())) {
            giveBack(reservationRow, reservationRow.remainingQuantity());
        }
    }

    @Transactional
    public void releasePaying(String orderNo, int paymentRound) {
        for (ReservationRow reservationRow : reservationRepository.findByOrderNo(orderNo)) {
            if (reservationRepository.releasePaying(reservationRow.reservationId(), paymentRound)) {
                giveBack(reservationRow, reservationRow.remainingQuantity());
            }
        }
    }

    // 슬롯 상품은 예약 때 뺀 슬롯 내역대로 돌려놓고, 돌려놓은 만큼 내역을 줄인다
    private void giveBack(ReservationRow reservationRow, int quantity) {
        sellableStock.increase(reservationRow.productId(), quantity,
                reservationRepository.findSlotAllocations(reservationRow.reservationId()));
        reservationRepository.consumeSlotAllocations(reservationRow.reservationId(), quantity);
    }

    @Transactional
    public void confirmPaying(String orderNo, int paymentRound) {
        reservationRepository.confirmPaying(orderNo, paymentRound);
    }
}
