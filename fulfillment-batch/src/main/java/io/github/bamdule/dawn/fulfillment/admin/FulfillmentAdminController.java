package io.github.bamdule.dawn.fulfillment.admin;

import io.github.bamdule.dawn.fulfillment.job.ShipmentJobRunner;
import io.github.bamdule.dawn.fulfillment.run.CutoffRunRepository;
import io.github.bamdule.dawn.fulfillment.run.CutoffRunStatus;
import io.github.bamdule.dawn.fulfillment.shipment.ShipmentHandover;
import io.github.bamdule.dawn.fulfillment.stock.CenterStocks;
import io.github.bamdule.dawn.fulfillment.target.ShipmentTargetRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 사람이 개입하는 풀필먼트 경로를 모은다. 상태를 바꾸는 호출은 모두 조건부 갱신이라 0행이면 409 로 답한다
@RestController
public class FulfillmentAdminController {

    private final ShipmentExceptions shipmentExceptions;
    private final TargetCorrections targetCorrections;
    private final ShipmentTargetRepository shipmentTargetRepository;
    private final CutoffRunRepository cutoffRunRepository;
    private final CenterStocks centerStocks;
    private final ShipmentJobRunner shipmentJobRunner;
    private final JdbcClient jdbcClient;
    private final ShipmentHandover shipmentHandover;

    public FulfillmentAdminController(ShipmentExceptions shipmentExceptions, TargetCorrections targetCorrections,
                                      ShipmentTargetRepository shipmentTargetRepository, CutoffRunRepository cutoffRunRepository,
                                      CenterStocks centerStocks, ShipmentJobRunner shipmentJobRunner, JdbcClient jdbcClient,
                                      ShipmentHandover shipmentHandover) {
        this.shipmentExceptions = shipmentExceptions;
        this.targetCorrections = targetCorrections;
        this.shipmentTargetRepository = shipmentTargetRepository;
        this.cutoffRunRepository = cutoffRunRepository;
        this.centerStocks = centerStocks;
        this.shipmentJobRunner = shipmentJobRunner;
        this.jdbcClient = jdbcClient;
        this.shipmentHandover = shipmentHandover;
    }

    @PostMapping("/admin/shipment-exceptions/{exceptionNo}/defer")
    public ResponseEntity<Void> defer(@PathVariable long exceptionNo) {
        return changed(shipmentExceptions.defer(exceptionNo));
    }

    @PostMapping("/admin/shipment-exceptions/{exceptionNo}/cancel")
    public ResponseEntity<Void> cancelException(@PathVariable long exceptionNo) {
        return changed(shipmentExceptions.cancel(exceptionNo));
    }

    @PostMapping("/admin/shipment-orders/{shipmentOrderId}/cancel")
    public ResponseEntity<Void> cancelShipmentOrder(@PathVariable long shipmentOrderId) {
        return changed(shipmentExceptions.cancelShipmentOrder(shipmentOrderId));
    }

    // 배차가 부른다. 검증을 통과한 회차가 아니면 409
    @PostMapping("/admin/cutoff-runs/{cutoffAt}/hand-over")
    public ResponseEntity<HandoverResponse> handOver(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cutoffAt) {
        return shipmentHandover.handOver(cutoffAt)
                .map(handedOver -> ResponseEntity.ok(new HandoverResponse(handedOver)))
                .orElseGet(() -> ResponseEntity.status(409).build());
    }

    @PostMapping("/admin/cutoff-runs/{cutoffAt}/approve")
    public ResponseEntity<Void> approve(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cutoffAt) {
        return changed(cutoffRunRepository.transition(cutoffAt, CutoffRunStatus.VERIFY_FAILED, CutoffRunStatus.VERIFIED));
    }

    // 실패한 잡을 원인을 고친 뒤 같은 마감 시각으로 다시 돌린다
    @PostMapping("/admin/cutoff-runs/{cutoffAt}/start")
    public ResponseEntity<StartResponse> start(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cutoffAt)
            throws Exception {
        return ResponseEntity.ok(new StartResponse(shipmentJobRunner.run(cutoffAt).getStatus().name()));
    }

    @GetMapping("/admin/cutoff-runs/{cutoffAt}/target-diffs")
    public List<TargetDiff> targetDiffs(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cutoffAt) {
        return jdbcClient.sql("SELECT order_no, kind FROM target_fix_diff WHERE cutoff_at = :cutoffAt ORDER BY order_no")
                .param("cutoffAt", cutoffAt).query(TargetDiff.class).list();
    }

    @PostMapping("/admin/shipment-targets/{orderNo}/resync")
    public ResponseEntity<Void> resync(@PathVariable String orderNo) {
        return changed(targetCorrections.resync(orderNo));
    }

    @PutMapping("/admin/shipment-targets/{orderNo}/address")
    public ResponseEntity<Void> updateAddress(@PathVariable String orderNo, @RequestBody AddressRequest addressRequest) {
        shipmentTargetRepository.updateAddress(orderNo, addressRequest.recipientName(), addressRequest.address());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/center-stocks/{centerId}/{productId}/inbound")
    public ResponseEntity<Void> inbound(@PathVariable String centerId, @PathVariable long productId,
                                        @RequestBody InboundRequest inboundRequest) {
        centerStocks.inbound(centerId, productId, inboundRequest.quantity());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/center-stocks/{centerId}/{productId}/stocktake")
    public StocktakeResponse stocktake(@PathVariable String centerId, @PathVariable long productId,
                                       @RequestBody StocktakeRequest stocktakeRequest) {
        return new StocktakeResponse(centerStocks.stocktake(centerId, productId, stocktakeRequest.countedQuantity()));
    }

    private static ResponseEntity<Void> changed(boolean changed) {
        return changed ? ResponseEntity.noContent().build() : ResponseEntity.status(409).build();
    }

    public record StartResponse(String status) {
    }

    public record HandoverResponse(int handedOver) {
    }

    public record TargetDiff(String orderNo, String kind) {
    }

    public record AddressRequest(String recipientName, String address) {
    }

    public record InboundRequest(int quantity) {
    }

    public record StocktakeRequest(int countedQuantity) {
    }

    // 실물 재고에 실제로 더하거나 뺀 수량. 차이가 없으면 0 이다
    public record StocktakeResponse(int appliedDelta) {
    }
}
