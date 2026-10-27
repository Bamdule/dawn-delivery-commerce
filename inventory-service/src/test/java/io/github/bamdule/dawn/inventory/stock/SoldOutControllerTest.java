package io.github.bamdule.dawn.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@Import({MySqlContainers.class, KafkaContainers.class})
class SoldOutControllerTest {

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 판매_가능_재고가_0인_상품만_품절_목록에_나온다() {
        long soldOut = StockFixture.newProduct(jdbcClient, 0);
        long available = StockFixture.newProduct(jdbcClient, 5);
        long slotSoldOut = StockFixture.newProduct(jdbcClient, 0);
        jdbcClient.sql("UPDATE stock SET slot_mode = 'SPREAD' WHERE product_id = :productId").param("productId", slotSoldOut).update();
        jdbcClient.sql("INSERT INTO stock_slot (product_id, slot_no, quantity, updated_at) VALUES (:productId, 0, 0, NOW(3))")
                .param("productId", slotSoldOut).update();

        assertThat(mockMvcTester.get().uri("/stocks/sold-out"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.productIds")
                .asArray()
                // JSON 숫자는 작은 값이면 Integer 로 읽힌다
                .contains(Math.toIntExact(soldOut), Math.toIntExact(slotSoldOut))
                .doesNotContain(Math.toIntExact(available));
    }
}
