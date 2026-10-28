package io.github.bamdule.dawn.inventory.reservation;

import static io.github.bamdule.dawn.messaging.test.ApiDocs.description;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;

import io.github.bamdule.dawn.inventory.reconciliation.FulfillmentStockClient;
import io.github.bamdule.dawn.inventory.reconciliation.FulfillmentStockClient.StockSummary;
import io.github.bamdule.dawn.inventory.reconciliation.StockReconciler;
import io.github.bamdule.dawn.inventory.stock.StockLine;
import io.github.bamdule.dawn.messaging.test.ApiDocs;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// 재고 서비스의 API 문서. 문서에 없는 응답 필드가 생기면 이 테스트가 깨진다 (docs/api/inventory-service.md)
@SpringBootTest(properties = {"dawn.reservation.expiry-interval=1h", "dawn.reconciliation.enabled=false"})
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@Import({MySqlContainers.class, KafkaContainers.class, ApiDocs.class})
class InventoryApiDocumentationTest {

    private static final String RESERVATION_STATUSES = Arrays.toString(ReservationStatus.values());

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private Reservations reservations;

    @Autowired
    private StockReconciler stockReconciler;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private PaymentRoundSealer paymentRoundSealer;

    @MockitoBean
    private FulfillmentStockClient fulfillmentStockClient;

    @Test
    void 품절_목록() {
        InventoryFixture.newProduct(jdbcClient, 0, 3);

        assertThat(mockMvcTester.get().uri("/stocks/sold-out"))
                .hasStatusOk()
                .apply(document("01-품절 목록",
                        description("판매 가능 재고가 0 인 상품. 주문 서비스가 500ms 마다 받아 주문을 저장하기 전에 특가 품절 시도를 거른다. 요청 때마다 계산한다"),
                        responseFields(fieldWithPath("productIds").description("품절 상품 번호 목록"))));
    }

    @Test
    void 예약() {
        long first = InventoryFixture.newProduct(jdbcClient, 100, 10);
        long hot = InventoryFixture.newProduct(jdbcClient, 300, 3);

        assertThat(mockMvcTester.post().uri("/reservations").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderNo": "%s", "regionCode": "SEOUL", "lines": [{"productId": %d, "quantity": 2}, {"productId": %d, "quantity": 1}]}
                """.formatted(InventoryFixture.newOrderNo(), first, hot)))
                .hasStatusOk()
                .apply(document("02-예약",
                        description("품목마다 판매 가능 재고를 조건부로 차감하고 HELD 예약을 만든다. 같은 주문 번호로 다시 오면 새로 잡지 않고 기존 만료 시각을 돌려준다. "
                                + "재고가 모자라거나 판매 제한 권역이면 409 와 함께 그 상품 번호를 돌려준다"),
                        requestFields(
                                fieldWithPath("orderNo").description("주문 번호"),
                                fieldWithPath("regionCode").description("배송 권역. 판매 제한 권역이면 품절로 답한다"),
                                fieldWithPath("lines[].productId").description("상품 번호"),
                                fieldWithPath("lines[].quantity").description("수량")),
                        responseFields(fieldWithPath("expiresAt").description("예약 만료 시각. 품목 중 가장 짧은 유지 시간으로 하나다. 예시는 특가 상품이 섞여 3분이다"))));
    }

    @Test
    void 결제_시작_표시() {
        String orderNo = reserve(InventoryFixture.newProduct(jdbcClient, 100, 10));

        assertThat(mockMvcTester.post().uri("/reservations/{orderNo}/paying", orderNo)
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentRound\": 1}"))
                .hasStatusOk()
                .apply(document("03-결제 시작 표시",
                        description("만료 전의 HELD 를 PAYING 으로 바꾸고 결제 회차를 적는다. 0행이면 marked 가 거짓이고 지금 상태로 주문 서비스가 갈래를 나눈다. 예약이 없으면 404"),
                        pathParameters(parameterWithName("orderNo").description("주문 번호")),
                        requestFields(fieldWithPath("paymentRound").description("이번 결제 회차")),
                        responseFields(
                                fieldWithPath("marked").description("이번 요청으로 PAYING 이 됐는지"),
                                fieldWithPath("currentStatus").description("지금 예약 상태. " + RESERVATION_STATUSES),
                                fieldWithPath("currentRound").description("예약에 적힌 결제 회차"),
                                fieldWithPath("expiresAt").description("만료 시각"),
                                fieldWithPath("expired").description("재고 DB 시계로 만료가 지났는지"))));
    }

    @Test
    void 거절_되돌림() {
        String orderNo = reserve(InventoryFixture.newProduct(jdbcClient, 100, 10));
        reservations.markPaying(orderNo, 1);

        assertThat(mockMvcTester.post().uri("/reservations/{orderNo}/paying/{paymentRound}/revert", orderNo, 1))
                .hasStatusOk()
                .apply(document("04-거절 되돌림",
                        description("카드가 거절된 회차의 PAYING 을 HELD 로 되돌린다. 그 회차일 때만 바뀌어, 늦게 온 앞 회차의 되돌림이 다음 회차를 건드리지 않는다"),
                        pathParameters(
                                parameterWithName("orderNo").description("주문 번호"),
                                parameterWithName("paymentRound").description("거절된 결제 회차")),
                        responseFields(fieldWithPath("reverted").description("이번 요청으로 HELD 가 됐는지"))));
    }

    @Test
    void 예약_상태() {
        String orderNo = reserve(InventoryFixture.newProduct(jdbcClient, 100, 10));
        reservations.markPaying(orderNo, 2);

        assertThat(mockMvcTester.get().uri("/reservations/{orderNo}", orderNo))
                .hasStatusOk()
                .apply(document("05-예약 상태",
                        description("주문 서비스의 정리 배치가 만료된 PENDING 주문을 닫을지 정할 때 쓴다. 예약이 없으면 404"),
                        pathParameters(parameterWithName("orderNo").description("주문 번호")),
                        responseFields(
                                fieldWithPath("status").description("예약 상태. " + RESERVATION_STATUSES),
                                fieldWithPath("paymentRound").description("결제 회차. 결제를 시작하기 전에는 없다").optional(),
                                fieldWithPath("expiresAt").description("만료 시각"),
                                fieldWithPath("expired").description("재고 DB 시계로 만료가 지났는지"))));
    }

    @Test
    void 판매_제한_권역() {
        long productId = InventoryFixture.newProduct(jdbcClient, 100, 10);

        assertThat(mockMvcTester.put().uri("/admin/stocks/{productId}/blocked-regions/{regionCode}", productId, "SEOUL"))
                .hasStatus(204)
                .apply(document("06-판매 제한 권역 설정 (관리)",
                        description("그 권역의 주문을 품절로 답하게 한다. 같은 상품과 권역의 권역 불일치가 사흘 연속이면 운영자가 쓴다. 해제는 같은 주소에 DELETE"),
                        pathParameters(
                                parameterWithName("productId").description("상품 번호"),
                                parameterWithName("regionCode").description("배송 권역"))));
    }

    @Test
    void 슬롯_펴기() {
        long productId = InventoryFixture.newProduct(jdbcClient, 300, 3);

        assertThat(mockMvcTester.put().uri("/admin/stocks/{productId}/slot-mode/spread", productId))
                .hasStatus(204)
                .apply(document("07-슬롯으로 펴기 (관리)",
                        description("특가 판매 전에 재고를 슬롯 8개로 나눈다. 합쳐진(MERGED) 상품도 다시 편다"),
                        pathParameters(parameterWithName("productId").description("상품 번호"))));
    }

    @Test
    void 재고_대사와_보정() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        when(fulfillmentStockClient.summary(anyList())).thenAnswer(invocation -> invocation.<List<Long>>getArgument(0).stream()
                .map(id -> id == productId ? new StockSummary(id, 12, 0) : new StockSummary(id, balancedLeft(id), 0))
                .toList());
        stockReconciler.reconcileAll();
        jdbcClient.sql("UPDATE stock_reconciliation SET first_seen_at = NOW(3) - INTERVAL 11 MINUTE WHERE product_id = :productId")
                .param("productId", productId).update();
        stockReconciler.recheckSuspected();

        assertThat(mockMvcTester.get().uri("/admin/stock-reconciliations"))
                .hasStatusOk()
                .apply(document("08-재고 대사 불일치 (관리)",
                        description("10분 간격 두 번 연속 대사 식이 어긋난 상품. 사람이 실물을 확인한 뒤 보정한다"),
                        responseFields(
                                fieldWithPath("[].productId").description("상품 번호"),
                                fieldWithPath("[].leftSide").description("판매 가능 재고 - 부족분 + 잡힌 예약 + 출고 전 확정 수량"),
                                fieldWithPath("[].rightSide").description("센터 실물 합"),
                                fieldWithPath("[].firstSeenAt").description("처음 어긋난 시각"))));
        assertThat(mockMvcTester.post().uri("/admin/stocks/{productId}/corrections", productId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\": 2}"))
                .hasStatus(204)
                .apply(document("09-판매 가능 재고 보정 (관리)",
                        description("판매 가능 재고를 더하거나 뺀다. 늘리는 쪽은 부족분부터 갚고, 줄이는 쪽은 0 아래로 갈 몫을 부족분에 둔다. 열린 대사 기록을 닫는다"),
                        pathParameters(parameterWithName("productId").description("상품 번호")),
                        requestFields(fieldWithPath("delta").description("더하거나 뺄 수량. 대사의 오른쪽 - 왼쪽을 넣으면 식이 맞는다"))));
    }

    private int balancedLeft(long productId) {
        return jdbcClient.sql("""
                SELECT s.quantity + COALESCE((SELECT SUM(ss.quantity) FROM stock_slot ss WHERE ss.product_id = s.product_id), 0) - s.deficit
                       + COALESCE((SELECT SUM(r.quantity - r.cancelled_quantity) FROM stock_reservation r
                                    WHERE r.product_id = s.product_id AND r.status IN ('HELD', 'PAYING')), 0)
                  FROM stock s WHERE s.product_id = :productId
                """).param("productId", productId).query(Integer.class).single();
    }

    private String reserve(long productId) {
        String orderNo = InventoryFixture.newOrderNo();
        reservations.reserve(new ReservationCommand(orderNo, "SEOUL", List.of(new StockLine(productId, 1))));
        return orderNo;
    }
}
