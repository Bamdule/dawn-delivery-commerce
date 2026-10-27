package io.github.bamdule.dawn.fulfillment.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.fulfillment.FulfillmentIntegrationTest;
import io.github.bamdule.dawn.fulfillment.allocation.AllocationRunner;
import io.github.bamdule.dawn.fulfillment.shipment.ShipmentCreator;
import io.github.bamdule.dawn.fulfillment.target.TargetFixer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@AutoConfigureMockMvc
class ShipmentExceptionsTest extends FulfillmentIntegrationTest {

    @Autowired
    private ShipmentExceptions shipmentExceptions;

    @Autowired
    private ShipmentCreator shipmentCreator;

    @Autowired
    private TargetFixer targetFixer;

    @Autowired
    private AllocationRunner allocationRunner;

    @Autowired
    private MockMvcTester mockMvcTester;

    @Test
    void 다음_회차가_입력을_고정한_뒤에_이월해도_그_수량이_그다음_회차에_오른다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 1);
        String orderNo = exceptionAtSeoulA(cutoff, product);
        long exceptionNo = exceptionNoOf(cutoff, orderNo);
        stock(SEOUL_A, product, 5);

        // 다음 회차의 대상 확정이 끝난 뒤 이월된다
        targetFixer.fix(cutoff.plusDays(1));
        assertThat(shipmentExceptions.defer(exceptionNo)).isTrue();
        run(cutoff.plusDays(1));
        assertThat(shippedByCenter(cutoff.plusDays(1), orderNo)).isEmpty();
        assertThat(quantities(orderNo, product)).containsExactly(1, 0, 0, 0, 0);

        run(cutoff.plusDays(2));

        assertThat(shippedByCenter(cutoff.plusDays(2), orderNo)).isEqualTo(Map.of(SEOUL_A, 1));
        assertThat(quantities(orderNo, product)).containsExactly(0, 1, 0, 0, 0);
    }

    @Test
    void 한_예외에_이월과_관리자_취소를_동시에_보내도_한쪽만_일어난다() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            LocalDateTime cutoff = newCutoff();
            long product = newProductId();
            String orderNo = exceptionAtSeoulA(cutoff, product);
            long exceptionNo = exceptionNoOf(cutoff, orderNo);

            List<Boolean> results = concurrently(() -> shipmentExceptions.defer(exceptionNo), () -> shipmentExceptions.cancel(exceptionNo));

            assertThat(results).containsExactlyInAnyOrder(true, false);
            List<Integer> quantities = quantities(orderNo, product);
            assertThat(quantities.get(0) + quantities.get(4)).as("대기나 취소 한쪽으로만 옮겨진다").isEqualTo(1);
            assertThat(quantities.get(3)).isZero();
        }
    }

    @Test
    void 관리자_취소는_예외_수량을_취소로_옮기고_예외_번호로_EXCEPTION_CANCELLED_를_낸다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        String orderNo = exceptionAtSeoulA(cutoff, product);
        long exceptionNo = exceptionNoOf(cutoff, orderNo);

        assertThat(mockMvcTester.post().uri("/admin/shipment-exceptions/{exceptionNo}/cancel", exceptionNo)).hasStatus(204);
        assertThat(mockMvcTester.post().uri("/admin/shipment-exceptions/{exceptionNo}/defer", exceptionNo)).hasStatus(409);

        assertThat(quantities(orderNo, product)).containsExactly(0, 0, 0, 0, 1);
        assertThat(outboxTypes(orderNo)).containsExactly("EXCEPTION_CANCELLED");
        assertThat(jdbcClient.sql("SELECT JSON_EXTRACT(payload, '$.exceptionNo') FROM outbox_event WHERE partition_key = :orderNo")
                .param("orderNo", orderNo).query(Long.class).single()).isEqualTo(exceptionNo);
    }

    @Test
    void 배차_전_출고_지시를_취소하면_실물_재고를_되돌리고_출고_예외로_돌린다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 3);
        String orderNo = paid(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 2));
        run(cutoff);
        long shipmentOrderId = jdbcClient.sql("SELECT id FROM shipment_order WHERE cutoff_at = :cutoffAt AND order_no = :orderNo")
                .param("cutoffAt", cutoff).param("orderNo", orderNo).query(Long.class).single();

        assertThat(mockMvcTester.post().uri("/admin/shipment-orders/{id}/cancel", shipmentOrderId)).hasStatus(204);
        assertThat(mockMvcTester.post().uri("/admin/shipment-orders/{id}/cancel", shipmentOrderId)).hasStatus(409);

        assertThat(stockOf(SEOUL_A, product)).isEqualTo(3);
        assertThat(quantities(orderNo, product)).containsExactly(0, 0, 0, 2, 0);
        assertThat(jdbcClient.sql("SELECT status FROM shipment_exception WHERE cutoff_at = :cutoffAt AND order_no = :orderNo")
                .param("cutoffAt", cutoff).param("orderNo", orderNo).query(String.class).single()).isEqualTo("OPEN");
    }

    @Test
    void 출고_지시_스텝을_다시_돌려도_출고_지시와_재고_차감이_두_번_일어나지_않는다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 5);
        String orderNo = paid(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 2));
        run(cutoff);
        // 출고 지시 청크를 커밋한 뒤 스텝이 끝나기 전에 죽은 상태로 되돌린다
        jdbcClient.sql("UPDATE cutoff_run SET status = 'UNFILLABLE_PUBLISHED' WHERE cutoff_at = :cutoffAt").param("cutoffAt", cutoff).update();

        shipmentCreator.createForCenter(cutoff, SEOUL_A);

        assertThat(stockOf(SEOUL_A, product)).isEqualTo(3);
        assertThat(shippedByCenter(cutoff, orderNo)).isEqualTo(Map.of(SEOUL_A, 2));
        assertThat(quantities(orderNo, product)).containsExactly(0, 2, 0, 0, 0);
    }

    @Test
    void 검증에_실패한_회차는_사람이_승인해야_VERIFIED_가_된다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        exceptionAtSeoulA(cutoff, newProductId());
        assertThat(runStatus(cutoff)).isEqualTo("VERIFY_FAILED");

        assertThat(mockMvcTester.post().uri("/admin/cutoff-runs/{cutoffAt}/approve", cutoff.toString())).hasStatus(204);

        assertThat(runStatus(cutoff)).isEqualTo("VERIFIED");
    }

    // 서울 A 에 재고 1 을 두고 할당까지 한 뒤 재고를 없애, 출고 지시 때 예외 하나를 만든다
    private String exceptionAtSeoulA(LocalDateTime cutoff, long product) throws Exception {
        stock(SEOUL_A, product, 1);
        String orderNo = paid(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 1));
        targetFixer.fix(cutoff);
        allocationRunner.allocate(cutoff);
        stock(SEOUL_A, product, 0);
        run(cutoff);
        assertThat(quantities(orderNo, product)).containsExactly(0, 0, 0, 1, 0);
        return orderNo;
    }

    private long exceptionNoOf(LocalDateTime cutoff, String orderNo) {
        return jdbcClient.sql("SELECT id FROM shipment_exception WHERE cutoff_at = :cutoffAt AND order_no = :orderNo")
                .param("cutoffAt", cutoff).param("orderNo", orderNo).query(Long.class).single();
    }

    @SafeVarargs
    private static List<Boolean> concurrently(java.util.concurrent.Callable<Boolean>... tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executorService = Executors.newFixedThreadPool(tasks.length)) {
            List<Future<Boolean>> futures = java.util.Arrays.stream(tasks)
                    .map(task -> executorService.submit(() -> {
                        start.await();
                        return task.call();
                    }))
                    .toList();
            start.countDown();
            List<Boolean> results = new java.util.ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
