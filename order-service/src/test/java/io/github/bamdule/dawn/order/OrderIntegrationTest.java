package io.github.bamdule.dawn.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import io.github.bamdule.dawn.order.client.ApproveOutcome;
import io.github.bamdule.dawn.order.client.InventoryClient;
import io.github.bamdule.dawn.order.client.MarkPayingResult;
import io.github.bamdule.dawn.order.client.PaymentClient;
import io.github.bamdule.dawn.order.client.PaymentClient.ApproveResult;
import io.github.bamdule.dawn.order.client.PaymentClient.RefundAcceptResult;
import io.github.bamdule.dawn.order.client.ReservationState;
import io.github.bamdule.dawn.order.client.ReservationStatus;
import io.github.bamdule.dawn.order.client.ReserveResult;
import io.github.bamdule.dawn.order.client.SealOutcome;
import io.github.bamdule.dawn.order.placement.OrderPlacement;
import io.github.bamdule.dawn.order.placement.OrderResult;
import io.github.bamdule.dawn.order.placement.PlaceOrderCommand;
import io.github.bamdule.dawn.order.placement.SoldOutProducts;
import io.github.bamdule.dawn.order.store.OrderRepository;
import io.github.bamdule.dawn.order.store.OrderRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 재고와 결제 서비스는 가짜로 두고 주문 DB 는 실제 MySQL 을 쓴다. 주기 작업은 끄고 테스트가 직접 부른다.
// 하위 클래스가 같은 설정을 써서 스프링 컨텍스트 하나를 함께 쓴다
@SpringBootTest(properties = {"dawn.order.jobs.enabled=false", "dawn.outbox.relay.enabled=false"})
@Import({MySqlContainers.class, KafkaContainers.class})
public abstract class OrderIntegrationTest {

    private static final AtomicLong NEXT_PRODUCT_ID = new AtomicLong(700_000);

    @MockitoBean
    protected InventoryClient inventoryClient;

    @MockitoBean
    protected PaymentClient paymentClient;

    @Autowired
    protected OrderPlacement orderPlacement;

    @Autowired
    protected OrderRepository orderRepository;

    @Autowired
    protected SoldOutProducts soldOutProducts;

    @Autowired
    protected JdbcClient jdbcClient;

    // 기본은 모두 순조롭다. 테스트마다 필요한 호출만 바꾼다
    @BeforeEach
    void defaultCollaborators() {
        reset(inventoryClient, paymentClient);
        when(inventoryClient.soldOutProductIds()).thenReturn(List.of());
        when(inventoryClient.reserve(any(), any(), any())).thenReturn(ReserveResult.reserved(LocalDateTime.now().plusMinutes(10)));
        when(inventoryClient.markPaying(any(), anyInt())).thenAnswer(invocation ->
                new MarkPayingResult(true, true, ReservationStatus.PAYING, invocation.getArgument(1),
                        LocalDateTime.now().plusMinutes(10), false));
        when(inventoryClient.revertPaying(any(), anyInt())).thenReturn(true);
        when(inventoryClient.state(any())).thenReturn(Optional.of(
                new ReservationState(ReservationStatus.HELD, null, LocalDateTime.now().plusMinutes(10), false)));
        when(paymentClient.approve(any(), anyInt(), anyLong(), any())).thenAnswer(invocation ->
                new ApproveResult(ApproveOutcome.APPROVED, invocation.getArgument(1), null));
        when(paymentClient.seal(any(), anyInt())).thenReturn(SealOutcome.SEALED);
        // 환불 배치는 앞선 테스트가 남긴 환불도 함께 집는다. 기본은 접수만 하고 끝내지 않는다
        when(paymentClient.acceptRefund(any(), any(), anyInt(), anyLong())).thenReturn(new RefundAcceptResult(true, "REQUESTED"));
        when(paymentClient.refundStatus(any())).thenReturn(Optional.of("REQUESTED"));
        soldOutProducts.refresh();
    }

    protected static long newProductId() {
        return NEXT_PRODUCT_ID.incrementAndGet();
    }

    protected static PlaceOrderCommand command(String checkoutId, PlaceOrderCommand.Item... items) {
        return new PlaceOrderCommand(checkoutId, 1L, "김새벽", "서울시 강남구 테헤란로 1", "SEOUL", "CARD", List.of(items));
    }

    protected static PlaceOrderCommand.Item item(long productId, int quantity, long unitPrice) {
        return new PlaceOrderCommand.Item(productId, quantity, unitPrice);
    }

    protected static String newCheckoutId() {
        return UUID.randomUUID().toString();
    }

    protected OrderResult placeOne(long unitPrice) {
        return orderPlacement.place(command(newCheckoutId(), item(newProductId(), 1, unitPrice)));
    }

    protected OrderRow order(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).orElseThrow();
    }

    protected List<String> outboxTypes(String orderNo) {
        return jdbcClient.sql("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.type')) FROM outbox_event
                 WHERE partition_key = :orderNo ORDER BY seq
                """)
                .param("orderNo", orderNo)
                .query(String.class)
                .list();
    }

    protected void ageAttempt(String orderNo, int seconds) {
        jdbcClient.sql("UPDATE orders SET attempt_started_at = NOW(3) - INTERVAL :seconds SECOND WHERE order_no = :orderNo")
                .param("seconds", seconds).param("orderNo", orderNo).update();
    }

    protected void expireReservation(String orderNo) {
        jdbcClient.sql("UPDATE orders SET reservation_expires_at = NOW(3) - INTERVAL 1 SECOND WHERE order_no = :orderNo")
                .param("orderNo", orderNo).update();
    }
}
