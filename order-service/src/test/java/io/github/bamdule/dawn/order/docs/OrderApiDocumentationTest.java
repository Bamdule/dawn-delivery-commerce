package io.github.bamdule.dawn.order.docs;

import static io.github.bamdule.dawn.messaging.test.ApiDocs.description;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;

import io.github.bamdule.dawn.event.PaymentEvent;
import io.github.bamdule.dawn.event.PaymentEventType;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.messaging.test.ApiDocs;
import io.github.bamdule.dawn.order.OrderIntegrationTest;
import io.github.bamdule.dawn.order.OrderStatus;
import io.github.bamdule.dawn.order.cancel.CancelOutcome;
import io.github.bamdule.dawn.order.client.ApproveOutcome;
import io.github.bamdule.dawn.order.client.PaymentClient.ApproveResult;
import io.github.bamdule.dawn.order.client.PaymentClient.RefundAcceptResult;
import io.github.bamdule.dawn.order.placement.OrderOutcome;
import io.github.bamdule.dawn.order.refund.RefundSender;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

// 주문 서비스의 API 문서. 문서에 없는 응답 필드가 생기면 이 테스트가 깨진다 (docs/api/order-service.md)
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@Import(ApiDocs.class)
class OrderApiDocumentationTest extends OrderIntegrationTest {

    private static final String ORDER_OUTCOMES = Arrays.toString(OrderOutcome.values());
    private static final String ORDER_STATUSES = Arrays.toString(OrderStatus.values());

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private RefundSender refundSender;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void 주문_생성() {
        when(paymentClient.approve(any(), anyInt(), anyLong(), any()))
                .thenReturn(new ApproveResult(ApproveOutcome.DECLINED, 1, "INSUFFICIENT_BALANCE"));
        String body = """
                {"checkoutId": "%s", "memberId": 1001, "recipientName": "김새벽", "address": "서울시 강남구 테헤란로 1",
                 "regionCode": "SEOUL", "paymentMethod": "CARD",
                 "items": [{"productId": %d, "quantity": 2, "unitPrice": 12000}, {"productId": %d, "quantity": 1, "unitPrice": 21000}]}
                """.formatted(UUID.randomUUID(), newProductId(), newProductId());

        assertThat(mockMvcTester.post().uri("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatusOk()
                .apply(document("01-주문 생성",
                        description("주문을 저장하고 재고를 예약한 뒤 결제를 부른다. 같은 주문서 ID 로 다시 보내면 새 주문을 만들지 않고 기존 주문의 지금 상태를 돌려준다. "
                                + "예시는 카드가 거절된 경우다. 주문은 PENDING 으로 남고 다른 카드로 다시 결제할 수 있다"),
                        requestFields(
                                fieldWithPath("checkoutId").description("주문서 화면을 열 때 받은 ID. 응답을 못 받고 다시 보내는 요청을 한 주문으로 묶는다"),
                                fieldWithPath("memberId").description("회원 번호"),
                                fieldWithPath("recipientName").description("받는 사람"),
                                fieldWithPath("address").description("배송지"),
                                fieldWithPath("regionCode").description("배송 권역. 출고 센터를 고르고 판매 제한 권역을 거르는 데 쓴다"),
                                fieldWithPath("paymentMethod").description("결제 수단"),
                                fieldWithPath("items[].productId").description("상품 번호"),
                                fieldWithPath("items[].quantity").description("수량. 1 이상"),
                                fieldWithPath("items[].unitPrice").description("할인을 품목에 나눠 반영한 실제 결제 단가. 환불 금액을 이 값으로 센다")),
                        responseFields(orderResultFields())));
    }

    @Test
    void 다시_결제() {
        when(paymentClient.approve(any(), anyInt(), anyLong(), any()))
                .thenReturn(new ApproveResult(ApproveOutcome.DECLINED, 1, "INSUFFICIENT_BALANCE"))
                .thenReturn(new ApproveResult(ApproveOutcome.DECLINED, 2, "CARD_EXPIRED"));
        String orderNo = placeOne(25_000).orderNo();

        assertThat(mockMvcTester.post().uri("/orders/{orderNo}/payments", orderNo)
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentMethod\": \"OTHER_CARD\"}"))
                .hasStatusOk()
                .apply(document("02-다시 결제",
                        description("카드가 거절된 뒤 다른 수단으로 다음 회차를 결제한다. 앞 결제의 결과를 아직 모르면 새 시도를 열지 않고 PAYMENT_IN_PROGRESS 로 답한다"),
                        pathParameters(parameterWithName("orderNo").description("주문 번호")),
                        requestFields(fieldWithPath("paymentMethod").description("결제 수단")),
                        responseFields(orderResultFields())));
    }

    @Test
    void 고객_취소() {
        String orderNo = placeOne(25_000).orderNo();

        assertThat(mockMvcTester.post().uri("/orders/{orderNo}/cancel", orderNo))
                .hasStatusOk()
                .apply(document("03-고객 취소",
                        description("속한 마감 회차의 마감 전까지만 받는다. 남은 품목을 모두 취소하고 환불 요청과 CANCELLED 이벤트를 한 트랜잭션에 쓴다"),
                        pathParameters(parameterWithName("orderNo").description("주문 번호")),
                        responseFields(
                                fieldWithPath("orderNo").description("주문 번호"),
                                fieldWithPath("outcome").description("결과. " + Arrays.toString(CancelOutcome.values())))));
    }

    @Test
    void 주문_상세() {
        String orderNo = placeOne(25_000).orderNo();

        assertThat(mockMvcTester.get().uri("/orders/{orderNo}", orderNo))
                .hasStatusOk()
                .apply(document("04-주문 상세",
                        description("사용자의 주문 조회와, 출고 배치가 이벤트로 받지 못한 주문을 채울 때 쓴다. 없으면 404"),
                        pathParameters(parameterWithName("orderNo").description("주문 번호")),
                        responseFields(
                                fieldWithPath("orderNo").description("주문 번호"),
                                fieldWithPath("status").description("주문 상태. " + ORDER_STATUSES),
                                fieldWithPath("memberId").description("회원 번호"),
                                fieldWithPath("totalAmount").description("결제 금액"),
                                fieldWithPath("refundedAmount").description("완료된 환불의 누적액"),
                                fieldWithPath("paidRound").description("주문을 PAID 로 만든 결제 회차. 결제 전에는 없다").optional(),
                                fieldWithPath("paidAt").description("PAID 커밋 시각(주문 DB 시계). 마감 회차를 정한다. 결제 전에는 없다").optional(),
                                fieldWithPath("recipientName").description("받는 사람"),
                                fieldWithPath("address").description("배송지"),
                                fieldWithPath("regionCode").description("배송 권역"),
                                fieldWithPath("items[].productId").description("상품 번호"),
                                fieldWithPath("items[].quantity").description("주문 수량"),
                                fieldWithPath("items[].cancelledQuantity").description("취소된 수량. 고객 취소, 출고 불가, 관리자 취소를 모두 더한다"),
                                fieldWithPath("items[].unitPrice").description("실제 결제 단가"))));
    }

    @Test
    void 마감_회차의_결제_완료_목록() {
        String cutoffAt = "2031-03-02T23:00:00";
        paidAt(placeOne(25_000).orderNo(), "2031-03-02 21:10:00");
        paidAt(placeOne(25_000).orderNo(), "2031-03-02 22:40:00");

        assertThat(mockMvcTester.get().uri("/orders/paid?cutoffAt={cutoffAt}&size=1", cutoffAt))
                .hasStatusOk()
                .apply(document("05-마감 회차의 결제 완료 목록",
                        description("출고 배치의 대상 확정이 원천과 대조할 때 쓴다. 전 회차 마감 뒤부터 이번 마감까지 PAID 가 된 주문을 (paid_at, id) 순으로 나눠 준다"),
                        queryParameters(
                                parameterWithName("cutoffAt").description("마감 시각. 예) 2031-03-02T23:00:00"),
                                parameterWithName("size").description("한 페이지의 주문 수. 기본 1,000, 최대 5,000").optional(),
                                parameterWithName("cursorPaidAt").description("앞 페이지 응답의 nextCursorPaidAt. 첫 페이지면 비운다").optional(),
                                parameterWithName("cursorId").description("앞 페이지 응답의 nextCursorId. 첫 페이지면 비운다").optional()),
                        responseFields(
                                fieldWithPath("orderNos").description("주문 번호 목록"),
                                fieldWithPath("nextCursorPaidAt").description("다음 페이지 커서. 마지막 페이지면 없다").optional(),
                                fieldWithPath("nextCursorId").description("다음 페이지 커서. 마지막 페이지면 없다").optional())));
    }

    @Test
    void 환불_다시_요청() {
        String orderNo = placeOne(25_000).orderNo();
        jdbcClient.sql("UPDATE orders SET status = 'PAID' WHERE order_no = :orderNo").param("orderNo", orderNo).update();
        mockMvcTester.post().uri("/orders/{orderNo}/cancel", orderNo).exchange();
        when(paymentClient.acceptRefund(any(), any(), anyInt(), anyLong())).thenReturn(new RefundAcceptResult(false, "EXCEEDS_APPROVED_AMOUNT"));
        refundSender.run();

        assertThat(mockMvcTester.post().uri("/admin/refunds/{refundKey}/reissue", "refund-" + orderNo + "-cancel")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 24000}"))
                .hasStatusOk()
                .apply(document("06-환불 다시 요청 (관리)",
                        description("실패했거나 결제 서비스가 접수를 거절한 환불을 사람이 금액을 확인해 새 키로 다시 요청한다. 진행 중인 환불이면 409"),
                        pathParameters(parameterWithName("refundKey").description("원래 환불 키")),
                        requestFields(fieldWithPath("amount").type(JsonFieldType.NUMBER)
                                .description("바로잡은 금액. 비우면 원래 금액").optional()),
                        responseFields(fieldWithPath("refundKey").description("새 환불 키. refund-{주문번호}-reissue-{원래 요청 번호}"))));
    }

    @Test
    void 아웃박스_관리() {
        long stuck = insertStuckEvent();
        long quarantined = insertStuckEvent();
        mockMvcTester.post().uri("/admin/outbox/{seq}/quarantine", quarantined).exchange();

        assertThat(mockMvcTester.get().uri("/admin/outbox/stuck"))
                .hasStatusOk()
                .apply(document("07-여러 번 실패한 이벤트 (관리)",
                        description("10번 넘게 보내지 못한 PENDING 이벤트. 이벤트를 내는 서비스(주문, 결제, 풀필먼트)마다 같은 주소로 있다. 격리된 이벤트는 /admin/outbox/quarantined 로 본다"),
                        responseFields(outboxEventFields())));
        assertThat(mockMvcTester.post().uri("/admin/outbox/{seq}/retry", stuck))
                .hasStatus(204)
                .apply(document("08-이벤트 바로 다시 보내기 (관리)",
                        description("원인을 고친 뒤 백오프를 기다리지 않고 다음 바퀴에 보낸다. PENDING 이 아니면 409"),
                        pathParameters(parameterWithName("seq").description("아웃박스 순번"))));
        assertThat(mockMvcTester.post().uri("/admin/outbox/{seq}/quarantine", stuck))
                .hasStatus(204)
                .apply(document("09-이벤트 격리 (관리)",
                        description("영구히 보낼 수 없는 이벤트를 QUARANTINED 로 둔다. 같은 키의 뒤 이벤트가 풀리므로 순서가 바뀌어도 되는지 판단한 뒤에만 쓴다. PENDING 이 아니면 409"),
                        pathParameters(parameterWithName("seq").description("아웃박스 순번"))));
    }

    @Test
    void 처리_실패_토픽_관리() throws Exception {
        PaymentEvent refunded = new PaymentEvent(UUID.randomUUID(), PaymentEventType.REFUNDED, "20261028-A1B2C3D4E", 1, 45_000,
                "refund-20261028-A1B2C3D4E-cancel", false, Instant.now());
        kafkaTemplate.send(Topics.PAYMENT_EVENTS + "-dlt", refunded.orderNo(), jsonMapper.writeValueAsString(refunded)).get();

        assertThat(mockMvcTester.get().uri("/admin/dead-letters"))
                .hasStatusOk()
                .apply(document("10-처리 실패 토픽에 쌓인 메시지 (관리)",
                        description("이 서비스가 받는 토픽마다 아직 다시 넣지 않은 메시지 수. 받는 서비스(주문, 재고, 풀필먼트)마다 같은 주소로 있다"),
                        responseFields(
                                fieldWithPath("[].topic").description("원래 토픽. 처리 실패 토픽은 이 이름 뒤에 -dlt 를 붙인다"),
                                fieldWithPath("[].backlog").description("다시 넣지 않은 메시지 수. 1건이라도 있으면 경보"))));
        assertThat(mockMvcTester.post().uri("/admin/dead-letters/{topic}/replay?maxRecords=500", Topics.PAYMENT_EVENTS))
                .hasStatusOk()
                .apply(document("11-처리 실패 토픽 다시 넣기 (관리)",
                        description("원인을 고친 뒤 같은 키로 원래 토픽에 다시 넣는다. 그 토픽을 읽는 모든 그룹이 다시 받는다. 이 서비스가 받지 않는 토픽이면 404"),
                        pathParameters(parameterWithName("topic").description("원래 토픽")),
                        queryParameters(parameterWithName("maxRecords").description("이번에 다시 넣을 최대 건수. 기본 500")),
                        responseFields(fieldWithPath("replayed").description("다시 넣은 메시지 수"))));
    }

    private static org.springframework.restdocs.payload.FieldDescriptor[] orderResultFields() {
        return new org.springframework.restdocs.payload.FieldDescriptor[] {
                fieldWithPath("orderNo").description("주문 번호. 품절 목록에서 걸러 저장하지 않았으면 없다").optional(),
                fieldWithPath("status").description("주문 상태. " + ORDER_STATUSES).optional(),
                fieldWithPath("outcome").description("화면에 보여 줄 결과. " + ORDER_OUTCOMES),
                fieldWithPath("failureCode").description("실패 사유. 카드 거절, 결제 지연, 품절일 때만 있다").optional()};
    }

    private static org.springframework.restdocs.payload.FieldDescriptor[] outboxEventFields() {
        return new org.springframework.restdocs.payload.FieldDescriptor[] {
                fieldWithPath("[].seq").description("아웃박스 순번. 같은 키 안의 발행 순서"),
                fieldWithPath("[].eventId").description("이벤트 아이디. 사실에서 만든 이름 기반 UUID"),
                fieldWithPath("[].topic").description("토픽"),
                fieldWithPath("[].partitionKey").description("파티션 키. 보통 주문 번호"),
                fieldWithPath("[].attemptCount").description("실패한 횟수"),
                fieldWithPath("[].createdAt").description("기록 시각"),
                fieldWithPath("[].nextAttemptAt").description("다음 시도 시각")};
    }

    private void paidAt(String orderNo, String paidAt) {
        jdbcClient.sql("UPDATE orders SET paid_at = :paidAt WHERE order_no = :orderNo").param("paidAt", paidAt).param("orderNo", orderNo).update();
    }

    private long insertStuckEvent() {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                INSERT INTO outbox_event (event_id, topic, partition_key, payload, status, attempt_count, next_attempt_at, created_at)
                VALUES (:eventId, 'order.events', '20261028-K3F9A2Q7XZ', '{}', 'PENDING', 11, NOW(3) + INTERVAL 1 MINUTE, NOW(3) - INTERVAL 15 MINUTE)
                """)
                .param("eventId", UUID.randomUUID().toString())
                .update(keyHolder, "seq");
        return keyHolder.getKey().longValue();
    }
}
