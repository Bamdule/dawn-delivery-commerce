package io.github.bamdule.dawn.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import({MySqlContainers.class, KafkaContainers.class})
class ReservationControllerTest {

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 예약하고_결제_시작을_표시하면_상태_조회에_회차가_보인다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        String orderNo = InventoryFixture.newOrderNo();

        assertThat(reserve(orderNo, productId, 2)).hasStatusOk().bodyJson().extractingPath("$.expiresAt").isNotNull();
        assertThat(markPaying(orderNo, 1)).hasStatusOk().bodyJson().extractingPath("$.marked").isEqualTo(true);

        assertThat(mockMvcTester.get().uri("/reservations/{orderNo}", orderNo))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.status").isEqualTo("PAYING");
                    assertThat(json).extractingPath("$.paymentRound").isEqualTo(1);
                    assertThat(json).extractingPath("$.expired").isEqualTo(false);
                });
    }

    @Test
    void 재고가_모자라면_409_와_품절_상품을_돌려준다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 1, 10);

        assertThat(reserve(InventoryFixture.newOrderNo(), productId, 2))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.productId")
                .isEqualTo(Math.toIntExact(productId));
    }

    @Test
    void 만료된_예약에_결제_시작을_표시하면_0행과_만료를_돌려준다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        String orderNo = InventoryFixture.newOrderNo();
        reserve(orderNo, productId, 1);
        InventoryFixture.expireNow(jdbcClient, orderNo);

        assertThat(markPaying(orderNo, 1))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.marked").isEqualTo(false);
                    assertThat(json).extractingPath("$.currentStatus").isEqualTo("HELD");
                    assertThat(json).extractingPath("$.expired").isEqualTo(true);
                });
    }

    @Test
    void 되돌림은_같은_회차일_때만_HELD_로_바꾼다() {
        long productId = InventoryFixture.newProduct(jdbcClient, 10, 10);
        String orderNo = InventoryFixture.newOrderNo();
        reserve(orderNo, productId, 1);
        markPaying(orderNo, 2);

        assertThat(mockMvcTester.post().uri("/reservations/{orderNo}/paying/1/revert", orderNo))
                .hasStatusOk().bodyJson().extractingPath("$.reverted").isEqualTo(false);
        assertThat(mockMvcTester.post().uri("/reservations/{orderNo}/paying/2/revert", orderNo))
                .hasStatusOk().bodyJson().extractingPath("$.reverted").isEqualTo(true);
    }

    @Test
    void 예약이_없으면_상태_조회와_결제_시작_표시가_404_다() {
        String orderNo = InventoryFixture.newOrderNo();

        assertThat(mockMvcTester.get().uri("/reservations/{orderNo}", orderNo)).hasStatus(404);
        assertThat(markPaying(orderNo, 1)).hasStatus(404);
    }

    private MvcTestResult reserve(String orderNo, long productId, int quantity) {
        return mockMvcTester.post().uri("/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderNo": "%s", "regionCode": "SEOUL", "lines": [{"productId": %d, "quantity": %d}]}
                        """.formatted(orderNo, productId, quantity))
                .exchange();
    }

    private MvcTestResult markPaying(String orderNo, int paymentRound) {
        return mockMvcTester.post().uri("/reservations/{orderNo}/paying", orderNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentRound\": " + paymentRound + "}")
                .exchange();
    }
}
