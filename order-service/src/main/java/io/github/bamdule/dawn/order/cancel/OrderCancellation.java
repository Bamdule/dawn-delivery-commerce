package io.github.bamdule.dawn.order.cancel;

import io.github.bamdule.dawn.event.CancelReason;
import io.github.bamdule.dawn.event.FulfillmentEvent;
import io.github.bamdule.dawn.event.OrderEvent.OrderLine;
import io.github.bamdule.dawn.order.OrderStatus;
import io.github.bamdule.dawn.order.placement.OrderNotFoundException;
import io.github.bamdule.dawn.order.refund.RefundKeys;
import io.github.bamdule.dawn.order.refund.RefundReason;
import io.github.bamdule.dawn.order.refund.RefundRequestRepository;
import io.github.bamdule.dawn.order.store.OrderEventPublisher;
import io.github.bamdule.dawn.order.store.OrderItemRow;
import io.github.bamdule.dawn.order.store.OrderRepository;
import io.github.bamdule.dawn.order.store.OrderRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 취소는 품목 단위다. 품목 취소 수량, 환불 요청, 주문 상태, CANCELLED 이벤트를 한 트랜잭션에 쓴다.
// 같은 사유가 두 번 와도 환불 키가 같아 한 번만 처리된다 (docs/design/04 "보상")
@Service
public class OrderCancellation {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellation.class);

    private final OrderRepository orderRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final CutoffPolicy cutoffPolicy;

    public OrderCancellation(OrderRepository orderRepository, RefundRequestRepository refundRequestRepository,
                             OrderEventPublisher orderEventPublisher, CutoffPolicy cutoffPolicy) {
        this.orderRepository = orderRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.cutoffPolicy = cutoffPolicy;
    }

    // 마감 뒤에는 받지 않는다. 그래서 출고 배치가 대상을 확정한 뒤 대상이 바뀌는 길은 출고 예외뿐이다
    @Transactional
    public CancelOutcome cancelByCustomer(String orderNo) {
        OrderRow orderRow = orderRepository.lockByOrderNo(orderNo).orElseThrow(() -> new OrderNotFoundException(orderNo));
        if (orderRow.status() == OrderStatus.CANCELLED) {
            return CancelOutcome.ALREADY_CANCELLED;
        }
        if (orderRow.status() != OrderStatus.PAID) {
            return CancelOutcome.NOT_CANCELLABLE;
        }
        if (!orderRepository.now().isBefore(cutoffPolicy.cutoffOf(orderRow.paidAt()))) {
            return CancelOutcome.CUTOFF_PASSED;
        }
        List<OrderItemRow> items = orderRepository.findItems(orderRow.id());
        List<OrderLine> remaining = items.stream()
                .filter(item -> item.remainingQuantity() > 0)
                .map(item -> new OrderLine(item.productId(), item.remainingQuantity()))
                .toList();
        cancelLines(orderRow, items, remaining, RefundKeys.customerCancel(orderNo), CancelReason.CUSTOMER,
                RefundReason.CUSTOMER_CANCEL, RefundReason.CUSTOMER_CANCEL);
        return CancelOutcome.CANCELLED;
    }

    @Transactional
    public void cancelUnfillable(FulfillmentEvent fulfillmentEvent) {
        cancelPaidLines(fulfillmentEvent.orderNo(), fulfillmentEvent.lines(),
                RefundKeys.unfillable(fulfillmentEvent.orderNo(), fulfillmentEvent.cutoffAt()), CancelReason.UNFILLABLE,
                RefundReason.UNFILLABLE_PARTIAL, RefundReason.UNFILLABLE_CANCEL);
    }

    @Transactional
    public void cancelByAdmin(FulfillmentEvent fulfillmentEvent) {
        cancelPaidLines(fulfillmentEvent.orderNo(), fulfillmentEvent.lines(),
                RefundKeys.adminCancel(fulfillmentEvent.orderNo(), fulfillmentEvent.exceptionNo()), CancelReason.ADMIN,
                RefundReason.ADMIN_CANCEL, RefundReason.ADMIN_CANCEL);
    }

    private void cancelPaidLines(String orderNo, List<OrderLine> lines, String refundKey, CancelReason cancelReason,
                                 RefundReason partialReason, RefundReason fullReason) {
        OrderRow orderRow = orderRepository.lockByOrderNo(orderNo).orElse(null);
        if (orderRow == null || (orderRow.status() != OrderStatus.PAID && orderRow.status() != OrderStatus.CANCELLED)) {
            log.error("결제 완료 전 주문에 온 품목 취소 (orderNo={}, refundKey={})", orderNo, refundKey);
            return;
        }
        cancelLines(orderRow, orderRepository.findItems(orderRow.id()), lines, refundKey, cancelReason, partialReason, fullReason);
    }

    // 주문 행을 잠근 뒤 품목을 읽어, 두 사유가 겹쳐도 남은 수량까지만 취소한다
    private void cancelLines(OrderRow orderRow, List<OrderItemRow> items, List<OrderLine> requested, String refundKey,
                             CancelReason cancelReason, RefundReason partialReason, RefundReason fullReason) {
        if (refundRequestRepository.exists(refundKey)) {
            return;
        }
        Map<Long, OrderItemRow> itemsByProduct = items.stream()
                .collect(Collectors.toMap(OrderItemRow::productId, Function.identity()));
        List<OrderLine> cancelled = new ArrayList<>();
        long refundAmount = 0;
        for (OrderLine orderLine : requested) {
            OrderItemRow item = itemsByProduct.get(orderLine.productId());
            int quantity = item == null ? 0 : Math.min(orderLine.quantity(), item.remainingQuantity());
            if (quantity == 0) {
                continue;
            }
            orderRepository.addCancelledQuantity(item.id(), quantity);
            cancelled.add(new OrderLine(item.productId(), quantity));
            refundAmount += item.unitPrice() * quantity;
        }
        if (cancelled.isEmpty()) {
            return;
        }
        boolean allCancelled = orderRepository.findItems(orderRow.id()).stream().allMatch(item -> item.remainingQuantity() == 0);
        refundRequestRepository.insert(orderRow.orderNo(), refundKey, orderRow.paidRound(), refundAmount,
                allCancelled ? fullReason : partialReason);
        if (allCancelled) {
            orderRepository.markCancelled(orderRow.id(), cancelReason);
        }
        orderEventPublisher.cancelled(orderRow, cancelled, cancelReason, refundKey);
    }

    // 결제되지 않은 주문인데 예약이 확정돼 있다. 그 승인은 쓰이지 않은 승인으로 환불되므로 주문을 닫고 재고를 돌려놓는다 (docs/design/01 "수렴 책임")
    @Transactional
    public void failWithConfirmedReservation(String orderNo) {
        if (!orderRepository.markFailed(orderNo)) {
            return;
        }
        OrderRow orderRow = orderRepository.findByOrderNo(orderNo).orElseThrow();
        List<OrderLine> lines = orderRepository.findItems(orderRow.id()).stream()
                .map(item -> new OrderLine(item.productId(), item.quantity()))
                .toList();
        orderEventPublisher.cancelled(orderRow, lines, CancelReason.ORPHAN, "failed-with-confirmed-reservation");
        log.error("결제되지 않은 주문의 확정 예약 복원 요청 (orderNo={})", orderNo);
    }
}
