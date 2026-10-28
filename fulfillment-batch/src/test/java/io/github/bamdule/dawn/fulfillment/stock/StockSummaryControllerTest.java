package io.github.bamdule.dawn.fulfillment.stock;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.fulfillment.FulfillmentIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@AutoConfigureMockMvc
class StockSummaryControllerTest extends FulfillmentIntegrationTest {

    @Autowired
    private MockMvcTester mockMvcTester;

    @Test
    void 상품마다_센터_실물_합과_대기와_예외_수량을_돌려준다() {
        long product = newProductId();
        long untouched = newProductId();
        stock(SEOUL_A, product, 7);
        stock(BUSAN_A, product, 3);
        String orderNo = paid(null, LocalDateTime.now(), "SEOUL", line(product, 4));
        jdbcClient.sql("UPDATE shipment_target_line SET waiting_qty = 2, exception_qty = 1, shipped_qty = 1 WHERE order_no = :orderNo")
                .param("orderNo", orderNo).update();

        assertThat(mockMvcTester.post().uri("/stocks/summary").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productIds\": [" + product + ", " + untouched + "]}"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$[0].physicalQuantity").isEqualTo(10);
                    assertThat(json).extractingPath("$[0].pendingQuantity").isEqualTo(3);
                    assertThat(json).extractingPath("$[1].physicalQuantity").isEqualTo(0);
                    assertThat(json).extractingPath("$[1].pendingQuantity").isEqualTo(0);
                });
    }
}
