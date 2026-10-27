package io.github.bamdule.dawn.order.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.order.OrderIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
class OrderQueryControllerTest extends OrderIntegrationTest {

    // 다른 테스트의 주문과 섞이지 않게 아무도 쓰지 않는 먼 과거의 회차를 쓴다
    private static final String CUTOFF = "2020-03-02T23:00:00";

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void 회차의_PAID_목록은_전_회차_마감_뒤부터_이번_마감까지이고_커서로_끝까지_나눠_받는다() throws Exception {
        String beforeRange = paidAt("2020-03-01 23:00:00.000");
        String first = paidAt("2020-03-01 23:00:00.001");
        String second = paidAt("2020-03-02 12:00:00.000");
        String sameTime = paidAt("2020-03-02 12:00:00.000");
        String atCutoff = paidAt("2020-03-02 23:00:00.000");
        String afterCutoff = paidAt("2020-03-02 23:00:00.001");
        String cancelled = paidAt("2020-03-02 13:00:00.000");
        jdbcClient.sql("UPDATE orders SET status = 'CANCELLED' WHERE order_no = :orderNo").param("orderNo", cancelled).update();

        List<String> collected = new ArrayList<>();
        String cursor = "";
        for (int page = 0; page < 10; page++) {
            MvcTestResult result = mockMvcTester.get().uri("/orders/paid?cutoffAt=" + CUTOFF + "&size=2" + cursor).exchange();
            Map<?, ?> body = jsonMapper.readValue(result.getResponse().getContentAsString(), Map.class);
            collected.addAll((List<String>) body.get("orderNos"));
            if (body.get("nextCursorId") == null) {
                break;
            }
            cursor = "&cursorPaidAt=" + body.get("nextCursorPaidAt") + "&cursorId=" + body.get("nextCursorId");
        }

        assertThat(collected).containsExactlyInAnyOrder(first, second, sameTime, atCutoff);
        assertThat(collected).doesNotContain(beforeRange, afterCutoff, cancelled);
    }

    @Test
    void 주문_상세에_품목과_취소_수량이_실린다() {
        long productId = newProductId();
        String orderNo = orderPlacement.place(command(newCheckoutId(), item(productId, 2, 10_000))).orderNo();

        assertThat(mockMvcTester.get().uri("/orders/{orderNo}", orderNo))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.status").isEqualTo("PAID");
                    assertThat(json).extractingPath("$.regionCode").isEqualTo("SEOUL");
                    assertThat(json).extractingPath("$.items[0].quantity").isEqualTo(2);
                    assertThat(json).extractingPath("$.items[0].cancelledQuantity").isEqualTo(0);
                });
        assertThat(mockMvcTester.get().uri("/orders/{orderNo}", "20260101-NOTEXISTS")).hasStatus(404);
    }

    private String paidAt(String paidAt) {
        String orderNo = placeOne(10_000).orderNo();
        jdbcClient.sql("UPDATE orders SET paid_at = :paidAt WHERE order_no = :orderNo")
                .param("paidAt", paidAt).param("orderNo", orderNo).update();
        return orderNo;
    }
}
