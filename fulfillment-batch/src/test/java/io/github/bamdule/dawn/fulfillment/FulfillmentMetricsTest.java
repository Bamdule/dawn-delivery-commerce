package io.github.bamdule.dawn.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.fulfillment.allocation.AllocationRunner;
import io.github.bamdule.dawn.fulfillment.target.TargetFixer;
import io.github.bamdule.dawn.messaging.DbGauges;
import io.github.bamdule.dawn.messaging.test.PrometheusScrape;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// 다른 테스트의 회차보다 늦은 회차를 써서 "가장 최근 회차" 지표가 이 테스트의 회차를 가리키게 한다
@AutoConfigureMockMvc
class FulfillmentMetricsTest extends FulfillmentIntegrationTest {

    private static final LocalDateTime FIRST_CUTOFF = LocalDateTime.of(2095, 1, 1, 23, 0);

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private DbGauges dbGauges;

    @Autowired
    private TargetFixer targetFixer;

    @Autowired
    private AllocationRunner allocationRunner;

    @Test
    void 검증_실패와_출고_예외_권역_불일치_반복과_최근_회차의_단계가_지표에_보인다() throws Exception {
        // 첫 회차는 할당 뒤 재고가 사라져 출고 예외가 나고 검증에 실패한다
        long exceptionProduct = newProductId();
        stock(SEOUL_A, exceptionProduct, 1);
        paid(FIRST_CUTOFF, FIRST_CUTOFF.minusHours(1), "SEOUL", line(exceptionProduct, 1));
        targetFixer.fix(FIRST_CUTOFF);
        allocationRunner.allocate(FIRST_CUTOFF);
        stock(SEOUL_A, exceptionProduct, 0);
        run(FIRST_CUTOFF);

        // 이어지는 세 회차 동안 부산에만 있는 상품을 서울 고객에게 팔아 권역 불일치가 반복된다
        long busanOnly = newProductId();
        stock(BUSAN_A, busanOnly, 100);
        LocalDateTime lastCutoff = FIRST_CUTOFF;
        for (int day = 1; day <= 3; day++) {
            lastCutoff = FIRST_CUTOFF.plusDays(day);
            paid(lastCutoff, lastCutoff.minusHours(1), "SEOUL", line(busanOnly, 1));
            run(lastCutoff);
        }
        // 첫 회차에 배정된 뒤 이월됐는데 다음 회차들이 가져가지 못한 대기 수량이다
        long carriedOver = newProductId();
        String carriedOverOrder = paid(null, FIRST_CUTOFF.minusHours(2), "SEOUL", line(carriedOver, 2));
        jdbcClient.sql("UPDATE shipment_target_line SET assigned_cutoff_at = :cutoffAt WHERE order_no = :orderNo")
                .param("cutoffAt", FIRST_CUTOFF).param("orderNo", carriedOverOrder).update();
        dbGauges.refresh();

        String body = scrape();

        assertThat(PrometheusScrape.value(body, "dawn_fulfillment_run_verify_failed_runs")).isGreaterThanOrEqualTo(1);
        assertThat(PrometheusScrape.value(body, "dawn_fulfillment_exception_open_exceptions")).isGreaterThanOrEqualTo(1);
        assertThat(PrometheusScrape.value(body, "dawn_fulfillment_region_mismatch_repeated_pairs")).isGreaterThanOrEqualTo(1);
        assertThat(PrometheusScrape.value(body, "dawn_fulfillment_unfilled_ratio")).isEqualTo(1.0);
        assertThat(PrometheusScrape.value(body, "dawn_fulfillment_carryover_overdue_items")).isGreaterThanOrEqualTo(2);
        assertThat(PrometheusScrape.value(body, "dawn_fulfillment_run_latest_stage")).as("VERIFIED").isEqualTo(5);
        assertThat(PrometheusScrape.value(body, "dawn_fulfillment_run_latest_cutoff_seconds"))
                .isEqualTo(lastCutoff.atZone(ZoneId.of("Asia/Seoul")).toEpochSecond());
    }

    private String scrape() throws Exception {
        return mockMvcTester.get().uri("/actuator/prometheus").exchange().getResponse().getContentAsString();
    }
}
