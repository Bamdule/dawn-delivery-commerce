package io.github.bamdule.dawn.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bamdule.dawn.fulfillment.allocation.AllocationRunner;
import io.github.bamdule.dawn.fulfillment.target.TargetFixer;
import io.github.bamdule.dawn.messaging.RetentionCleaner;
import io.github.bamdule.dawn.messaging.RetentionRules;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;

class FulfillmentRetentionTest extends FulfillmentIntegrationTest {

    // 보존 기간(90일)보다 훨씬 오래된 회차다. 다른 테스트의 회차는 모두 미래라 지워지지 않는다
    private static final LocalDateTime OLD_CUTOFF = LocalDateTime.of(2020, 1, 1, 23, 0);

    @Autowired
    private RetentionRules fulfillmentRetentionRules;

    @Autowired
    private RetentionCleaner retentionCleaner;

    @Autowired
    private TargetFixer targetFixer;

    @Autowired
    private AllocationRunner allocationRunner;

    @Test
    void 구십_일_지난_회차의_기록과_배치_메타를_지우되_열린_예외와_이월될_수량은_남긴다() throws Exception {
        JobExecution recentExecution = run(newCutoff());
        long shippedProduct = newProductId();
        long exceptionProduct = newProductId();
        stock(SEOUL_A, shippedProduct, 5);
        stock(SEOUL_B, exceptionProduct, 1);
        String shipped = paid(OLD_CUTOFF, OLD_CUTOFF.minusHours(2), "SEOUL", line(shippedProduct, 1));
        String excepted = paid(OLD_CUTOFF, OLD_CUTOFF.minusHours(1), "SEOUL", line(exceptionProduct, 1));
        targetFixer.fix(OLD_CUTOFF);
        allocationRunner.allocate(OLD_CUTOFF);
        stock(SEOUL_B, exceptionProduct, 0);
        JobExecution jobExecution = run(OLD_CUTOFF);
        jdbcClient.sql("UPDATE BATCH_JOB_EXECUTION SET END_TIME = :endTime WHERE JOB_EXECUTION_ID = :id")
                .param("endTime", OLD_CUTOFF.plusHours(2)).param("id", jobExecution.getId()).update();

        fulfillmentRetentionRules.rules().forEach(retentionCleaner::clean);

        assertThat(count("allocation_result")).isZero();
        assertThat(count("allocation_input")).isZero();
        assertThat(count("shipment_order")).isZero();
        assertThat(targetCount(shipped)).as("모든 수량이 끝난 출고 대상은 지운다").isZero();
        assertThat(targetCount(excepted)).as("예외 수량이 남은 출고 대상은 이월될 수 있어 남긴다").isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT status FROM shipment_exception WHERE cutoff_at = :cutoffAt")
                .param("cutoffAt", OLD_CUTOFF).query(String.class).list()).as("열린 출고 예외는 남긴다").containsExactly("OPEN");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", jobExecution.getId()).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", jobExecution.getId()).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :id")
                .param("id", jobExecution.getJobInstance().getInstanceId()).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", recentExecution.getId()).query(Integer.class).single()).as("최근 회차의 실행은 남는다").isEqualTo(1);
    }

    private int count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table + " WHERE cutoff_at = :cutoffAt")
                .param("cutoffAt", OLD_CUTOFF).query(Integer.class).single();
    }

    private int targetCount(String orderNo) {
        return jdbcClient.sql("SELECT COUNT(*) FROM shipment_target WHERE order_no = :orderNo")
                .param("orderNo", orderNo).query(Integer.class).single();
    }
}
