package io.github.bamdule.dawn.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.inventory.stock.SlotStock;
import io.github.bamdule.dawn.inventory.stock.StockLine;
import io.github.bamdule.dawn.messaging.RetentionCleaner;
import io.github.bamdule.dawn.messaging.RetentionRules;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"dawn.reservation.expiry-interval=1h", "dawn.slot.merge-interval=1h"})
@Import({MySqlContainers.class, KafkaContainers.class})
class InventoryRetentionTest {

    @Autowired
    private RetentionRules inventoryRetentionRules;

    @Autowired
    private RetentionCleaner retentionCleaner;

    @Autowired
    private Reservations reservations;

    @Autowired
    private SlotStock slotStock;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private PaymentRoundSealer paymentRoundSealer;

    @Test
    void 구십_일_지난_끝난_예약과_슬롯_내역은_지우고_진행_중인_예약은_오래돼도_남긴다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 100, 10);
        slotStock.spread(productId);
        String oldConfirmed = reserve(productId);
        String recentConfirmed = reserve(productId);
        String oldPaying = reserve(productId);
        setReservation(oldConfirmed, "CONFIRMED", 91);
        setReservation(recentConfirmed, "CONFIRMED", 89);
        setReservation(oldPaying, "PAYING", 91);

        clean();

        assertThat(reservationCount(oldConfirmed)).isZero();
        assertThat(slotCount(oldConfirmed)).as("자식 표인 슬롯 내역을 먼저 지운다").isZero();
        assertThat(reservationCount(recentConfirmed)).isEqualTo(1);
        assertThat(reservationCount(oldPaying)).as("결제 결과를 기다리는 예약은 기간과 관계없이 남긴다").isEqualTo(1);
    }

    @Test
    void 삼십_일_지난_인박스만_지운다() {
        String oldEvent = UUID.randomUUID().toString();
        String recentEvent = UUID.randomUUID().toString();
        jdbcClient.sql("INSERT INTO inbox_event (consumer, event_id, processed_at) VALUES ('retention-test', :eventId, NOW(3) - INTERVAL 31 DAY)")
                .param("eventId", oldEvent).update();
        jdbcClient.sql("INSERT INTO inbox_event (consumer, event_id, processed_at) VALUES ('retention-test', :eventId, NOW(3) - INTERVAL 29 DAY)")
                .param("eventId", recentEvent).update();

        clean();

        assertThat(jdbcClient.sql("SELECT event_id FROM inbox_event WHERE consumer = 'retention-test'").query(String.class).list())
                .containsExactly(recentEvent);
    }

    private void clean() {
        inventoryRetentionRules.rules().forEach(retentionCleaner::clean);
    }

    private String reserve(long productId) {
        String orderNo = InventoryFixture.newOrderNo();
        reservations.reserve(new ReservationCommand(orderNo, "SEOUL", List.of(new StockLine(productId, 1))));
        return orderNo;
    }

    private void setReservation(String orderNo, String status, int daysAgo) {
        jdbcClient.sql("UPDATE stock_reservation SET status = :status, updated_at = NOW(3) - INTERVAL :days DAY WHERE order_no = :orderNo")
                .param("status", status).param("days", daysAgo).param("orderNo", orderNo).update();
    }

    private int reservationCount(String orderNo) {
        return jdbcClient.sql("SELECT COUNT(*) FROM stock_reservation WHERE order_no = :orderNo").param("orderNo", orderNo)
                .query(Integer.class).single();
    }

    private int slotCount(String orderNo) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM reservation_slot s JOIN stock_reservation r ON r.reservation_id = s.reservation_id WHERE r.order_no = :orderNo
                """).param("orderNo", orderNo).query(Integer.class).single()
                + jdbcClient.sql("""
                SELECT COUNT(*) FROM reservation_slot WHERE reservation_id NOT IN (SELECT reservation_id FROM stock_reservation)
                """).query(Integer.class).single();
    }
}
