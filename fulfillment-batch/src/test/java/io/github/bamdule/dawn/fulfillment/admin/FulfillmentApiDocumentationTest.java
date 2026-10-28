package io.github.bamdule.dawn.fulfillment.admin;

import static io.github.bamdule.dawn.messaging.test.ApiDocs.description;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;

import io.github.bamdule.dawn.event.CancelReason;
import io.github.bamdule.dawn.event.OrderEvent;
import io.github.bamdule.dawn.event.OrderEventType;
import io.github.bamdule.dawn.fulfillment.FulfillmentIntegrationTest;
import io.github.bamdule.dawn.fulfillment.allocation.AllocationRunner;
import io.github.bamdule.dawn.fulfillment.target.TargetFixer;
import io.github.bamdule.dawn.messaging.test.ApiDocs;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// 풀필먼트의 API 문서. 대부분 사람이 쓰는 관리 API 다 (docs/api/fulfillment-batch.md)
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@Import(ApiDocs.class)
class FulfillmentApiDocumentationTest extends FulfillmentIntegrationTest {

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private TargetFixer targetFixer;

    @Autowired
    private AllocationRunner allocationRunner;

    @Test
    void 출고_예외_이월과_관리자_취소() throws Exception {
        LocalDateTime firstCutoff = newCutoff();
        LocalDateTime secondCutoff = newCutoff();
        long deferred = exceptionNo(firstCutoff, exceptionOrder(firstCutoff));
        long cancelled = exceptionNo(secondCutoff, exceptionOrder(secondCutoff));

        assertThat(mockMvcTester.post().uri("/admin/shipment-exceptions/{exceptionNo}/defer", deferred))
                .hasStatus(204)
                .apply(document("01-출고 예외 이월",
                        description("예외 수량을 대기 수량으로 돌린다. 배정 회차는 그대로 둬서 다음 회차의 대상 확정이 가져간다. OPEN 이 아니면 409"),
                        pathParameters(parameterWithName("exceptionNo").description("출고 예외 번호"))));
        assertThat(mockMvcTester.post().uri("/admin/shipment-exceptions/{exceptionNo}/cancel", cancelled))
                .hasStatus(204)
                .apply(document("02-출고 예외 관리자 취소",
                        description("예외 수량을 취소 수량으로 옮기고 EXCEPTION_CANCELLED 를 낸다. 주문 서비스가 refund-{주문번호}-admin-{예외 번호} 로 환불한다. OPEN 이 아니면 409"),
                        pathParameters(parameterWithName("exceptionNo").description("출고 예외 번호"))));
    }

    @Test
    void 배차_전_출고_지시_취소() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 3);
        String orderNo = paid(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 2));
        run(cutoff);
        long shipmentOrderId = jdbcClient.sql("SELECT id FROM shipment_order WHERE cutoff_at = :cutoffAt AND order_no = :orderNo")
                .param("cutoffAt", cutoff).param("orderNo", orderNo).query(Long.class).single();

        assertThat(mockMvcTester.post().uri("/admin/shipment-orders/{shipmentOrderId}/cancel", shipmentOrderId))
                .hasStatus(204)
                .apply(document("03-배차 전 출고 지시 취소",
                        description("잘못 만든 출고 지시를 배차 전에 되돌린다. 실물 재고를 돌려놓고 출고 수량을 예외 수량으로 옮겨 출고 예외(OPEN)를 만든다. CREATED 가 아니면 409"),
                        pathParameters(parameterWithName("shipmentOrderId").description("출고 지시 번호"))));
    }

    @Test
    void 회차_시작과_승인() throws Exception {
        LocalDateTime cutoff = newCutoff();
        exceptionOrder(cutoff);

        assertThat(mockMvcTester.post().uri("/admin/cutoff-runs/{cutoffAt}/start", cutoff.plusDays(1).toString()))
                .hasStatusOk()
                .apply(document("04-회차 시작",
                        description("마감 시각으로 출고 배치를 시작한다. 실패한 회차에 다시 부르면 실패한 스텝부터 재시작하고, 완료된 회차는 다시 돌지 않는다. 평소에는 23:10 스케줄러가 부른다"),
                        pathParameters(parameterWithName("cutoffAt").description("마감 시각. 예) 2090-12-31T23:00")),
                        responseFields(fieldWithPath("status").description("잡 실행 결과. COMPLETED, FAILED 등 Spring Batch 상태"))));
        assertThat(mockMvcTester.post().uri("/admin/cutoff-runs/{cutoffAt}/approve", cutoff.toString()))
                .hasStatus(204)
                .apply(document("05-검증 실패 회차 승인",
                        description("결과 검증에 실패한 회차(VERIFY_FAILED)를 사람이 원인을 고친 뒤 승인한다. 승인 전에는 배차로 넘기지 않는다. VERIFY_FAILED 가 아니면 409"),
                        pathParameters(parameterWithName("cutoffAt").description("마감 시각"))));
    }

    @Test
    void 배차_인계() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 5);
        paid(cutoff, cutoff.minusHours(2), "SEOUL", line(product, 1));
        paid(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 2));
        run(cutoff);

        assertThat(mockMvcTester.post().uri("/admin/cutoff-runs/{cutoffAt}/hand-over", cutoff.toString()))
                .hasStatusOk()
                .apply(document("12-배차 인계",
                        description("배차가 VERIFIED 회차를 가져가며 부른다. 그 회차의 출고 지시를 HANDED_OVER 로 바꿔, 실사가 피킹 전 수량으로 빼지 않고 출고 지시 취소도 받지 않게 한다. 다시 부르면 0건이다. VERIFIED 가 아니면 409"),
                        pathParameters(parameterWithName("cutoffAt").description("마감 시각")),
                        responseFields(fieldWithPath("handedOver").description("이번에 넘긴 출고 지시 수"))));
    }

    @Test
    void 대상_확정_차이와_출고_대상_보정() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 1);
        String orderNo = paidWithoutEvent(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 1));
        orderEventConsumer.handle(new OrderEvent(UUID.randomUUID(), OrderEventType.CANCELLED, orderNo, 1L, 10_000L,
                List.of(line(product, 1)), CancelReason.CUSTOMER, null, null, Instant.now()));
        run(cutoff);

        assertThat(mockMvcTester.get().uri("/admin/cutoff-runs/{cutoffAt}/target-diffs", cutoff.toString()))
                .hasStatusOk()
                .apply(document("06-대상 확정 차이",
                        description("대상 확정이 세 번 어긋나 잡을 실패시킬 때 남긴 차이. 사람이 보고 출고 대상을 보정한 뒤 회차를 다시 시작한다"),
                        pathParameters(parameterWithName("cutoffAt").description("마감 시각")),
                        responseFields(
                                fieldWithPath("[].orderNo").description("주문 번호"),
                                fieldWithPath("[].kind").description("MISSING_IN_TARGET, CANCELLED_MARK_BUT_PAID, EXTRA_IN_TARGET"))));
        assertThat(mockMvcTester.post().uri("/admin/shipment-targets/{orderNo}/resync", orderNo))
                .hasStatus(204)
                .apply(document("07-출고 대상 보정",
                        description("주문 서비스의 주문 상세로 출고 대상을 다시 만든다. 없으면 넣고, 취소 표시인데 결제 완료이고 아직 처리된 수량이 없으면 대기로 되돌린다. 바꿀 것이 없으면 409"),
                        pathParameters(parameterWithName("orderNo").description("주문 번호"))));
        assertThat(mockMvcTester.put().uri("/admin/shipment-targets/{orderNo}/address", orderNo)
                .contentType(MediaType.APPLICATION_JSON).content("{\"recipientName\": \"김새벽\", \"address\": \"서울시 송파구 올림픽로 300\"}"))
                .hasStatus(204)
                .apply(document("08-배송지 수정",
                        description("주소 오류로 생긴 출고 예외를 이월하기 전에 출고 대상의 배송지를 고친다"),
                        pathParameters(parameterWithName("orderNo").description("주문 번호")),
                        requestFields(
                                fieldWithPath("recipientName").description("받는 사람"),
                                fieldWithPath("address").description("배송지"))));
    }

    @Test
    void 입고와_실사() {
        long product = newProductId();
        stock(SEOUL_A, product, 10);

        assertThat(mockMvcTester.post().uri("/admin/center-stocks/{centerId}/{productId}/inbound", SEOUL_A, product)
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": 20}"))
                .hasStatus(204)
                .apply(document("09-입고",
                        description("센터 실물 재고를 늘리고 조정 이력과 STOCK_CHANGED 를 한 트랜잭션에 쓴다. 재고 서비스가 판매 가능 재고를 늘린다"),
                        pathParameters(
                                parameterWithName("centerId").description("센터"),
                                parameterWithName("productId").description("상품 번호")),
                        requestFields(fieldWithPath("quantity").description("입고 수량. 1 이상"))));
        assertThat(mockMvcTester.post().uri("/admin/center-stocks/{centerId}/{productId}/stocktake", SEOUL_A, product)
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedQuantity\": 27}"))
                .hasStatusOk()
                .apply(document("10-실사",
                        description("센 수량을 덮어쓰지 않고, 전산 실물과 피킹 전 출고 지시 수량을 뺀 차이만 반영한다. 출고 지시 뒤 피킹 전에 세어도 판매 가능 재고가 늘지 않는다"),
                        pathParameters(
                                parameterWithName("centerId").description("센터"),
                                parameterWithName("productId").description("상품 번호")),
                        requestFields(fieldWithPath("countedQuantity").description("선반에서 센 수량")),
                        responseFields(fieldWithPath("appliedDelta").description("실물 재고에 실제로 더하거나 뺀 수량. 차이가 없으면 0"))));
    }

    @Test
    void 상품별_실물_요약() {
        long product = newProductId();
        stock(SEOUL_A, product, 7);
        stock(BUSAN_A, product, 3);
        paid(null, LocalDateTime.now(), "SEOUL", line(product, 2));

        assertThat(mockMvcTester.post().uri("/stocks/summary").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productIds\": [" + product + "]}"))
                .hasStatusOk()
                .apply(document("11-상품별 실물 요약",
                        description("재고 서비스의 재고 대사가 부른다. 상품마다 센터 실물 합과 출고 전 확정 수량(대기와 예외 수량)을 준다. 한 번에 1,000개까지"),
                        requestFields(fieldWithPath("productIds").description("상품 번호 목록")),
                        responseFields(
                                fieldWithPath("[].productId").description("상품 번호"),
                                fieldWithPath("[].physicalQuantity").description("센터 실물 재고의 합"),
                                fieldWithPath("[].pendingQuantity").description("출고 대상 품목의 대기 수량과 예외 수량의 합"))));
    }

    // 할당 뒤 재고를 없애 출고 지시 때 예외를 만든다. 예외가 할당 입력의 0.1% 를 넘어 회차는 VERIFY_FAILED 가 된다
    private String exceptionOrder(LocalDateTime cutoff) throws Exception {
        long product = newProductId();
        stock(SEOUL_A, product, 1);
        String orderNo = paid(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 1));
        targetFixer.fix(cutoff);
        allocationRunner.allocate(cutoff);
        jdbcClient.sql("UPDATE center_stock SET quantity = 0 WHERE center_id = :centerId AND product_id IN "
                        + "(SELECT product_id FROM allocation_input WHERE cutoff_at = :cutoffAt)")
                .param("centerId", SEOUL_A).param("cutoffAt", cutoff).update();
        run(cutoff);
        return orderNo;
    }

    private long exceptionNo(LocalDateTime cutoff, String orderNo) {
        return jdbcClient.sql("SELECT id FROM shipment_exception WHERE cutoff_at = :cutoffAt AND order_no = :orderNo")
                .param("cutoffAt", cutoff).param("orderNo", orderNo).query(Long.class).single();
    }
}
