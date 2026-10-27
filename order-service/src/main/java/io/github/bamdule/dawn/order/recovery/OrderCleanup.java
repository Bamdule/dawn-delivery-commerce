package io.github.bamdule.dawn.order.recovery;

import io.github.bamdule.dawn.order.cancel.OrderCancellation;
import io.github.bamdule.dawn.order.client.InventoryClient;
import io.github.bamdule.dawn.order.client.PaymentClient;
import io.github.bamdule.dawn.order.client.ReservationState;
import io.github.bamdule.dawn.order.client.ReservationStatus;
import io.github.bamdule.dawn.order.client.SealOutcome;
import io.github.bamdule.dawn.order.store.OrderCompletion;
import io.github.bamdule.dawn.order.store.OrderRepository;
import io.github.bamdule.dawn.order.store.OrderRepository.ExpiredPending;
import io.github.bamdule.dawn.order.store.OrderRepository.StuckAttempt;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

// 주문 서비스가 시도 중간에 죽었거나 결제 이벤트를 놓친 주문을 끝낸다 (docs/design/04 "정리 배치")
@Component
public class OrderCleanup {

    private static final Logger log = LoggerFactory.getLogger(OrderCleanup.class);
    private static final int BATCH_LIMIT = 500;

    private final OrderRepository orderRepository;
    private final OrderCompletion orderCompletion;
    private final OrderCancellation orderCancellation;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;

    public OrderCleanup(OrderRepository orderRepository, OrderCompletion orderCompletion,
                        OrderCancellation orderCancellation, InventoryClient inventoryClient, PaymentClient paymentClient) {
        this.orderRepository = orderRepository;
        this.orderCompletion = orderCompletion;
        this.orderCancellation = orderCancellation;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
    }

    public void run() {
        resolveStuckAttempts();
        closeExpiredPending();
    }

    // 한 건의 예상 못 한 오류가 나머지 주문을 막지 않게 건마다 따로 잡는다.
    // 1분 넘은 시도는 그 회차의 봉인을 요청한다. 봉인되면 늦게 온 승인 요청도 그 회차를 열지 못한다
    public void resolveStuckAttempts() {
        for (StuckAttempt stuckAttempt : orderRepository.findStuckAttempts(BATCH_LIMIT)) {
            try {
                resolve(stuckAttempt);
            } catch (RestClientException restClientException) {
                log.warn("정리 배치 시도 확인 실패 (orderNo={}, paymentRound={})",
                        stuckAttempt.orderNo(), stuckAttempt.paymentRound());
            } catch (RuntimeException runtimeException) {
                log.error("정리 배치 시도 처리 오류 (orderNo={})", stuckAttempt.orderNo(), runtimeException);
            }
        }
    }

    private void resolve(StuckAttempt stuckAttempt) {
        String orderNo = stuckAttempt.orderNo();
        int paymentRound = stuckAttempt.paymentRound();
        SealOutcome sealOutcome = paymentClient.seal(orderNo, paymentRound);
        switch (sealOutcome) {
            case APPROVED -> orderCompletion.completePaid(orderNo, paymentRound);
            case SEALED, FAILED, EXPIRED -> {
                // 되돌림이 실패하면 시도를 끝내지 않고 다음 주기에 다시 한다
                inventoryClient.revertPaying(orderNo, paymentRound);
                orderRepository.endAttempt(orderNo, paymentRound);
            }
            case REQUESTED, UNKNOWN -> {
                if (stuckAttempt.overdue()) {
                    log.error("결제 시도 15분 넘게 미확정 (orderNo={}, paymentRound={}, outcome={})",
                            orderNo, paymentRound, sealOutcome);
                }
            }
        }
    }

    // 시도가 IDLE 이면 열려 있는 회차가 없다. 예약이 PAYING 이 아니면 새 회차도 열리지 않아 닫아도 된다
    public void closeExpiredPending() {
        for (ExpiredPending expiredPending : orderRepository.findExpiredIdlePending(BATCH_LIMIT)) {
            try {
                close(expiredPending);
            } catch (RestClientException restClientException) {
                log.warn("정리 배치 예약 확인 실패 (orderNo={})", expiredPending.orderNo());
            } catch (RuntimeException runtimeException) {
                log.error("정리 배치 주문 처리 오류 (orderNo={})", expiredPending.orderNo(), runtimeException);
            }
        }
    }

    private void close(ExpiredPending expiredPending) {
        String orderNo = expiredPending.orderNo();
        Optional<ReservationState> reservationState = inventoryClient.state(orderNo);
        if (reservationState.isEmpty() || reservationState.get().releasedOrExpiredHeld()) {
            orderRepository.markFailed(orderNo);
            return;
        }
        if (reservationState.get().status() == ReservationStatus.CONFIRMED) {
            orderCancellation.failWithConfirmedReservation(orderNo);
            return;
        }
        if (expiredPending.overdue()) {
            log.error("PENDING 주문 15분 넘게 미확정 (orderNo={}, reservationStatus={})",
                    orderNo, reservationState.get().status());
        }
    }
}
