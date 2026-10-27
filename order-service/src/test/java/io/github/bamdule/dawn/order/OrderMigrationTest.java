package io.github.bamdule.dawn.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.messaging.test.KafkaContainers;
import io.github.bamdule.dawn.messaging.test.MySqlContainers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import({MySqlContainers.class, KafkaContainers.class})
class OrderMigrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 기동하면_Flyway_마이그레이션으로_설계의_테이블이_만들어진다() {
        List<String> tables = jdbcClient.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")
                .query(String.class)
                .list();

        assertThat(tables).contains("orders", "order_item", "refund_request", "outbox_event");
    }
}
