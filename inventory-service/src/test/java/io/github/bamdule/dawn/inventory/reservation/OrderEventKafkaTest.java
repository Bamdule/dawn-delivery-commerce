package io.github.bamdule.dawn.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.event.OrderEvent;
import io.github.bamdule.dawn.event.OrderEventType;
import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.inventory.stock.StockLine;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"dawn.reservation.expiry-interval=1h", "spring.kafka.consumer.auto-offset-reset=earliest"})
@Import({MySqlContainers.class, KafkaContainers.class})
class OrderEventKafkaTest {

    @Autowired
    private Reservations reservations;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private PaymentRoundSealer paymentRoundSealer;

    @Test
    void order_events_토픽의_PAID_를_받아_예약을_확정한다() throws Exception {
        long productId = InventoryFixture.newProduct(jdbcClient, 3, 10);
        String orderNo = InventoryFixture.newOrderNo();
        reservations.reserve(new ReservationCommand(orderNo, "SEOUL", List.of(new StockLine(productId, 1))));
        reservations.markPaying(orderNo, 1);
        OrderEvent paid = new OrderEvent(UUID.randomUUID(), OrderEventType.PAID, orderNo, 1L, 10_000L,
                List.of(new OrderEvent.OrderLine(productId, 1)), null, Instant.now());

        kafkaTemplate.send(Topics.ORDER_EVENTS, orderNo, jsonMapper.writeValueAsString(paid)).get();

        long deadline = System.currentTimeMillis() + 20_000;
        String status = "PAYING";
        while (!"CONFIRMED".equals(status) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            status = InventoryFixture.statusOf(jdbcClient, orderNo, productId);
        }
        assertThat(status).isEqualTo("CONFIRMED");
    }
}
