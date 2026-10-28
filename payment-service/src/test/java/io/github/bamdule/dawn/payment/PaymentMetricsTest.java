package io.github.bamdule.dawn.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.messaging.DbGauges;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import io.github.bamdule.dawn.messaging.test.PrometheusScrape;
import io.github.bamdule.dawn.payment.guard.PgCallGuard;
import io.github.bamdule.dawn.payment.refund.RefundRepository;
import io.github.bamdule.dawn.payment.round.PaymentRounds;
import io.github.bamdule.dawn.payment.round.PaymentRow;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// 동시 호출 한도를 2 로 줄여 세 번째 호출이 거절되게 한다
@SpringBootTest(properties = {
        "management.defaults.metrics.export.enabled=true",
        "dawn.metrics.refresh.enabled=false",
        "dawn.outbox.relay.enabled=false",
        "dawn.pg.max-concurrent-calls=2",
        "dawn.payment.inquiry-interval=1h",
        "dawn.payment.refund-interval=1h",
        "dawn.payment.settlement-cron=-"})
@AutoConfigureMockMvc
@Import({MySqlContainers.class, KafkaContainers.class})
class PaymentMetricsTest {

    private static final AtomicLong NEXT_ORDER = new AtomicLong();

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private DbGauges dbGauges;

    @Autowired
    private PaymentRounds paymentRounds;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private PgCallGuard pgCallGuard;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 십_분_넘은_REQUESTED_와_UNKNOWN_결제가_지표에_보인다() throws Exception {
        PaymentRow stale = open();
        PaymentRow unknown = open();
        jdbcClient.sql("UPDATE payment SET requested_at = NOW(3) - INTERVAL 11 MINUTE WHERE id = :id").param("id", stale.id()).update();
        jdbcClient.sql("UPDATE payment SET status = 'UNKNOWN' WHERE id = :id").param("id", unknown.id()).update();
        dbGauges.refresh();

        String body = scrape();

        assertThat(PrometheusScrape.value(body, "dawn_payment_requested_overdue_payments")).isGreaterThanOrEqualTo(1);
        assertThat(PrometheusScrape.value(body, "dawn_payment_unknown_payments")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 사람이_봐야_하는_환불과_한_시간_넘은_환불과_정산_어긋남이_지표에_보인다() throws Exception {
        PaymentRow approved = open();
        jdbcClient.sql("UPDATE payment SET status = 'APPROVED' WHERE id = :id").param("id", approved.id()).update();
        refundRepository.insertRequested(approved.id(), "refund-" + approved.orderNo() + "-old", 100);
        refundRepository.insertRequested(approved.id(), "refund-" + approved.orderNo() + "-failed", 100);
        jdbcClient.sql("UPDATE refund SET requested_at = NOW(3) - INTERVAL 61 MINUTE WHERE refund_key = :key")
                .param("key", "refund-" + approved.orderNo() + "-old").update();
        jdbcClient.sql("UPDATE refund SET status = 'FAILED' WHERE refund_key = :key")
                .param("key", "refund-" + approved.orderNo() + "-failed").update();
        jdbcClient.sql("""
                INSERT INTO settlement_mismatch (settlement_date, idempotency_key, kind, resolution, created_at)
                VALUES (CURDATE(), :key, 'OURS_APPROVED_PG_MISSING', 'OPEN', NOW(3))
                """).param("key", approved.idempotencyKey()).update();
        dbGauges.refresh();

        String body = scrape();

        assertThat(PrometheusScrape.value(body, "dawn_payment_refund_requested_overdue_refunds")).isGreaterThanOrEqualTo(1);
        assertThat(PrometheusScrape.value(body, "dawn_payment_refund_attention_refunds")).isGreaterThanOrEqualTo(1);
        assertThat(PrometheusScrape.value(body, "dawn_payment_settlement_mismatch_mismatches")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 동시_호출_한도를_넘어_거절하면_사유별_카운터가_오른다() throws Exception {
        String metric = "dawn_payment_pg_rejected_total{reason=\"limit\"";
        double before = sumStartingWith(scrape(), metric);

        assertThat(pgCallGuard.tryEnter()).isTrue();
        assertThat(pgCallGuard.tryEnter()).isTrue();
        assertThat(pgCallGuard.tryEnter()).isFalse();
        pgCallGuard.exit(false, Duration.ZERO);
        pgCallGuard.exit(false, Duration.ZERO);

        assertThat(sumStartingWith(scrape(), metric)).isEqualTo(before + 1);
    }

    private PaymentRow open() {
        String orderNo = "20261028-PM" + NEXT_ORDER.incrementAndGet() + "-" + System.nanoTime() % 100_000;
        return paymentRounds.open(orderNo, 1, 10_000).paymentRow();
    }

    private static double sumStartingWith(String body, String prefix) {
        return body.lines().filter(line -> line.startsWith(prefix))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1))).sum();
    }

    private String scrape() throws Exception {
        return mockMvcTester.get().uri("/actuator/prometheus").exchange().getResponse().getContentAsString();
    }
}
