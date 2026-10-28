package io.github.bamdule.dawn.fulfillment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import io.github.bamdule.dawn.event.OrderEvent;
import io.github.bamdule.dawn.event.OrderEvent.Delivery;
import io.github.bamdule.dawn.event.OrderEvent.OrderLine;
import io.github.bamdule.dawn.event.OrderEventType;
import io.github.bamdule.dawn.fulfillment.job.ShipmentJobRunner;
import io.github.bamdule.dawn.fulfillment.target.OrderClient;
import io.github.bamdule.dawn.fulfillment.target.OrderClient.Item;
import io.github.bamdule.dawn.fulfillment.target.OrderClient.OrderDetail;
import io.github.bamdule.dawn.fulfillment.target.OrderClient.PaidPage;
import io.github.bamdule.dawn.fulfillment.target.OrderEventConsumer;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 주문 서비스는 가짜로 두고 풀필먼트 DB 는 실제 MySQL 을 쓴다. 스케줄러는 끄고 테스트가 마감 시각을 정해 잡을 돌린다.
// 센터는 서울 둘, 서울에 새벽 도착이 되는 대전 하나, 부산 하나다
@SpringBootTest(properties = {
        "dawn.fulfillment.scheduler.enabled=false",
        "dawn.outbox.relay.enabled=false",
        "dawn.fulfillment.target-fix.retry-delay=0s",
        "management.defaults.metrics.export.enabled=true",
        "dawn.metrics.refresh.enabled=false"})
@Import({MySqlContainers.class, KafkaContainers.class})
public abstract class FulfillmentIntegrationTest {

    protected static final String SEOUL_A = "SEOUL-A";
    protected static final String SEOUL_B = "SEOUL-B";
    protected static final String DAEJEON_A = "DAEJEON-A";
    protected static final String BUSAN_A = "BUSAN-A";

    private static final AtomicLong NEXT_PRODUCT_ID = new AtomicLong(900_000);
    private static final AtomicLong NEXT_ORDER = new AtomicLong();
    // 테스트마다 회차가 겹치지 않게 열흘씩 띄운다. 뒤에 도는 테스트일수록 이른 회차라 앞 테스트가 남긴 대기 수량을 이월로 가져가지 않는다
    private static final AtomicInteger NEXT_CUTOFF_OFFSET = new AtomicInteger();
    private static final LocalDateTime LATEST_CUTOFF = LocalDateTime.of(2090, 12, 31, 23, 0);

    @MockitoBean
    protected OrderClient orderClient;

    @Autowired
    protected OrderEventConsumer orderEventConsumer;

    @Autowired
    protected ShipmentJobRunner shipmentJobRunner;

    @Autowired
    protected JdbcClient jdbcClient;

    private final Map<LocalDateTime, List<String>> paidLists = new HashMap<>();
    private final Map<String, OrderDetail> details = new HashMap<>();

    @BeforeEach
    void centersAndOrderService() {
        reset(orderClient);
        paidLists.clear();
        details.clear();
        insertCenter(SEOUL_A, "SEOUL");
        insertCenter(SEOUL_B, "SEOUL");
        insertCenter(DAEJEON_A, "DAEJEON");
        insertCenter(BUSAN_A, "BUSAN");
        jdbcClient.sql("INSERT IGNORE INTO center_coverage (center_id, region_code) VALUES ('DAEJEON-A', 'SEOUL')").update();
        when(orderClient.paidOrders(any(), any(), any(), anyInt())).thenAnswer(invocation ->
                new PaidPage(paidLists.getOrDefault(invocation.<LocalDateTime>getArgument(0), List.of()), null, null));
        when(orderClient.detail(any())).thenAnswer(invocation -> Optional.ofNullable(details.get(invocation.<String>getArgument(0))));
    }

    protected static LocalDateTime newCutoff() {
        return LATEST_CUTOFF.minusDays(10L * NEXT_CUTOFF_OFFSET.getAndIncrement());
    }

    protected static long newProductId() {
        return NEXT_PRODUCT_ID.incrementAndGet();
    }

    protected static String newOrderNo() {
        return "20901231-F" + String.format("%09d", NEXT_ORDER.incrementAndGet());
    }

    // 주문 서비스가 PAID 를 내고 풀필먼트가 받은 상태를 만든다. 주문 서비스의 결제 완료 목록에도 올린다
    protected String paid(LocalDateTime cutoffAt, LocalDateTime paidAt, String regionCode, OrderLine... lines) {
        String orderNo = paidWithoutEvent(cutoffAt, paidAt, regionCode, lines);
        orderEventConsumer.handle(paidEvent(orderNo, paidAt, regionCode, lines));
        return orderNo;
    }

    // PAID 이벤트가 밀렸거나 처리 실패 토픽으로 빠진 주문이다. 주문 서비스에는 있다
    protected String paidWithoutEvent(LocalDateTime cutoffAt, LocalDateTime paidAt, String regionCode, OrderLine... lines) {
        String orderNo = newOrderNo();
        if (cutoffAt != null) {
            paidLists.computeIfAbsent(cutoffAt, key -> new ArrayList<>()).add(orderNo);
        }
        details.put(orderNo, new OrderDetail(orderNo, "PAID", "김새벽", "서울시 강남구 테헤란로 1", regionCode, paidAt,
                Arrays.stream(lines).map(line -> new Item(line.productId(), line.quantity(), 0)).toList()));
        return orderNo;
    }

    protected void listInRound(LocalDateTime cutoffAt, String orderNo) {
        paidLists.computeIfAbsent(cutoffAt, key -> new ArrayList<>()).add(orderNo);
    }

    protected OrderEvent paidEvent(String orderNo, LocalDateTime paidAt, String regionCode, OrderLine... lines) {
        return new OrderEvent(UUID.randomUUID(), OrderEventType.PAID, orderNo, 1L, 10_000L, List.of(lines), null,
                new Delivery("김새벽", "서울시 강남구 테헤란로 1", regionCode), paidAt, Instant.now());
    }

    protected void stock(String centerId, long productId, int quantity) {
        jdbcClient.sql("""
                INSERT INTO center_stock (center_id, product_id, quantity, updated_at) VALUES (:centerId, :productId, :quantity, NOW(3))
                ON DUPLICATE KEY UPDATE quantity = :quantity, updated_at = NOW(3)
                """)
                .param("centerId", centerId).param("productId", productId).param("quantity", quantity).update();
    }

    protected int stockOf(String centerId, long productId) {
        return jdbcClient.sql("SELECT quantity FROM center_stock WHERE center_id = :centerId AND product_id = :productId")
                .param("centerId", centerId).param("productId", productId).query(Integer.class).single();
    }

    protected JobExecution run(LocalDateTime cutoffAt) throws Exception {
        return shipmentJobRunner.run(cutoffAt);
    }

    protected String runStatus(LocalDateTime cutoffAt) {
        return jdbcClient.sql("SELECT status FROM cutoff_run WHERE cutoff_at = :cutoffAt")
                .param("cutoffAt", cutoffAt).query(String.class).optional().orElse("NONE");
    }

    // 대기, 출고, 못 채움, 예외, 취소 순서
    protected List<Integer> quantities(String orderNo, long productId) {
        return jdbcClient.sql("""
                SELECT waiting_qty, shipped_qty, unfilled_qty, exception_qty, cancelled_qty FROM shipment_target_line
                 WHERE order_no = :orderNo AND product_id = :productId
                """)
                .param("orderNo", orderNo).param("productId", productId)
                .query((rs, rowNum) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)))
                .single();
    }

    protected Map<String, Integer> shippedByCenter(LocalDateTime cutoffAt, String orderNo) {
        Map<String, Integer> shipped = new HashMap<>();
        jdbcClient.sql("""
                SELECT o.center_id, SUM(l.quantity) AS quantity FROM shipment_order o JOIN shipment_order_line l ON l.shipment_order_id = o.id
                 WHERE o.cutoff_at = :cutoffAt AND o.order_no = :orderNo AND o.status = 'CREATED' GROUP BY o.center_id
                """)
                .param("cutoffAt", cutoffAt).param("orderNo", orderNo)
                .query(rs -> {
                    shipped.put(rs.getString("center_id"), rs.getInt("quantity"));
                });
        return shipped;
    }

    protected List<String> outboxTypes(String partitionKey) {
        return jdbcClient.sql("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.type')) FROM outbox_event WHERE partition_key = :partitionKey ORDER BY seq
                """)
                .param("partitionKey", partitionKey).query(String.class).list();
    }

    protected static OrderLine line(long productId, int quantity) {
        return new OrderLine(productId, quantity);
    }

    private void insertCenter(String centerId, String regionCode) {
        jdbcClient.sql("INSERT IGNORE INTO center (center_id, region_code) VALUES (:centerId, :regionCode)")
                .param("centerId", centerId).param("regionCode", regionCode).update();
    }
}
