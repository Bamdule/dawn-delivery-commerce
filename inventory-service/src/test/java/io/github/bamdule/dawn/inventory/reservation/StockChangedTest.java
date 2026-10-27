package io.github.bamdule.dawn.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.event.FulfillmentEvent;
import io.github.bamdule.dawn.event.FulfillmentEventType;
import io.github.bamdule.dawn.inventory.consumer.FulfillmentEventConsumer;
import io.github.bamdule.dawn.inventory.stock.SlotStock;
import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"dawn.reservation.expiry-interval=1h", "dawn.slot.merge-interval=1h"})
@Import({MySqlContainers.class, KafkaContainers.class})
class StockChangedTest {

    private static final AtomicLong NEXT_ADJUSTMENT = new AtomicLong();

    @Autowired
    private FulfillmentEventConsumer fulfillmentEventConsumer;

    @Autowired
    private SlotStock slotStock;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private PaymentRoundSealer paymentRoundSealer;

    @Test
    void 다_팔린_상품의_실사_감소는_부족분으로_들고_있다가_입고가_먼저_갚는다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 0, 10);

        fulfillmentEventConsumer.handle(stockChanged(productId, -2));
        assertThat(InventoryFixture.deficitOf(jdbcClient, productId)).isEqualTo(2);

        fulfillmentEventConsumer.handle(stockChanged(productId, 5));

        assertThat(InventoryFixture.quantityOf(jdbcClient, productId)).isEqualTo(3);
        assertThat(InventoryFixture.deficitOf(jdbcClient, productId)).isZero();
    }

    @Test
    void 같은_조정_이벤트가_두_번_와도_한_번만_반영한다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        FulfillmentEvent inbound = stockChanged(productId, 4);

        fulfillmentEventConsumer.handle(inbound);
        fulfillmentEventConsumer.handle(inbound);

        assertThat(InventoryFixture.quantityOf(jdbcClient, productId)).isEqualTo(14);
    }

    @Test
    void 슬롯_상품의_실사_감소는_슬롯_번호_순으로_빼고_모자란_몫을_부족분에_더한다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 3);
        slotStock.spread(productId);

        fulfillmentEventConsumer.handle(stockChanged(productId, -12));

        assertThat(slots(productId)).containsOnly(0);
        assertThat(InventoryFixture.deficitOf(jdbcClient, productId)).isEqualTo(2);
    }

    @Test
    void 합쳐진_슬롯_상품의_실사_감소는_슬롯_0_에서만_뺀다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 30, 3);
        slotStock.spread(productId);
        jdbcClient.sql("UPDATE stock_slot SET quantity = CASE WHEN slot_no = 0 THEN 5 ELSE 0 END WHERE product_id = :productId")
                .param("productId", productId).update();
        jdbcClient.sql("UPDATE stock SET slot_mode = 'MERGED' WHERE product_id = :productId").param("productId", productId).update();

        fulfillmentEventConsumer.handle(stockChanged(productId, -3));

        assertThat(slots(productId).getFirst()).isEqualTo(2);
        assertThat(InventoryFixture.deficitOf(jdbcClient, productId)).isZero();
    }

    private static FulfillmentEvent stockChanged(long productId, int quantityDelta) {
        return new FulfillmentEvent(UUID.randomUUID(), FulfillmentEventType.STOCK_CHANGED, null, null, null, null,
                NEXT_ADJUSTMENT.incrementAndGet(), productId, quantityDelta, Instant.now());
    }

    private List<Integer> slots(long productId) {
        return jdbcClient.sql("SELECT quantity FROM stock_slot WHERE product_id = :productId ORDER BY slot_no")
                .param("productId", productId).query(Integer.class).list();
    }
}
