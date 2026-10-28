package io.github.bamdule.dawn.fulfillment.stock;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.fulfillment.FulfillmentIntegrationTest;
import java.time.LocalDateTime;
import io.github.bamdule.dawn.fulfillment.shipment.ShipmentHandover;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CenterStocksTest extends FulfillmentIntegrationTest {

    @Autowired
    private CenterStocks centerStocks;

    @Autowired
    private ShipmentHandover shipmentHandover;

    @Test
    void 입고하면_실물_재고와_조정_이력_STOCK_CHANGED_를_함께_남긴다() {
        long product = newProductId();
        stock(SEOUL_A, product, 3);

        centerStocks.inbound(SEOUL_A, product, 5);

        assertThat(stockOf(SEOUL_A, product)).isEqualTo(8);
        assertThat(outboxTypes(String.valueOf(product))).containsExactly("STOCK_CHANGED");
        assertThat(quantityDelta(product)).isEqualTo(5);
    }

    @Test
    void 출고_지시_뒤_피킹_전에_실사를_해도_판매_가능_재고가_늘지_않는다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 10);
        paid(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 4));
        run(cutoff);
        assertThat(stockOf(SEOUL_A, product)).isEqualTo(6);

        // 선반에는 아직 10개가 있다. 출고 지시된 4개가 피킹 전이다
        int applied = centerStocks.stocktake(SEOUL_A, product, 10);

        assertThat(applied).isZero();
        assertThat(stockOf(SEOUL_A, product)).isEqualTo(6);
        assertThat(outboxTypes(String.valueOf(product))).isEmpty();
    }

    @Test
    void 배차로_넘긴_출고_지시는_피킹_전으로_세지_않아_실사가_재고를_줄이지_않는다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 10);
        paid(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 4));
        run(cutoff);
        assertThat(shipmentHandover.handOver(cutoff)).contains(1);

        // 피킹이 끝나 선반에 6개가 남았다. 넘긴 4개를 다시 빼면 전산이 2 가 된다
        int applied = centerStocks.stocktake(SEOUL_A, product, 6);

        assertThat(applied).isZero();
        assertThat(stockOf(SEOUL_A, product)).isEqualTo(6);
    }

    @Test
    void 실제로_모자라면_차이만큼_줄이고_음수_변화를_알린다() throws Exception {
        LocalDateTime cutoff = newCutoff();
        long product = newProductId();
        stock(SEOUL_A, product, 10);
        paid(cutoff, cutoff.minusHours(1), "SEOUL", line(product, 4));
        run(cutoff);

        // 선반에 8개. 피킹 전 4개를 빼면 전산 6 이 아니라 4 여야 한다
        int applied = centerStocks.stocktake(SEOUL_A, product, 8);

        assertThat(applied).isEqualTo(-2);
        assertThat(stockOf(SEOUL_A, product)).isEqualTo(4);
        assertThat(quantityDelta(product)).isEqualTo(-2);
    }

    private int quantityDelta(long product) {
        return jdbcClient.sql("SELECT JSON_EXTRACT(payload, '$.quantityDelta') FROM outbox_event WHERE partition_key = :key ORDER BY seq DESC LIMIT 1")
                .param("key", String.valueOf(product)).query(Integer.class).single();
    }
}
