package io.github.bamdule.dawn.order.query;

import io.github.bamdule.dawn.order.OrderStatus;
import io.github.bamdule.dawn.order.store.OrderItemRow;
import io.github.bamdule.dawn.order.store.OrderRepository;
import io.github.bamdule.dawn.order.store.OrderRepository.PaidOrder;
import io.github.bamdule.dawn.order.store.OrderRow;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 출고 배치의 대상 확정 스텝이 회차의 PAID 목록을 받아 출고 대상과 대조하고, 빠진 주문은 상세로 채운다 (docs/design/05)
@RestController
public class OrderQueryController {

    private static final int MAX_PAGE_SIZE = 5_000;
    private static final long CUTOFF_INTERVAL_DAYS = 1;

    private final OrderRepository orderRepository;

    public OrderQueryController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    // 회차는 전 회차 마감 뒤부터 이번 마감까지의 paid_at 이다. 커서는 앞 페이지 마지막 주문의 (paid_at, id) 다
    @GetMapping("/orders/paid")
    public PaidOrdersPage paid(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cutoffAt,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorPaidAt,
                               @RequestParam(defaultValue = "0") long cursorId,
                               @RequestParam(defaultValue = "1000") int size) {
        LocalDateTime previousCutoff = cutoffAt.minusDays(CUTOFF_INTERVAL_DAYS);
        List<PaidOrder> paidOrders = orderRepository.findPaid(previousCutoff, cutoffAt,
                cursorPaidAt == null ? previousCutoff : cursorPaidAt, cursorId, Math.min(size, MAX_PAGE_SIZE));
        PaidOrder last = paidOrders.isEmpty() ? null : paidOrders.getLast();
        return new PaidOrdersPage(paidOrders.stream().map(PaidOrder::orderNo).toList(),
                last == null ? null : last.paidAt(), last == null ? null : last.id());
    }

    @GetMapping("/orders/{orderNo}")
    public ResponseEntity<OrderDetail> detail(@PathVariable String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .map(orderRow -> ResponseEntity.ok(OrderDetail.of(orderRow, orderRepository.findItems(orderRow.id()))))
                .orElse(ResponseEntity.notFound().build());
    }

    // 마지막 페이지면 다음 커서가 비어 있다
    public record PaidOrdersPage(List<String> orderNos, LocalDateTime nextCursorPaidAt, Long nextCursorId) {
    }

    public record OrderDetail(String orderNo, OrderStatus status, long memberId, long totalAmount, long refundedAmount,
                              Integer paidRound, LocalDateTime paidAt, String recipientName, String address,
                              String regionCode, List<Item> items) {

        static OrderDetail of(OrderRow orderRow, List<OrderItemRow> items) {
            return new OrderDetail(orderRow.orderNo(), orderRow.status(), orderRow.memberId(), orderRow.totalAmount(),
                    orderRow.refundedAmount(), orderRow.paidRound(), orderRow.paidAt(), orderRow.recipientName(),
                    orderRow.address(), orderRow.regionCode(),
                    items.stream().map(item -> new Item(item.productId(), item.quantity(), item.cancelledQuantity(),
                            item.unitPrice())).toList());
        }
    }

    public record Item(long productId, int quantity, int cancelledQuantity, long unitPrice) {
    }
}
