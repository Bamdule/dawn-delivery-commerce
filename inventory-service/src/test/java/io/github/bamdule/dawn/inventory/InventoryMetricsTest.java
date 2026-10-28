package io.github.bamdule.dawn.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.inventory.reservation.ReservationCommand;
import io.github.bamdule.dawn.inventory.reservation.ReservationLifecycle;
import io.github.bamdule.dawn.inventory.reservation.Reservations;
import io.github.bamdule.dawn.inventory.stock.StockLine;
import io.github.bamdule.dawn.messaging.DbGauges;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import io.github.bamdule.dawn.messaging.test.PrometheusScrape;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// 테스트에서는 지표 내보내기가 기본으로 꺼져 있어 켠다. 주기 갱신은 끄고 테스트가 직접 갱신한다
@SpringBootTest(properties = {"management.defaults.metrics.export.enabled=true", "dawn.metrics.refresh.enabled=false",
        "dawn.reservation.expiry-interval=1h"})
@AutoConfigureMockMvc
@Import({MySqlContainers.class, KafkaContainers.class})
class InventoryMetricsTest {

    private static final AtomicLong NEXT_PRODUCT_ID = new AtomicLong(800_000);

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private DbGauges dbGauges;

    @Autowired
    private Reservations reservations;

    @Autowired
    private ReservationLifecycle reservationLifecycle;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 만료_5분_지난_HELD_가_있으면_경보_지표가_1_이상이다() throws Exception {
        String orderNo = reserve();
        jdbcClient.sql("UPDATE stock_reservation SET expires_at = NOW(3) - INTERVAL 6 MINUTE WHERE order_no = :orderNo")
                .param("orderNo", orderNo).update();
        dbGauges.refresh();

        assertThat(PrometheusScrape.value(scrape(), "dawn_inventory_held_expired_overdue_reservations")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 풀린_예약에_확정이_오면_카운터가_오른다() throws Exception {
        double before = PrometheusScrape.value(scrape(), "dawn_inventory_confirm_released_total");
        String orderNo = reserve();
        jdbcClient.sql("UPDATE stock_reservation SET status = 'RELEASED' WHERE order_no = :orderNo").param("orderNo", orderNo).update();

        reservationLifecycle.confirm(orderNo);

        assertThat(PrometheusScrape.value(scrape(), "dawn_inventory_confirm_released_total")).isEqualTo(before + 1);
    }

    @Test
    void 만료_배치가_마지막으로_돈_뒤의_시간과_컨슈머_랙이_보인다() throws Exception {
        String body = scrape();

        assertThat(PrometheusScrape.has(body, "dawn_inventory_expiry_idle_seconds")).isTrue();
        assertThat(PrometheusScrape.has(body, "kafka_consumer_fetch_manager_records_lag_max")).isTrue();
    }

    private String reserve() {
        long productId = NEXT_PRODUCT_ID.incrementAndGet();
        jdbcClient.sql("INSERT INTO stock (product_id, quantity, slot_mode, hold_minutes, deficit, updated_at) VALUES (:productId, 5, 'NONE', 10, 0, NOW(3))")
                .param("productId", productId).update();
        String orderNo = "20261028-M" + productId;
        reservations.reserve(new ReservationCommand(orderNo, "SEOUL", List.of(new StockLine(productId, 1))));
        return orderNo;
    }

    private String scrape() throws Exception {
        return mockMvcTester.get().uri("/actuator/prometheus").exchange().getResponse().getContentAsString();
    }
}
