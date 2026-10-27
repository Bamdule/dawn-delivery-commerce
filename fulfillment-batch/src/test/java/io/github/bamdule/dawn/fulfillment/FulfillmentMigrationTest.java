package io.github.bamdule.dawn.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FulfillmentMigrationTest extends FulfillmentIntegrationTest {

    @Test
    void 기동하면_Flyway_마이그레이션으로_설계의_테이블이_만들어진다() {
        List<String> tables = jdbcClient.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")
                .query(String.class)
                .list();

        assertThat(tables).contains("cutoff_run", "shipment_target", "shipment_target_line", "center", "center_coverage",
                "center_stock", "allocation_input", "allocation_result", "unfillable_decision", "shipment_order",
                "shipment_exception", "inbox_event", "outbox_event", "BATCH_JOB_INSTANCE");
    }
}
