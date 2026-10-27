package io.github.bamdule.dawn.order.store;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 결제 확정은 동기 응답, 결제 이벤트, 정리 배치 세 경로에서 온다. 회차 조건 갱신이 1행일 때만 PAID 이벤트를 쓴다 (docs/design/03)
@Service
public class OrderCompletion {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;

    public OrderCompletion(OrderRepository orderRepository, OrderEventPublisher orderEventPublisher) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
    }

    @Transactional
    public boolean completePaid(String orderNo, int paymentRound) {
        if (!orderRepository.markPaid(orderNo, paymentRound)) {
            return false;
        }
        OrderRow orderRow = orderRepository.findByOrderNo(orderNo).orElseThrow();
        orderEventPublisher.paid(orderRow, orderRepository.findItems(orderRow.id()));
        return true;
    }
}
