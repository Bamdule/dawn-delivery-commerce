package io.github.bamdule.dawn.order.placement;

import io.github.bamdule.dawn.order.cancel.CancelOutcome;
import io.github.bamdule.dawn.order.cancel.OrderCancellation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderPlacement orderPlacement;
    private final PaymentAttempts paymentAttempts;
    private final OrderCancellation orderCancellation;

    public OrderController(OrderPlacement orderPlacement, PaymentAttempts paymentAttempts,
                           OrderCancellation orderCancellation) {
        this.orderPlacement = orderPlacement;
        this.paymentAttempts = paymentAttempts;
        this.orderCancellation = orderCancellation;
    }

    @PostMapping("/orders")
    public OrderResult place(@RequestBody PlaceOrderCommand placeOrderCommand) {
        return orderPlacement.place(placeOrderCommand);
    }

    // 카드가 거절된 뒤 다른 카드로 다시 결제한다. 앞 시도가 끝나지 않았으면 새 시도를 시작하지 않는다
    @PostMapping("/orders/{orderNo}/payments")
    public OrderResult pay(@PathVariable String orderNo, @RequestBody PayRequest payRequest) {
        return paymentAttempts.pay(orderNo, payRequest.paymentMethod());
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public CancelResponse cancel(@PathVariable String orderNo) {
        return new CancelResponse(orderNo, orderCancellation.cancelByCustomer(orderNo));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }

    public record PayRequest(String paymentMethod) {
    }

    public record CancelResponse(String orderNo, CancelOutcome outcome) {
    }
}
