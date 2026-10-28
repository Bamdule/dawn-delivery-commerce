package io.github.bamdule.dawn.fulfillment.job;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.event.CancelReason;
import io.github.bamdule.dawn.event.OrderEvent;
import io.github.bamdule.dawn.event.OrderEventType;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.fulfillment.FulfillmentIntegrationTest;
import io.github.bamdule.dawn.fulfillment.allocation.AllocationRunner;
import io.github.bamdule.dawn.fulfillment.target.TargetFixer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
class ShipmentJobTest extends FulfillmentIntegrationTest {

    @Autowired
    private TargetFixer targetFixer;

    @Autowired
    private AllocationRunner allocationRunner;

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void 한_센터에서_전량_나갈_주문은_그_센터에서_나가고_나머지는_나눠_보내며_못_채운_수량은_판정한다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long productA = newProductId();
        long productB = newProductId();
        stock(SEOUL_A, productA, 2);
        stock(SEOUL_B, productA, 1);
        stock(SEOUL_B, productB, 1);
        // 먼저 결제한 주문이 먼저 재고를 받는다. 첫 주문은 서울 A 하나로, 둘째 주문은 서울 B 에서, 셋째는 남은 것이 없다
        String first = paid(cutoff, cutoff.minusHours(3), "SEOUL", line(productA, 2));
        String second = paid(cutoff, cutoff.minusHours(2), "SEOUL", line(productA, 1), line(productB, 1));
        String third = paid(cutoff, cutoff.minusHours(1), "SEOUL", line(productA, 1));

        assertThat(run(cutoff).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(shippedByCenter(cutoff, first)).isEqualTo(Map.of(SEOUL_A, 2));
        assertThat(shippedByCenter(cutoff, second)).isEqualTo(Map.of(SEOUL_B, 2));
        assertThat(quantities(third, productA)).containsExactly(0, 0, 1, 0, 0);
        assertThat(outboxTypes(third)).containsExactly("UNFILLABLE");
        assertThat(stockOf(SEOUL_A, productA)).isZero();
        assertThat(stockOf(SEOUL_B, productA)).isZero();
        assertThat(runStatus(cutoff)).isEqualTo("VERIFIED");
    }

    @Test
    void 권역_안에서_못_채운_수량은_새벽_도착이_되는_권역_밖_센터에서_채운다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 1);
        stock(DAEJEON_A, product, 2);
        stock(BUSAN_A, product, 5);
        String orderNo = paid(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 3));

        run(cutoff);

        assertThat(shippedByCenter(cutoff, orderNo)).isEqualTo(Map.of(SEOUL_A, 1, DAEJEON_A, 2));
        assertThat(stockOf(BUSAN_A, product)).as("새벽에 닿지 못하는 센터는 쓰지 않는다").isEqualTo(5);
    }

    @Test
    void PAID_이벤트가_처리_실패_토픽으로_빠진_주문도_출고_대상에_오른다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 1);
        String orderNo = paidWithoutEvent(cutoff, cutoff.minusMinutes(1), "SEOUL", line(product, 1));

        run(cutoff);

        assertThat(shippedByCenter(cutoff, orderNo)).isEqualTo(Map.of(SEOUL_A, 1));
    }

    @Test
    void 마감_뒤에_결제해_배치_전에_등록된_주문은_이번_회차에_오르지_않고_다음_회차에_오른다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        LocalDateTime nextCutoff = cutoff.plusDays(1);
        long product = newProductId();
        stock(SEOUL_A, product, 5);
        String late = paid(nextCutoff, cutoff.plusMinutes(4), "SEOUL", line(product, 1));

        run(cutoff);
        assertThat(shippedByCenter(cutoff, late)).isEmpty();
        assertThat(quantities(late, product)).containsExactly(1, 0, 0, 0, 0);

        run(nextCutoff);
        assertThat(shippedByCenter(nextCutoff, late)).isEqualTo(Map.of(SEOUL_A, 1));
    }

    @Test
    void 마감_전에_취소됐는데_취소_이벤트가_늦은_주문은_출고되지_않고_늦은_이벤트도_아무것도_바꾸지_않는다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 5);
        // 결제 완료 목록에 없다. 주문 서비스에서는 이미 취소됐지만 CANCELLED 이벤트가 아직 오지 않았다
        String cancelled = paid(null, cutoff.minusHours(1), "SEOUL", line(product, 2));

        run(cutoff);
        orderEventConsumer.handle(new OrderEvent(UUID.randomUUID(), OrderEventType.CANCELLED, cancelled, 1L, 10_000L,
                List.of(line(product, 2)), CancelReason.CUSTOMER, null, null, Instant.now()));

        assertThat(shippedByCenter(cutoff, cancelled)).isEmpty();
        assertThat(quantities(cancelled, product)).containsExactly(0, 0, 0, 0, 2);
        assertThat(stockOf(SEOUL_A, product)).isEqualTo(5);
    }

    @Test
    void 목록과_대상이_세_번_다르면_차이_목록을_남기고_잡을_실패시킨다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        // 모르는 주문의 취소가 먼저 와 취소 표시만 남았는데 주문 서비스 목록에는 결제 완료로 있다
        String orderNo = paidWithoutEvent(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 1));
        orderEventConsumer.handle(new OrderEvent(UUID.randomUUID(), OrderEventType.CANCELLED, orderNo, 1L, 10_000L,
                List.of(line(product, 1)), CancelReason.CUSTOMER, null, null, Instant.now()));

        assertThat(run(cutoff).getStatus()).isEqualTo(BatchStatus.FAILED);

        assertThat(jdbcClient.sql("SELECT kind FROM target_fix_diff WHERE cutoff_at = :cutoffAt AND order_no = :orderNo")
                .param("cutoffAt", cutoff).param("orderNo", orderNo).query(String.class).single())
                .isEqualTo("CANCELLED_MARK_BUT_PAID");
        assertThat(runStatus(cutoff)).isEqualTo("STARTED");
    }

    @Test
    void 사람이_차이_목록을_보고_출고_대상을_바로잡으면_같은_마감_시각으로_재시작해_끝낸다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 1);
        String orderNo = paidWithoutEvent(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 1));
        orderEventConsumer.handle(new OrderEvent(UUID.randomUUID(), OrderEventType.CANCELLED, orderNo, 1L, 10_000L,
                List.of(line(product, 1)), CancelReason.CUSTOMER, null, null, Instant.now()));
        assertThat(run(cutoff).getStatus()).isEqualTo(BatchStatus.FAILED);

        assertThat(mockMvcTester.post().uri("/admin/shipment-targets/{orderNo}/resync", orderNo)).hasStatus(204);
        assertThat(mockMvcTester.post().uri("/admin/shipment-targets/{orderNo}/resync", orderNo))
                .as("두 번 눌러도 대기 중인 대상의 품목을 지우지 않는다").hasStatus(409);
        assertThat(run(cutoff).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(shippedByCenter(cutoff, orderNo)).isEqualTo(Map.of(SEOUL_A, 1));
        assertThat(runStatus(cutoff)).isEqualTo("VERIFIED");
    }

    @Test
    void order_events_토픽의_PAID_를_받아_출고_대상을_쌓는다() throws Exception {
        long product = newProductId();
        String orderNo = newOrderNo();
        OrderEvent paid = paidEvent(orderNo, LocalDateTime.now(), "SEOUL", line(product, 2));

        kafkaTemplate.send(Topics.ORDER_EVENTS, orderNo, jsonMapper.writeValueAsString(paid)).get();

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && jdbcClient.sql("SELECT COUNT(*) FROM shipment_target_line WHERE order_no = :orderNo")
                .param("orderNo", orderNo).query(Integer.class).single() == 0) {
            Thread.sleep(200);
        }
        assertThat(quantities(orderNo, product)).containsExactly(2, 0, 0, 0, 0);
    }

    @Test
    void 앞_회차가_끝나지_않았으면_대상_확정을_시작하지_않는다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        jdbcClient.sql("INSERT INTO cutoff_run (cutoff_at, status, updated_at) VALUES (:cutoffAt, 'ALLOCATED', NOW(3))")
                .param("cutoffAt", cutoff.minusDays(1)).update();

        assertThat(run(cutoff).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(runStatus(cutoff)).isEqualTo("NONE");
    }

    @Test
    void 앞_회차가_빠졌으면_그_회차를_먼저_돌리라고_거부하고_돌린_뒤에는_진행한다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        jdbcClient.sql("INSERT INTO cutoff_run (cutoff_at, status, updated_at) VALUES (:cutoffAt, 'VERIFIED', NOW(3))")
                .param("cutoffAt", cutoff.minusDays(2)).update();

        assertThat(run(cutoff).getStatus()).as("하루 전 회차 범위의 주문이 어느 목록에도 오르지 않는다").isEqualTo(BatchStatus.FAILED);
        assertThat(runStatus(cutoff)).isEqualTo("NONE");

        assertThat(run(cutoff.minusDays(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(cutoff).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void 할당_저장_중에_죽고_그사이_입고와_이월이_들어와도_재시작한_결과가_처음과_같다() {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 3);
        stock(SEOUL_B, product, 2);
        List<String> orderNos = List.of(
                paid(cutoff, cutoff.minusHours(4), "SEOUL", line(product, 2)),
                paid(cutoff, cutoff.minusHours(3), "SEOUL", line(product, 2)),
                paid(cutoff, cutoff.minusHours(2), "SEOUL", line(product, 2)));
        targetFixer.fix(cutoff);
        allocationRunner.allocate(cutoff);
        List<String> first = allocationRows(cutoff);

        // 결과 일부만 저장된 채 죽은 상태로 되돌리고, 그사이 실물 재고와 품목 행이 바뀌었다
        jdbcClient.sql("UPDATE cutoff_run SET status = 'TARGET_FIXED' WHERE cutoff_at = :cutoffAt").param("cutoffAt", cutoff).update();
        jdbcClient.sql("DELETE FROM allocation_result WHERE cutoff_at = :cutoffAt LIMIT 1").param("cutoffAt", cutoff).update();
        stock(SEOUL_B, product, 10);
        // 지난 회차의 예외가 배치 중에 이월돼 이번 회차로 배정된 것처럼, 입력을 고정한 뒤 품목 행이 이번 회차를 가리키게 됐다
        String carried = paid(null, cutoff.minusDays(1).minusHours(1), "SEOUL", line(product, 2));
        jdbcClient.sql("UPDATE shipment_target_line SET assigned_cutoff_at = :cutoffAt WHERE order_no = :orderNo")
                .param("cutoffAt", cutoff).param("orderNo", carried).update();
        allocationRunner.allocate(cutoff);

        assertThat(allocationRows(cutoff)).isEqualTo(first);
        assertThat(allocationRows(cutoff)).noneMatch(row -> row.startsWith(carried));
        assertThat(first).hasSize(3);
    }

    @Test
    void 한_품목이_두_센터로_나뉘고_한_센터만_예외면_출고와_예외_수량으로_나뉜다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 2);
        stock(SEOUL_B, product, 1);
        String orderNo = paid(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 3));
        targetFixer.fix(cutoff);
        allocationRunner.allocate(cutoff);
        // 할당 뒤 실사로 서울 B 의 재고가 사라졌다. 출고 지시 때의 조건부 차감이 드러낸다
        stock(SEOUL_B, product, 0);

        run(cutoff);

        assertThat(quantities(orderNo, product)).containsExactly(0, 2, 0, 1, 0);
        assertThat(runStatus(cutoff)).as("예외가 할당 입력의 0.1% 를 넘는다").isEqualTo("VERIFY_FAILED");
    }

    private List<String> allocationRows(LocalDateTime cutoff) {
        return jdbcClient.sql("""
                SELECT CONCAT(order_no, '/', product_id, '/', center_id, '/', quantity) FROM allocation_result
                 WHERE cutoff_at = :cutoffAt ORDER BY order_no, product_id, center_id
                """)
                .param("cutoffAt", cutoff).query(String.class).list();
    }
}
