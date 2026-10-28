package io.github.bamdule.dawn.payment.round;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.fakepg.FakePgApplication;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import io.github.bamdule.dawn.payment.refund.RefundJob;
import io.github.bamdule.dawn.payment.refund.Refunds;
import io.github.bamdule.dawn.payment.refund.Refunds.AcceptOutcome;
import io.github.bamdule.dawn.payment.refund.Refunds.AcceptResult;
import io.github.bamdule.dawn.payment.refund.Refunds.RefundCommand;
import io.github.bamdule.dawn.payment.round.PaymentApproval.ApproveCommand;
import io.github.bamdule.dawn.payment.round.PaymentApproval.ApproveOutcome;
import io.github.bamdule.dawn.payment.round.PaymentApproval.ApproveResult;
import io.github.bamdule.dawn.payment.settlement.SettlementReconciler;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.client.RestClient;

@SpringBootTest(properties = {
        "dawn.outbox.relay.enabled=false",
        "dawn.payment.inquiry-interval=1h",
        "dawn.payment.refund-interval=1h",
        "dawn.payment.settlement-cron=-"})
@AutoConfigureMockMvc
@Import({MySqlContainers.class, KafkaContainers.class})
class PaymentIntegrationTest {

    // 3초 타임아웃과 응답 유실은 HTTP 수준의 일이라, 가짜 대행사를 같은 JVM 안에서 실제 서버로 띄운다
    // 같은 JVM 이라 결제 서비스의 application.yml 과 DB, Kafka 자동 설정이 따라 들어온다. 설정 파일 이름을 바꿔 읽지 않게 하고 자동 설정을 뺀다
    private static final ConfigurableApplicationContext FAKE_PG = new SpringApplicationBuilder(FakePgApplication.class)
            .run("--server.port=0", "--spring.main.banner-mode=off", "--spring.config.name=fake-pg-embedded",
                    "--spring.autoconfigure.exclude="
                            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                            + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                            + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
                            + "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                            + "io.github.bamdule.dawn.messaging.MessagingAutoConfiguration");
    private static final String FAKE_PG_URL = "http://localhost:" + FAKE_PG.getEnvironment().getProperty("local.server.port");
    private static final AtomicLong NEXT_ORDER = new AtomicLong();

    private final RestClient fakePg = RestClient.create(FAKE_PG_URL);

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private PaymentApproval paymentApproval;

    @Autowired
    private PaymentRounds paymentRounds;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RequestedPaymentResolver requestedPaymentResolver;

    @Autowired
    private Refunds refunds;

    @Autowired
    private RefundJob refundJob;

    @Autowired
    private SettlementReconciler settlementReconciler;

    @Autowired
    private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("dawn.pg.base-url", () -> FAKE_PG_URL);
    }

    @BeforeEach
    void resetFakePg() {
        fakePg.post().uri("/fake/reset").retrieve().toBodilessEntity();
        // 앞선 시험이 남긴 미결 결제와 환불을 배치가 줍지 않게 미룬다. 대행사 기록을 지웠으니 주우면 다시 보낸다
        jdbcClient.sql("UPDATE payment SET next_attempt_at = NOW(3) + INTERVAL 1 DAY WHERE status = 'REQUESTED'").update();
        jdbcClient.sql("UPDATE refund SET last_sent_at = NOW(3) + INTERVAL 1 DAY WHERE status = 'REQUESTED'").update();
    }

    @Test
    void 승인되면_APPROVED_와_승인_이벤트를_함께_남긴다() {
        String orderNo = newOrderNo();

        ApproveResult result = approve(orderNo, 1);

        assertThat(result.outcome()).isEqualTo(ApproveOutcome.APPROVED);
        assertThat(statusOf(orderNo, 1)).isEqualTo("APPROVED");
        assertThat(eventTypes(orderNo)).containsExactly("APPROVED");
    }

    @Test
    void 카드가_거절되면_FAILED_와_실패_이벤트를_남긴다() {
        String orderNo = newOrderNo();
        nextApprove("DECLINE", 0);

        ApproveResult result = approve(orderNo, 1);

        assertThat(result.outcome()).isEqualTo(ApproveOutcome.DECLINED);
        assertThat(statusOf(orderNo, 1)).isEqualTo("FAILED");
        assertThat(eventTypes(orderNo)).containsExactly("FAILED");
    }

    @Test
    void 대행사가_5초_동안_응답하지_않아도_3초_남짓에_모름으로_답한다() {
        String orderNo = newOrderNo();
        nextApprove("APPROVE_SLOWLY", 5_000);

        long startedAt = System.currentTimeMillis();
        ApproveResult result = approve(orderNo, 1);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(result.outcome()).isEqualTo(ApproveOutcome.UNKNOWN);
        assertThat(elapsed).isBetween(2_900L, 4_500L);
        assertThat(statusOf(orderNo, 1)).as("타임아웃은 실패가 아니라 모름").isEqualTo("REQUESTED");
        assertThat(eventTypes(orderNo)).isEmpty();
    }

    @Test
    void 응답이_유실돼도_조회_배치가_멱등키로_물어_승인으로_확정하고_대행사_승인은_한_번이다() {
        String orderNo = newOrderNo();
        nextApprove("APPROVE_SLOWLY", 5_000);
        approve(orderNo, 1);
        makeInquiryDue(orderNo, 1);

        requestedPaymentResolver.run();

        assertThat(statusOf(orderNo, 1)).isEqualTo("APPROVED");
        assertThat(eventTypes(orderNo)).containsExactly("APPROVED");
        assertThat(approveCalls()).isEqualTo(1);
    }

    @Test
    void 결과를_모르는_동안에는_다음_회차를_열지_않고_실패로_끝나면_연다() {
        String orderNo = newOrderNo();
        nextApprove("APPROVE_SLOWLY", 5_000);
        approve(orderNo, 1);

        assertThat(approve(orderNo, 2).outcome()).isEqualTo(ApproveOutcome.IN_PROGRESS);

        String declinedOrder = newOrderNo();
        nextApprove("DECLINE", 0);
        approve(declinedOrder, 1);
        assertThat(approve(declinedOrder, 3).outcome()).as("번호는 건너뛰어도 된다").isEqualTo(ApproveOutcome.APPROVED);
        assertThat(approve(declinedOrder, 2).outcome()).as("기존보다 작은 번호는 오래된 요청").isEqualTo(ApproveOutcome.STALE);
        assertThat(approve(declinedOrder, 3).outcome()).as("같은 회차를 다시 보내면 그 결과").isEqualTo(ApproveOutcome.APPROVED);
        assertThat(approve(declinedOrder, 4).outcome()).isEqualTo(ApproveOutcome.ALREADY_PAID);
    }

    @Test
    void 봉인된_회차는_늦게_온_승인_요청이_열지_못하고_대행사도_부르지_않는다() {
        String orderNo = newOrderNo();

        assertThat(paymentRounds.seal(orderNo, 1)).isEqualTo(SealOutcome.SEALED);
        assertThat(approve(orderNo, 1).outcome()).isEqualTo(ApproveOutcome.SEALED);
        assertThat(approveCalls()).isZero();
        assertThat(approve(orderNo, 2).outcome()).as("봉인 다음 회차는 열린다").isEqualTo(ApproveOutcome.APPROVED);
        assertThat(paymentRounds.seal(orderNo, 2)).isEqualTo(SealOutcome.APPROVED);
    }

    @Test
    void 봉인과_회차_열기를_50번_겹쳐도_봉인됐는데_승인된_경우는_없다() {
        int sealedFirst = 0;
        int openedFirst = 0;
        for (int attempt = 0; attempt < 50; attempt++) {
            String orderNo = newOrderNo();
            CompletableFuture<SealOutcome> seal = CompletableFuture.supplyAsync(() -> paymentRounds.seal(orderNo, 1));
            CompletableFuture<ApproveResult> approve = CompletableFuture.supplyAsync(() -> approve(orderNo, 1));

            SealOutcome sealOutcome = seal.join();
            ApproveOutcome approveOutcome = approve.join().outcome();

            if (sealOutcome == SealOutcome.SEALED) {
                sealedFirst++;
                assertThat(approveOutcome).isEqualTo(ApproveOutcome.SEALED);
                assertThat(statusOf(orderNo, 1)).isEqualTo("EXPIRED");
            } else {
                openedFirst++;
                assertThat(approveOutcome).isEqualTo(ApproveOutcome.APPROVED);
                assertThat(sealOutcome).isIn(SealOutcome.REQUESTED, SealOutcome.APPROVED);
            }
        }
        System.out.printf("봉인이 먼저=%d, 회차 열기가 먼저=%d%n", sealedFirst, openedFirst);
        assertThat(sealedFirst).as("봉인이 먼저인 경우도 시험해야 한다").isPositive();
        assertThat(openedFirst).as("회차 열기가 먼저인 경우도 시험해야 한다").isPositive();
    }

    @Test
    void 재고_서비스가_부르는_봉인_API_와_회차_조회_API_의_계약() {
        String orderNo = newOrderNo();

        assertThat(mockMvcTester.post().uri("/payments/{orderNo}/rounds/1/seal", orderNo))
                .hasStatusOk().bodyJson().extractingPath("$.outcome").isEqualTo("SEALED");
        approve(orderNo, 2);
        assertThat(mockMvcTester.get().uri("/payments/{orderNo}", orderNo))
                .hasStatusOk().bodyJson().extractingPath("$.rounds[1].status").isEqualTo("APPROVED");
        assertThat(mockMvcTester.get().uri("/refunds/{key}", "refund-none"))
                .hasStatus(404);
    }

    @Test
    void 대행사에_요청이_없다는_답은_마지막_전송에서_60초가_지난_뒤에만_실패로_확정한다() {
        String orderNo = newOrderNo();
        nextApprove("NEVER_RECEIVED", 0);
        approve(orderNo, 1);
        makeInquiryDue(orderNo, 1);

        requestedPaymentResolver.run();
        assertThat(statusOf(orderNo, 1)).as("마지막 전송 직후라 아직 처리 중일 수 있다").isEqualTo("REQUESTED");

        jdbcClient.sql("UPDATE payment SET last_sent_at = NOW(3) - INTERVAL 61 SECOND, next_attempt_at = NULL WHERE order_no = :orderNo")
                .param("orderNo", orderNo).update();
        requestedPaymentResolver.run();

        assertThat(statusOf(orderNo, 1)).isEqualTo("FAILED");
        assertThat(eventTypes(orderNo)).containsExactly("FAILED");
    }

    @Test
    void 결과를_10분_넘게_모르면_UNKNOWN_으로_사람에게_넘기고_사람이_확정할_수_있다() {
        String orderNo = newOrderNo();
        nextApprove("NEVER_RECEIVED", 0);
        approve(orderNo, 1);
        jdbcClient.sql("UPDATE payment SET requested_at = NOW(3) - INTERVAL 11 MINUTE, next_attempt_at = NULL WHERE order_no = :orderNo")
                .param("orderNo", orderNo).update();

        requestedPaymentResolver.run();
        assertThat(statusOf(orderNo, 1)).isEqualTo("UNKNOWN");

        PaymentRow paymentRow = paymentRepository.findRound(orderNo, 1).orElseThrow();
        assertThat(paymentRounds.resolveUnknown(paymentRow, io.github.bamdule.dawn.payment.PaymentStatus.APPROVED)).isTrue();
        assertThat(statusOf(orderNo, 1)).isEqualTo("APPROVED");
        assertThat(eventTypes(orderNo)).containsExactly("APPROVED");
    }

    @Test
    void 한_건의_조회가_실패해도_같은_주기의_다른_건은_확정한다() {
        String firstOrder = newOrderNo();
        String secondOrder = newOrderNo();
        nextApprove("ERROR_AFTER_APPROVE", 0);
        approve(firstOrder, 1);
        nextApprove("ERROR_AFTER_APPROVE", 0);
        approve(secondOrder, 1);
        makeInquiryDue(firstOrder, 1);
        makeInquiryDue(secondOrder, 1);
        nextInquiry("ERROR");

        requestedPaymentResolver.run();

        assertThat(List.of(statusOf(firstOrder, 1), statusOf(secondOrder, 1)))
                .as("실패한 한 건은 다음 주기로 넘기고 나머지는 확정한다")
                .containsExactlyInAnyOrder("APPROVED", "REQUESTED");
    }

    @Test
    void 조회가_계속_실패해도_10분이_지나면_UNKNOWN_으로_넘겨_사람이_확정하게_한다() {
        String orderNo = newOrderNo();
        nextApprove("NEVER_RECEIVED", 0);
        approve(orderNo, 1);
        jdbcClient.sql("UPDATE payment SET requested_at = NOW(3) - INTERVAL 11 MINUTE, next_attempt_at = NULL WHERE order_no = :orderNo")
                .param("orderNo", orderNo).update();
        nextInquiry("EMPTY");

        requestedPaymentResolver.run();

        assertThat(statusOf(orderNo, 1)).isEqualTo("UNKNOWN");
    }

    @Test
    void 결과를_모르는_환불은_30초에_한_번만_다시_보낸다() {
        String orderNo = newOrderNo();
        approve(orderNo, 1);
        String refundKey = "refund-" + orderNo + "-cancel";
        refunds.accept(new RefundCommand(refundKey, orderNo, 1, 45_000));
        fakePg.post().uri("/fake/behaviors/refund?behavior=ERROR").retrieve().toBodilessEntity();

        refundJob.run();
        assertThat(refundStatus(refundKey)).isEqualTo("REQUESTED");
        refundJob.run();
        assertThat(refundStatus(refundKey)).as("방금 보낸 환불은 다시 보내지 않는다").isEqualTo("REQUESTED");

        jdbcClient.sql("UPDATE refund SET last_sent_at = NOW(3) - INTERVAL 31 SECOND WHERE refund_key = :refundKey")
                .param("refundKey", refundKey).update();
        refundJob.run();

        assertThat(refundStatus(refundKey)).isEqualTo("COMPLETED");
    }

    @Test
    void 대행사가_5xx_로_답한_요청은_같은_키로_다시_보내_승인을_한_번만_받는다() {
        String orderNo = newOrderNo();
        nextApprove("ERROR_BEFORE_PROCESSING", 0);
        assertThat(approve(orderNo, 1).outcome()).as("5xx 도 모름").isEqualTo(ApproveOutcome.UNKNOWN);
        makeInquiryDue(orderNo, 1);

        requestedPaymentResolver.run();

        assertThat(statusOf(orderNo, 1)).isEqualTo("APPROVED");
        assertThat(approveCalls()).isEqualTo(2);
        assertThat(eventTypes(orderNo)).containsExactly("APPROVED");
    }

    @Test
    void 환불은_같은_키면_다시_와도_한_건이고_승인_금액을_넘으면_거절한다() {
        String orderNo = newOrderNo();
        approve(orderNo, 1);
        RefundCommand first = new RefundCommand("refund-" + orderNo + "-cancel", orderNo, 1, 40_000);

        assertThat(refunds.accept(first).outcome()).isEqualTo(AcceptOutcome.ACCEPTED);
        AcceptResult again = refunds.accept(first);
        assertThat(again.outcome()).as("접수 응답이 유실돼 다시 온 요청은 거절하지 않는다").isEqualTo(AcceptOutcome.ACCEPTED);
        AcceptResult exceeding = refunds.accept(new RefundCommand("refund-" + orderNo + "-admin-1", orderNo, 1, 10_000));
        assertThat(exceeding.outcome()).isEqualTo(AcceptOutcome.REJECTED);

        refundJob.run();

        assertThat(refundStatus(first.refundKey())).isEqualTo("COMPLETED");
        assertThat(eventTypes(orderNo)).containsExactly("APPROVED", "REFUNDED");
        assertThat(refunds.accept(first).status()).as("접수 응답에 결제 쪽 현재 상태를 싣는다").isEqualTo("COMPLETED");
    }

    @Test
    void 정산_대사는_실패로_닫힌_회차의_늦은_승인과_우리만_승인한_건과_환불_상태_차이를_모두_잡는다() {
        String lateOrder = newOrderNo();
        nextApprove("DECLINE", 0);
        approve(lateOrder, 1);
        fakePg.post().uri("/fake/payments/{key}/approve?amount=45000", PaymentRounds.idempotencyKey(lateOrder, 1))
                .retrieve().toBodilessEntity();

        String oursOnlyOrder = newOrderNo();
        paymentRepository.insert(oursOnlyOrder, 1, PaymentRounds.idempotencyKey(oursOnlyOrder, 1), 45_000,
                io.github.bamdule.dawn.payment.PaymentStatus.APPROVED);
        jdbcClient.sql("UPDATE payment SET approved_at = NOW(3) WHERE order_no = :orderNo").param("orderNo", oursOnlyOrder).update();

        String refundOrder = newOrderNo();
        approve(refundOrder, 1);
        String refundKey = "refund-" + refundOrder + "-cancel";
        refunds.accept(new RefundCommand(refundKey, refundOrder, 1, 45_000));
        refundJob.run();
        fakePg.post().uri("/fake/refunds/{key}/status?status=DECLINED", refundKey).retrieve().toBodilessEntity();

        int mismatches = settlementReconciler.reconcile(LocalDate.now());

        assertThat(mismatches).isGreaterThanOrEqualTo(3);
        assertThat(statusOf(lateOrder, 1)).isEqualTo("APPROVED");
        assertThat(correctionEvents(lateOrder)).containsExactly("APPROVED");
        assertThat(refundStatus(refundKey)).isEqualTo("FAILED");
        assertThat(correctionEvents(refundOrder)).containsExactly("REFUND_FAILED");
        assertThat(mismatchKinds()).contains("PG_APPROVED_OURS_FAILED", "OURS_APPROVED_PG_MISSING", "REFUND_STATUS_DIFF");
    }

    private ApproveResult approve(String orderNo, int paymentRound) {
        return paymentApproval.approve(new ApproveCommand(orderNo, paymentRound, 45_000, "CARD"));
    }

    private void nextApprove(String behavior, long delayMillis) {
        fakePg.post().uri("/fake/behaviors/approve?behavior={behavior}&delayMillis={delay}", behavior, delayMillis)
                .retrieve().toBodilessEntity();
    }

    private void nextInquiry(String behavior) {
        fakePg.post().uri("/fake/behaviors/inquiry?behavior={behavior}", behavior).retrieve().toBodilessEntity();
    }

    private int approveCalls() {
        return fakePg.get().uri("/fake/stats").retrieve().body(Stats.class).approveCalls();
    }

    private void makeInquiryDue(String orderNo, int paymentRound) {
        jdbcClient.sql("""
                UPDATE payment SET requested_at = NOW(3) - INTERVAL 11 SECOND, next_attempt_at = NULL
                 WHERE order_no = :orderNo AND payment_round = :round
                """).param("orderNo", orderNo).param("round", paymentRound).update();
    }

    private String statusOf(String orderNo, int paymentRound) {
        return jdbcClient.sql("SELECT status FROM payment WHERE order_no = :orderNo AND payment_round = :round")
                .param("orderNo", orderNo).param("round", paymentRound).query(String.class).single();
    }

    private String refundStatus(String refundKey) {
        return jdbcClient.sql("SELECT status FROM refund WHERE refund_key = :key").param("key", refundKey).query(String.class).single();
    }

    private List<String> eventTypes(String orderNo) {
        return jdbcClient.sql("SELECT payload->>'$.type' FROM outbox_event WHERE partition_key = :orderNo ORDER BY seq")
                .param("orderNo", orderNo).query(String.class).list();
    }

    private List<String> correctionEvents(String orderNo) {
        return jdbcClient.sql("""
                SELECT payload->>'$.type' FROM outbox_event
                 WHERE partition_key = :orderNo AND payload->>'$.settlementCorrection' = 'true' ORDER BY seq
                """).param("orderNo", orderNo).query(String.class).list();
    }

    private List<String> mismatchKinds() {
        return jdbcClient.sql("SELECT kind FROM settlement_mismatch").query(String.class).list();
    }

    private String newOrderNo() {
        return "20261028-" + NEXT_ORDER.incrementAndGet() + "-" + System.nanoTime() % 1_000_000;
    }

    record Stats(int approveCalls) {
    }
}
