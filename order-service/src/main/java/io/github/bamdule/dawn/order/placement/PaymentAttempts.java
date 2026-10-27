package io.github.bamdule.dawn.order.placement;

import io.github.bamdule.dawn.order.OrderStatus;
import io.github.bamdule.dawn.order.client.InventoryClient;
import io.github.bamdule.dawn.order.client.MarkPayingResult;
import io.github.bamdule.dawn.order.client.PaymentClient;
import io.github.bamdule.dawn.order.client.PaymentClient.ApproveResult;
import io.github.bamdule.dawn.order.client.ReservationState;
import io.github.bamdule.dawn.order.client.ReservationStatus;
import io.github.bamdule.dawn.order.store.OrderCompletion;
import io.github.bamdule.dawn.order.store.OrderRepository;
import io.github.bamdule.dawn.order.store.OrderRow;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

// 결제 시도 하나를 시작해서 끝낼 수 있으면 끝낸다. 외부 호출은 모두 트랜잭션 밖이고 주문 DB 는 짧은 갱신만 한다.
// 결과를 모르면 시도를 IN_PROGRESS 로 두고 결제 이벤트나 정리 배치가 끝낸다 (docs/design/01 "동기 구간")
@Service
public class PaymentAttempts {

    private static final Logger log = LoggerFactory.getLogger(PaymentAttempts.class);
    // 사용자 경로에서는 다시 바뀌지 않는 끝 상태라, 읽기만으로 앞 회차를 되돌려도 된다는 근거가 된다
    private static final Set<String> CLOSED_ROUND_STATUSES = Set.of("FAILED", "EXPIRED");

    private final OrderRepository orderRepository;
    private final OrderCompletion orderCompletion;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final TransactionTemplate transactionTemplate;

    public PaymentAttempts(OrderRepository orderRepository, OrderCompletion orderCompletion, InventoryClient inventoryClient,
                           PaymentClient paymentClient, TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.orderCompletion = orderCompletion;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.transactionTemplate = transactionTemplate;
    }

    public OrderResult pay(String orderNo, String paymentMethod) {
        OrderRow orderRow = orderRepository.findByOrderNo(orderNo).orElseThrow(() -> new OrderNotFoundException(orderNo));
        if (orderRow.status() != OrderStatus.PENDING) {
            return OrderResult.current(orderRow);
        }
        if (!orderRepository.startAttempt(orderNo, orderRow.paymentRound())) {
            return OrderResult.current(orderRepository.findByOrderNo(orderNo).orElseThrow());
        }
        int paymentRound = orderRow.paymentRound() + 1;
        Preparation preparation;
        try {
            preparation = prepare(orderNo, paymentRound);
        } catch (RestClientException restClientException) {
            log.warn("결제 시작 표시 실패, 정리 배치에 맡김 (orderNo={}, paymentRound={})", orderNo, paymentRound);
            return OrderResult.of(orderNo, OrderStatus.PENDING, OrderOutcome.RETRY_LATER);
        }
        return switch (preparation) {
            case READY -> approve(orderRow, paymentRound, paymentMethod);
            case RESERVATION_GONE -> closeAsFailed(orderNo, paymentRound);
            case BLOCKED -> endAttempt(orderNo, paymentRound, OrderOutcome.PAYMENT_IN_PROGRESS);
            case NOT_READY -> endAttempt(orderNo, paymentRound, OrderOutcome.RETRY_LATER);
        };
    }

    // 결제 시작 표시가 0행이면 예약의 현재 상태로 나눈다 (docs/design/01 표)
    private Preparation prepare(String orderNo, int paymentRound) {
        MarkPayingResult markPayingResult = inventoryClient.markPaying(orderNo, paymentRound);
        if (!markPayingResult.found()) {
            return Preparation.RESERVATION_GONE;
        }
        if (markPayingResult.marked()) {
            return Preparation.READY;
        }
        ReservationState reservationState = markPayingResult.currentState();
        if (reservationState.status() == ReservationStatus.PAYING) {
            // 이번 회차면 앞선 표시의 응답만 유실된 것이다
            return reservationState.paymentRound() == paymentRound
                    ? Preparation.READY
                    : revertPreviousRound(orderNo, reservationState.paymentRound(), paymentRound);
        }
        if (reservationState.releasedOrExpiredHeld()) {
            return Preparation.RESERVATION_GONE;
        }
        if (reservationState.status() == ReservationStatus.CONFIRMED) {
            log.error("결제 시도 중 이미 확정된 예약 (orderNo={}, paymentRound={})", orderNo, paymentRound);
            return Preparation.BLOCKED;
        }
        return Preparation.NOT_READY;
    }

    // 앞 시도가 거절로 끝났는데 되돌림 호출이 실패해 예약이 앞 회차의 PAYING 으로 남은 경우다
    private Preparation revertPreviousRound(String orderNo, int previousRound, int paymentRound) {
        Optional<String> previousStatus = paymentClient.roundStatus(orderNo, previousRound);
        if (previousStatus.isEmpty() || !CLOSED_ROUND_STATUSES.contains(previousStatus.get())) {
            log.error("앞 회차 미확정으로 결제 시작 보류 (orderNo={}, previousRound={}, status={})",
                    orderNo, previousRound, previousStatus.orElse("NONE"));
            return Preparation.BLOCKED;
        }
        inventoryClient.revertPaying(orderNo, previousRound);
        MarkPayingResult retried = inventoryClient.markPaying(orderNo, paymentRound);
        if (retried.marked()) {
            return Preparation.READY;
        }
        return retried.found() && !retried.currentState().releasedOrExpiredHeld()
                ? Preparation.NOT_READY
                : Preparation.RESERVATION_GONE;
    }

    private OrderResult approve(OrderRow orderRow, int paymentRound, String paymentMethod) {
        String orderNo = orderRow.orderNo();
        ApproveResult approveResult;
        try {
            approveResult = paymentClient.approve(orderNo, paymentRound, orderRow.totalAmount(), paymentMethod);
        } catch (RestClientException restClientException) {
            log.warn("결제 승인 응답 없음 (orderNo={}, paymentRound={})", orderNo, paymentRound);
            return OrderResult.of(orderNo, OrderStatus.PENDING, OrderOutcome.PAYMENT_CHECKING);
        }
        return switch (approveResult.outcome()) {
            case APPROVED -> confirmPaid(orderNo, paymentRound);
            case DECLINED -> decline(orderNo, paymentRound, approveResult.failureCode());
            case SEALED -> afterSealed(orderNo, paymentRound);
            case UNKNOWN, IN_PROGRESS -> OrderResult.of(orderNo, OrderStatus.PENDING, OrderOutcome.PAYMENT_CHECKING);
            case ALREADY_PAID, STALE -> {
                log.error("결제 서비스의 회차 열기 거절 (orderNo={}, paymentRound={}, outcome={})",
                        orderNo, paymentRound, approveResult.outcome());
                yield OrderResult.of(orderNo, OrderStatus.PENDING, OrderOutcome.PAYMENT_CHECKING);
            }
        };
    }

    private OrderResult confirmPaid(String orderNo, int paymentRound) {
        if (orderCompletion.completePaid(orderNo, paymentRound)) {
            return OrderResult.of(orderNo, OrderStatus.PAID, OrderOutcome.PAID);
        }
        return OrderResult.current(orderRepository.findByOrderNo(orderNo).orElseThrow());
    }

    // 거절은 흔하고 사용자는 다른 카드로 다시 결제한다. 주문은 PENDING 그대로 두고 예약을 HELD 로 돌린다
    private OrderResult decline(String orderNo, int paymentRound, String failureCode) {
        try {
            inventoryClient.revertPaying(orderNo, paymentRound);
        } catch (RestClientException restClientException) {
            log.warn("거절 되돌림 실패, 다음 시도가 되돌림 (orderNo={}, paymentRound={})", orderNo, paymentRound);
        }
        orderRepository.endAttempt(orderNo, paymentRound);
        OrderOutcome outcome = PaymentClient.NOT_CALLED.equals(failureCode)
                ? OrderOutcome.PAYMENT_DELAYED
                : OrderOutcome.PAYMENT_DECLINED;
        return new OrderResult(orderNo, OrderStatus.PENDING, outcome, failureCode);
    }

    // 정리 배치나 만료 배치가 이 회차를 먼저 봉인했다. 만료 전이면 다시 결제를 받고, 만료 뒤면 주문을 닫는다
    private OrderResult afterSealed(String orderNo, int paymentRound) {
        orderRepository.endAttempt(orderNo, paymentRound);
        try {
            Optional<ReservationState> reservationState = inventoryClient.state(orderNo);
            if (reservationState.isPresent() && reservationState.get().status() == ReservationStatus.CONFIRMED) {
                log.error("봉인된 회차의 주문에 확정된 예약 (orderNo={}, paymentRound={})", orderNo, paymentRound);
                return OrderResult.of(orderNo, OrderStatus.PENDING, OrderOutcome.RETRY_LATER);
            }
            if (reservationState.isEmpty() || reservationState.get().releasedOrExpiredHeld() || reservationState.get().expired()) {
                orderRepository.markFailed(orderNo);
                return OrderResult.current(orderRepository.findByOrderNo(orderNo).orElseThrow());
            }
            inventoryClient.revertPaying(orderNo, paymentRound);
            return OrderResult.of(orderNo, OrderStatus.PENDING, OrderOutcome.RETRY_PAYMENT);
        } catch (RestClientException restClientException) {
            log.warn("봉인 뒤 예약 확인 실패, 정리 배치에 맡김 (orderNo={}, paymentRound={})", orderNo, paymentRound);
            return OrderResult.of(orderNo, OrderStatus.PENDING, OrderOutcome.RETRY_LATER);
        }
    }

    // 결제를 시작한 뒤에 주문을 FAILED 로 닫는 것은 예약이 만료됐을 때뿐이다. 이번 회차는 결제 서비스에 열리지 않았다
    private OrderResult closeAsFailed(String orderNo, int paymentRound) {
        transactionTemplate.executeWithoutResult(status -> {
            orderRepository.endAttempt(orderNo, paymentRound);
            orderRepository.markFailed(orderNo);
        });
        return OrderResult.current(orderRepository.findByOrderNo(orderNo).orElseThrow());
    }

    private OrderResult endAttempt(String orderNo, int paymentRound, OrderOutcome outcome) {
        orderRepository.endAttempt(orderNo, paymentRound);
        return OrderResult.of(orderNo, OrderStatus.PENDING, outcome);
    }

    private enum Preparation {
        READY,
        RESERVATION_GONE,
        BLOCKED,
        NOT_READY
    }
}
