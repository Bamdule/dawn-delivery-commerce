package io.github.bamdule.dawn.order.placement;

import io.github.bamdule.dawn.event.OrderEvent.OrderLine;
import io.github.bamdule.dawn.order.OrderStatus;
import io.github.bamdule.dawn.order.client.InventoryClient;
import io.github.bamdule.dawn.order.client.ReserveResult;
import io.github.bamdule.dawn.order.store.NewOrder;
import io.github.bamdule.dawn.order.store.NewOrder.NewOrderItem;
import io.github.bamdule.dawn.order.store.OrderRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

// 주문을 먼저 저장하고, 재고와 결제는 트랜잭션 밖에서 부른다 (docs/design/01 "동기 구간")
@Service
public class OrderPlacement {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacement.class);

    private final OrderRepository orderRepository;
    private final OrderNumbers orderNumbers;
    private final SoldOutProducts soldOutProducts;
    private final InventoryClient inventoryClient;
    private final PaymentAttempts paymentAttempts;
    private final TransactionTemplate transactionTemplate;

    public OrderPlacement(OrderRepository orderRepository, OrderNumbers orderNumbers, SoldOutProducts soldOutProducts,
                          InventoryClient inventoryClient, PaymentAttempts paymentAttempts,
                          TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.orderNumbers = orderNumbers;
        this.soldOutProducts = soldOutProducts;
        this.inventoryClient = inventoryClient;
        this.paymentAttempts = paymentAttempts;
        this.transactionTemplate = transactionTemplate;
    }

    public OrderResult place(PlaceOrderCommand placeOrderCommand) {
        // 품절 목록보다 먼저 본다. 응답을 못 받은 앱의 재시도는 그사이 품절이 됐어도 기존 주문의 상태를 받아야 한다
        var existing = orderRepository.findByCheckoutId(placeOrderCommand.checkoutId());
        if (existing.isPresent()) {
            return OrderResult.current(existing.get());
        }
        Optional<Long> soldOut = soldOutProducts.firstSoldOut(placeOrderCommand.productIds());
        if (soldOut.isPresent()) {
            return OrderResult.soldOut(null, soldOut.get());
        }
        String orderNo = orderNumbers.next();
        try {
            transactionTemplate.executeWithoutResult(status -> orderRepository.insert(toNewOrder(orderNo, placeOrderCommand)));
        } catch (DuplicateKeyException duplicateKeyException) {
            // 같은 주문서 ID 의 요청이 동시에 들어와 다른 쪽이 먼저 저장했다
            return OrderResult.current(orderRepository.findByCheckoutId(placeOrderCommand.checkoutId()).orElseThrow());
        }
        ReserveResult reserveResult;
        try {
            reserveResult = inventoryClient.reserve(orderNo, placeOrderCommand.regionCode(), toLines(placeOrderCommand));
        } catch (RestClientException restClientException) {
            // 예약이 실제로 잡혔더라도 HELD 로 만료될 뿐이다
            log.warn("재고 예약 응답 없음 (orderNo={})", orderNo);
            orderRepository.markFailed(orderNo);
            return OrderResult.of(orderNo, OrderStatus.FAILED, OrderOutcome.FAILED);
        }
        if (!reserveResult.reserved()) {
            orderRepository.markFailed(orderNo);
            return OrderResult.soldOut(orderNo, reserveResult.soldOutProductId());
        }
        orderRepository.saveReservationExpiry(orderNo, reserveResult.expiresAt());
        return paymentAttempts.pay(orderNo, placeOrderCommand.paymentMethod());
    }

    private static NewOrder toNewOrder(String orderNo, PlaceOrderCommand placeOrderCommand) {
        List<NewOrderItem> items = placeOrderCommand.items().stream()
                .map(item -> new NewOrderItem(item.productId(), item.quantity(), item.unitPrice()))
                .toList();
        return new NewOrder(orderNo, placeOrderCommand.checkoutId(), placeOrderCommand.memberId(),
                placeOrderCommand.recipientName(), placeOrderCommand.address(), placeOrderCommand.regionCode(), items);
    }

    private static List<OrderLine> toLines(PlaceOrderCommand placeOrderCommand) {
        return placeOrderCommand.items().stream().map(item -> new OrderLine(item.productId(), item.quantity())).toList();
    }
}
