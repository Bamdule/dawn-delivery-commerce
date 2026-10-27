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
class RegionBlockControllerTest {

    @Autowired
    private MockMvcTester mockMvcTester;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 판매_제한_권역을_두_번_설정해도_한_줄이고_해제하면_사라진다() {
        long productId = StockFixture.newProduct(jdbcClient, 10);
        String uri = "/admin/stocks/" + productId + "/blocked-regions/SEOUL";

        assertThat(mockMvcTester.put().uri(uri)).hasStatus(204);
        assertThat(mockMvcTester.put().uri(uri)).hasStatus(204);
        assertThat(blockedCount(productId)).isEqualTo(1);

        assertThat(mockMvcTester.delete().uri(uri)).hasStatus(204);
        assertThat(blockedCount(productId)).isZero();
    }

    private int blockedCount(long productId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM stock_region_block WHERE product_id = :productId")
                .param("productId", productId).query(Integer.class).single();
    }
}
