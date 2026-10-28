package io.github.bamdule.dawn.payment.round;

import static io.github.bamdule.dawn.messaging.test.ApiDocs.description;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;

import io.github.bamdule.dawn.messaging.test.ApiDocs;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import io.github.bamdule.dawn.payment.PaymentStatus;
import io.github.bamdule.dawn.payment.pg.PgApproveResult;
import io.github.bamdule.dawn.payment.pg.PgClient;
import io.github.bamdule.dawn.payment.refund.RefundRepository;
import io.github.bamdule.dawn.payment.round.PaymentApproval.ApproveOutcome;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
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

// 결제 서비스의 API 문서. 대행사는 가짜로 둔다. 문서에 없는 응답 필드가 생기면 이 테스트가 깨진다 (docs/api/payment-service.md)
@SpringBootTest(properties = {
        "dawn.outbox.relay.enabled=false",
        "dawn.payment.inquiry-interval=1h",
        "dawn.payment.refund-interval=1h",
        "dawn.payment.settlement-cron=-"})
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@Import({MySqlContainers.class, KafkaContainers.class, ApiDocs.class})
class PaymentApiDocumentationTest {

    private static final AtomicLong NEXT_ORDER = new AtomicLong();
    private static final String PAYMENT_STATUSES = Arrays.toString(PaymentStatus.values());

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private PaymentRounds paymentRounds;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private PgClient pgClient;

    @Test
    void 승인() {
        when(pgClient.approve(any(), anyLong(), any())).thenReturn(PgApproveResult.declined("INSUFFICIENT_BALANCE"));

        assertThat(mockMvcTester.post().uri("/payments").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderNo": "%s", "paymentRound": 1, "amount": 45000, "method": "CARD"}
                """.formatted(newOrderNo())))
                .hasStatusOk()
                .apply(document("01-승인",
                        description("주문 서비스가 결제 회차 하나를 승인 요청한다. 회차를 연 뒤 대행사를 한 번만 3초까지 부른다. "
                                + "타임아웃과 5xx 는 실패가 아니라 UNKNOWN 이고, 조회 배치가 같은 멱등키로 확정한다. 예시는 카드가 거절된 경우다"),
                        requestFields(
                                fieldWithPath("orderNo").description("주문 번호"),
                                fieldWithPath("paymentRound").description("결제 회차. 주문 서비스가 정한다. 가장 최근 회차가 FAILED 나 EXPIRED 일 때만 새 회차가 열린다"),
                                fieldWithPath("amount").description("결제 금액"),
                                fieldWithPath("method").description("결제 수단")),
                        responseFields(
                                fieldWithPath("outcome").description("결과. " + Arrays.toString(ApproveOutcome.values())),
                                fieldWithPath("paymentRound").description("결제 회차"),
                                fieldWithPath("failureCode").description("실패 사유. 대행사의 거절 코드, 부르지 않았으면 NOT_CALLED. 거절일 때만 있다").optional())));
    }

    @Test
    void 회차_봉인() {
        String orderNo = newOrderNo();

        assertThat(mockMvcTester.post().uri("/payments/{orderNo}/rounds/{paymentRound}/seal", orderNo, 2))
                .hasStatusOk()
                .apply(document("02-회차 봉인",
                        description("아직 열리지 않은 회차에 EXPIRED 행을 넣어 늦게 온 승인 요청이 열지 못하게 한다. 이미 열렸으면 봉인하지 않고 그 회차의 상태를 돌려준다. "
                                + "재고 만료 배치와 주문 정리 배치만 부른다"),
                        pathParameters(
                                parameterWithName("orderNo").description("주문 번호"),
                                parameterWithName("paymentRound").description("봉인할 결제 회차")),
                        responseFields(fieldWithPath("outcome").description("SEALED 면 봉인했다. 아니면 이미 있던 회차의 상태다. "
                                + Arrays.toString(SealOutcome.values())))));
    }

    @Test
    void 회차_조회() {
        String orderNo = newOrderNo();
        paymentRounds.recordFailed(paymentRounds.open(orderNo, 1, 45_000).paymentRow(), "INSUFFICIENT_BALANCE");
        paymentRounds.recordApproved(paymentRounds.open(orderNo, 2, 45_000).paymentRow());

        assertThat(mockMvcTester.get().uri("/payments/{orderNo}", orderNo))
                .hasStatusOk()
                .apply(document("03-회차 조회",
                        description("한 주문의 결제 회차 목록. 다시 결제할 때 주문 서비스가 앞 회차가 끝났는지 확인한다"),
                        pathParameters(parameterWithName("orderNo").description("주문 번호")),
                        responseFields(
                                fieldWithPath("rounds[].paymentRound").description("결제 회차"),
                                fieldWithPath("rounds[].status").description("회차 상태. " + PAYMENT_STATUSES),
                                fieldWithPath("rounds[].amount").description("금액. 봉인된 회차는 0"))));
    }

    @Test
    void 미결_회차_확정() {
        String orderNo = newOrderNo();
        PaymentRow paymentRow = paymentRounds.open(orderNo, 1, 45_000).paymentRow();
        paymentRounds.markUnknown(paymentRow);

        assertThat(mockMvcTester.post().uri("/admin/payments/{orderNo}/rounds/{paymentRound}/resolve", orderNo, 1)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"APPROVED\"}"))
                .hasStatus(204)
                .apply(document("04-미결 회차 확정 (관리)",
                        description("10분 넘게 결과를 모른 UNKNOWN 회차를 사람이 대행사에 확인한 뒤 확정한다. 결과 이벤트가 나가 주문에 반영된다. UNKNOWN 이 아니면 409"),
                        pathParameters(
                                parameterWithName("orderNo").description("주문 번호"),
                                parameterWithName("paymentRound").description("결제 회차")),
                        requestFields(fieldWithPath("status").description("확정할 상태. APPROVED 또는 FAILED"))));
    }

    @Test
    void 환불_접수() {
        String orderNo = newOrderNo();
        paymentRounds.recordApproved(paymentRounds.open(orderNo, 1, 45_000).paymentRow());

        assertThat(mockMvcTester.post().uri("/refunds").contentType(MediaType.APPLICATION_JSON).content("""
                {"refundKey": "refund-%s-cancel", "orderNo": "%s", "paymentRound": 1, "amount": 45000}
                """.formatted(orderNo, orderNo)))
                .hasStatusOk()
                .apply(document("05-환불 접수",
                        description("주문 서비스의 환불 배치가 같은 키로 접수될 때까지 보낸다. 같은 키가 있으면 상한 검사 전에 그 환불의 지금 상태를 돌려준다. "
                                + "회차의 환불 합이 승인 금액을 넘으면 거절한다"),
                        requestFields(
                                fieldWithPath("refundKey").description("주문 서비스가 사유에서 만든 환불 키. 다시 계산해도 같다"),
                                fieldWithPath("orderNo").description("주문 번호"),
                                fieldWithPath("paymentRound").description("환불할 결제 회차"),
                                fieldWithPath("amount").description("환불 금액")),
                        responseFields(
                                fieldWithPath("outcome").description("ACCEPTED 또는 REJECTED"),
                                fieldWithPath("status").description("접수됐으면 결제 쪽 환불의 지금 상태(REQUESTED, COMPLETED, FAILED, UNKNOWN), 거절이면 사유"))));
    }

    @Test
    void 환불_조회와_확정() {
        String orderNo = newOrderNo();
        PaymentRow paymentRow = paymentRounds.open(orderNo, 1, 45_000).paymentRow();
        paymentRounds.recordApproved(paymentRow);
        String refundKey = "refund-" + orderNo + "-cancel";
        refundRepository.insertRequested(paymentRow.id(), refundKey, 45_000);
        jdbcClient.sql("UPDATE refund SET status = 'FAILED' WHERE refund_key = :refundKey").param("refundKey", refundKey).update();

        assertThat(mockMvcTester.get().uri("/refunds/{refundKey}", refundKey))
                .hasStatusOk()
                .apply(document("06-환불 조회",
                        description("1시간 넘게 결과가 오지 않은 환불을 주문 서비스가 물을 때 쓴다. 없으면 404"),
                        pathParameters(parameterWithName("refundKey").description("환불 키")),
                        responseFields(
                                fieldWithPath("refundKey").description("환불 키"),
                                fieldWithPath("status").description("REQUESTED, COMPLETED, FAILED, UNKNOWN"),
                                fieldWithPath("amount").description("환불 금액"))));
        assertThat(mockMvcTester.post().uri("/admin/refunds/{refundKey}/resolve", refundKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"COMPLETED\"}"))
                .hasStatus(204)
                .apply(document("07-환불 확정 (관리)",
                        description("대행사가 거절했거나 결과를 모르는 환불을 사람이 확인해 확정한다. 대행사에서 직접 처리했으면 COMPLETED 다. 결과 이벤트가 나가 주문의 누적액에 반영된다"),
                        pathParameters(parameterWithName("refundKey").description("환불 키")),
                        requestFields(fieldWithPath("status").description("COMPLETED 또는 FAILED. FAILED 는 UNKNOWN 에서만"))));
    }

    private static String newOrderNo() {
        return "20261028-PD" + NEXT_ORDER.incrementAndGet() + System.nanoTime() % 10_000;
    }
}
