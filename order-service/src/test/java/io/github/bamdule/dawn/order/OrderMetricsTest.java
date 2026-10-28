package io.github.bamdule.dawn.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.event.PaymentEvent;
import io.github.bamdule.dawn.event.PaymentEventType;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.messaging.DbGauges;
import io.github.bamdule.dawn.messaging.test.PrometheusScrape;
import io.github.bamdule.dawn.order.recovery.PaymentEventConsumer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// 설계 01 "운영 지표" 표의 경보 기준을 넘는 상황을 만들고 /actuator/prometheus 에서 값을 읽는다
@AutoConfigureMockMvc
class OrderMetricsTest extends OrderIntegrationTest {

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private DbGauges dbGauges;

    @Autowired
    private PaymentEventConsumer paymentEventConsumer;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void 십오_분_넘은_PENDING_주문과_결제_시도가_지표에_보인다() throws Exception {
        String orderNo = placeOne(10_000).orderNo();
        jdbcClient.sql("""
                UPDATE orders SET status = 'PENDING', payment_attempt = 'IN_PROGRESS',
                       created_at = NOW(3) - INTERVAL 16 MINUTE, attempt_started_at = NOW(3) - INTERVAL 16 MINUTE
                 WHERE order_no = :orderNo
                """).param("orderNo", orderNo).update();
        dbGauges.refresh();

        String body = scrape();

        assertThat(PrometheusScrape.value(body, "dawn_order_pending_overdue_orders")).isGreaterThanOrEqualTo(1);
        assertThat(PrometheusScrape.value(body, "dawn_order_attempt_overdue_attempts")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 접수되지_않은_환불과_오래된_ACCEPTED_와_거절된_환불이_지표에_보인다() throws Exception {
        String orderNo = placeOne(10_000).orderNo();
        insertRefund(orderNo, "unaccepted", "REQUESTED", "created_at = NOW(3) - INTERVAL 11 MINUTE");
        insertRefund(orderNo, "accepted", "ACCEPTED", "accepted_at = NOW(3) - INTERVAL 3 HOUR");
        insertRefund(orderNo, "rejected", "REJECTED", "created_at = NOW(3)");
        dbGauges.refresh();

        String body = scrape();

        assertThat(PrometheusScrape.value(body, "dawn_order_refund_unaccepted_refunds")).isGreaterThanOrEqualTo(1);
        assertThat(PrometheusScrape.value(body, "dawn_order_refund_accepted_overdue_refunds")).isGreaterThanOrEqualTo(1);
        assertThat(PrometheusScrape.value(body, "dawn_order_refund_attention_refunds")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 쓰이지_않은_승인이_오면_카운터가_오른다() throws Exception {
        double before = PrometheusScrape.value(scrape(), "dawn_order_orphan_approval_total");
        String orderNo = placeOne(10_000).orderNo();

        paymentEventConsumer.handle(new PaymentEvent(UUID.randomUUID(), PaymentEventType.APPROVED, orderNo, 1, 10_000, null,
                true, Instant.now()));

        assertThat(PrometheusScrape.value(scrape(), "dawn_order_orphan_approval_total")).isEqualTo(before + 1);
    }

    @Test
    void 오래된_PENDING_아웃박스와_여러_번_실패한_이벤트가_지표에_보인다() throws Exception {
        jdbcClient.sql("""
                INSERT INTO outbox_event (event_id, topic, partition_key, payload, status, attempt_count, next_attempt_at, created_at)
                VALUES (:eventId, 'order.events', 'metrics-test', '{}', 'PENDING', 11, NOW(3) + INTERVAL 1 HOUR, NOW(3) - INTERVAL 40 SECOND)
                """).param("eventId", UUID.randomUUID().toString()).update();
        dbGauges.refresh();

        String body = scrape();

        assertThat(PrometheusScrape.value(body, "dawn_outbox_pending_oldest_age_seconds")).isGreaterThanOrEqualTo(40);
        assertThat(PrometheusScrape.value(body, "dawn_outbox_pending_stuck_events")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 처리하지_못한_메시지가_처리_실패_토픽으로_가면_토픽별_카운터가_오른다() throws Exception {
        String metric = "dawn_consumer_dead_letter_total{topic=\"payment.events\"";
        double before = sumStartingWith(scrape(), metric);

        kafkaTemplate.send(Topics.PAYMENT_EVENTS, "broken", "형식이 깨진 메시지").get();

        long deadline = System.currentTimeMillis() + 30_000;
        while (sumStartingWith(scrape(), metric) == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        assertThat(sumStartingWith(scrape(), metric)).isEqualTo(before + 1);
    }

    private void insertRefund(String orderNo, String suffix, String status, String timeColumn) {
        jdbcClient.sql("""
                INSERT INTO refund_request (order_no, refund_key, payment_round, amount, reason, status, next_attempt_at, created_at, updated_at)
                VALUES (:orderNo, :refundKey, 1, 1000, 'CUSTOMER_CANCEL', :status, NOW(3) + INTERVAL 1 HOUR, NOW(3), NOW(3))
                """)
                .param("orderNo", orderNo).param("refundKey", "refund-" + orderNo + "-metrics-" + suffix).param("status", status)
                .update();
        jdbcClient.sql("UPDATE refund_request SET " + timeColumn + " WHERE refund_key = :refundKey")
                .param("refundKey", "refund-" + orderNo + "-metrics-" + suffix).update();
    }

    private static double sumStartingWith(String body, String prefix) {
        return body.lines().filter(line -> line.startsWith(prefix))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1))).sum();
    }

    private String scrape() throws Exception {
        return mockMvcTester.get().uri("/actuator/prometheus").exchange().getResponse().getContentAsString();
    }
}
