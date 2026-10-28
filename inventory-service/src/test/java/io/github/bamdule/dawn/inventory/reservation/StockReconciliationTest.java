package io.github.bamdule.dawn.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import io.github.bamdule.dawn.inventory.reconciliation.FulfillmentStockClient;
import io.github.bamdule.dawn.inventory.reconciliation.FulfillmentStockClient.StockSummary;
import io.github.bamdule.dawn.inventory.reconciliation.StockReconciler;
import io.github.bamdule.dawn.inventory.stock.SellableStock;
import io.github.bamdule.dawn.inventory.stock.StockLine;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

// 풀필먼트는 가짜로 두고 상품마다 실물 합과 출고 전 확정 수량을 정해 준다
@SpringBootTest(properties = {"dawn.reservation.expiry-interval=1h", "dawn.reconciliation.enabled=false"})
@AutoConfigureMockMvc
@Import({MySqlContainers.class, KafkaContainers.class})
class StockReconciliationTest {

    @MockitoBean
    private FulfillmentStockClient fulfillmentStockClient;

    @MockitoBean
    private PaymentRoundSealer paymentRoundSealer;

    @Autowired
    private StockReconciler stockReconciler;

    @Autowired
    private Reservations reservations;

    @Autowired
    private SellableStock sellableStock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private JdbcClient jdbcClient;

    private final Map<Long, StockSummary> fulfillment = new ConcurrentHashMap<>();

    @BeforeEach
    void fakeFulfillment() {
        // 다른 테스트가 만든 상품은 식이 맞는 것으로 둬서 이 테스트의 상품만 본다
        when(fulfillmentStockClient.summary(anyList())).thenAnswer(invocation -> invocation.<List<Long>>getArgument(0).stream()
                .map(productId -> fulfillment.getOrDefault(productId, balancedFor(productId)))
                .toList());
    }

    @Test
    void 판매_가능_재고와_잡힌_예약과_출고_전_수량의_합이_실물과_같으면_아무것도_남기지_않는다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        reserve(productId, 2);
        fulfillment.put(productId, new StockSummary(productId, 13, 3));

        stockReconciler.reconcileAll();

        assertThat(statuses(productId)).isEmpty();
    }

    @Test
    void 부족분은_판매_가능_재고에서_뺀다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        for (int index = 0; index < 10; index++) {
            reserve(productId, 1);
        }
        // 다 잡힌 상품을 실사가 2개 줄였다. 판매 가능 0, 부족분 2, 잡힌 예약 10, 실물 8 이다
        transactionTemplate.executeWithoutResult(status -> sellableStock.decreaseForStocktake(UUID.randomUUID().toString(), productId, 2));
        fulfillment.put(productId, new StockSummary(productId, 8, 0));

        stockReconciler.reconcileAll();

        assertThat(statuses(productId)).isEmpty();
    }

    @Test
    void 십_분_뒤_다시_재도_어긋나면_확정하고_경보_목록에_오른다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        fulfillment.put(productId, new StockSummary(productId, 12, 0));

        stockReconciler.reconcileAll();
        stockReconciler.reconcileAll();
        stockReconciler.recheckSuspected();
        assertThat(statuses(productId)).as("하루 대사를 두 번 돌려도 열린 기록은 하나이고, 10분 전에는 다시 재지 않는다").containsExactly("SUSPECTED");

        ageSuspected(productId);
        stockReconciler.recheckSuspected();

        assertThat(statuses(productId)).containsExactly("CONFIRMED");
        assertThat(mockMvcTester.get().uri("/admin/stock-reconciliations"))
                .hasStatusOk().bodyJson().extractingPath("$[?(@.productId == " + productId + ")].rightSide").asArray().containsExactly(12);
    }

    @Test
    void 다시_잴_때_맞으면_순간의_흔들림으로_보고_닫는다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        fulfillment.put(productId, new StockSummary(productId, 12, 0));
        stockReconciler.reconcileAll();

        // 출고 대상에 오르지 않았던 PAID 가 그사이 등록돼 출고 전 수량이 됐다
        fulfillment.put(productId, new StockSummary(productId, 12, 2));
        ageSuspected(productId);
        stockReconciler.recheckSuspected();

        assertThat(statuses(productId)).containsExactly("RESOLVED");
    }

    @Test
    void 사람이_차이만큼_보정하면_기록이_닫히고_다음_대사에서_맞는다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        fulfillment.put(productId, new StockSummary(productId, 12, 0));
        stockReconciler.reconcileAll();
        ageSuspected(productId);
        stockReconciler.recheckSuspected();

        assertThat(mockMvcTester.post().uri("/admin/stocks/{productId}/corrections", productId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\": 2}")).hasStatus(204);
        stockReconciler.reconcileAll();

        assertThat(InventoryFixture.quantityOf(jdbcClient, productId)).isEqualTo(12);
        assertThat(statuses(productId)).containsExactly("CORRECTED");
    }

    private StockSummary balancedFor(long productId) {
        Integer left = jdbcClient.sql("""
                SELECT s.quantity + COALESCE((SELECT SUM(ss.quantity) FROM stock_slot ss WHERE ss.product_id = s.product_id), 0) - s.deficit
                       + COALESCE((SELECT SUM(r.quantity - r.cancelled_quantity) FROM stock_reservation r
                                    WHERE r.product_id = s.product_id AND r.status IN ('HELD', 'PAYING')), 0)
                  FROM stock s WHERE s.product_id = :productId
                """).param("productId", productId).query(Integer.class).optional().orElse(0);
        return new StockSummary(productId, left, 0);
    }

    private void reserve(long productId, int quantity) {
        reservations.reserve(new ReservationCommand(InventoryFixture.newOrderNo(), "SEOUL", List.of(new StockLine(productId, quantity))));
    }

    private void ageSuspected(long productId) {
        jdbcClient.sql("UPDATE stock_reconciliation SET first_seen_at = NOW(3) - INTERVAL 11 MINUTE WHERE product_id = :productId")
                .param("productId", productId).update();
    }

    private List<String> statuses(long productId) {
        return jdbcClient.sql("SELECT status FROM stock_reconciliation WHERE product_id = :productId ORDER BY id")
                .param("productId", productId).query(String.class).list();
    }
}
