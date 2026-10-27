package io.github.bamdule.dawn.inventory.reservation;

import io.github.bamdule.dawn.inventory.stock.OutOfStockException;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 주문 서비스가 동기 흐름과 정리 배치에서 부른다. 모든 호출은 다시 보내도 결과가 같다
@RestController
public class ReservationController {

    private final Reservations reservations;

    public ReservationController(Reservations reservations) {
        this.reservations = reservations;
    }

    @PostMapping("/reservations")
    public ReserveResponse reserve(@RequestBody ReservationCommand reservationCommand) {
        return new ReserveResponse(reservations.reserve(reservationCommand));
    }

    @PostMapping("/reservations/{orderNo}/paying")
    public MarkPayingResult markPaying(@PathVariable String orderNo, @RequestBody MarkPayingRequest markPayingRequest) {
        return reservations.markPaying(orderNo, markPayingRequest.paymentRound());
    }

    @PostMapping("/reservations/{orderNo}/paying/{paymentRound}/revert")
    public RevertResponse revertPaying(@PathVariable String orderNo, @PathVariable int paymentRound) {
        return new RevertResponse(reservations.revertPaying(orderNo, paymentRound));
    }

    // 예약이 없으면 404 다. 정리 배치는 이것을 "예약 없음"으로 읽고 주문을 닫는다
    @GetMapping("/reservations/{orderNo}")
    public ResponseEntity<ReservationState> state(@PathVariable String orderNo) {
        return ResponseEntity.of(reservations.currentState(orderNo));
    }

    @ExceptionHandler(OutOfStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public OutOfStockResponse outOfStock(OutOfStockException outOfStockException) {
        return new OutOfStockResponse(outOfStockException.getProductId(), outOfStockException.getMessage());
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }

    public record ReserveResponse(LocalDateTime expiresAt) {
    }

    public record MarkPayingRequest(int paymentRound) {
    }

    public record RevertResponse(boolean reverted) {
    }

    public record OutOfStockResponse(long productId, String message) {
    }
}
