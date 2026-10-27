package io.github.bamdule.dawn.fulfillment.run;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CutoffRunRepository {

    private final JdbcClient jdbcClient;

    public CutoffRunRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void startIfAbsent(LocalDateTime cutoffAt) {
        jdbcClient.sql("INSERT IGNORE INTO cutoff_run (cutoff_at, status, updated_at) VALUES (:cutoffAt, 'STARTED', NOW(3))")
                .param("cutoffAt", cutoffAt)
                .update();
    }

    public Optional<CutoffRunStatus> findStatus(LocalDateTime cutoffAt) {
        return jdbcClient.sql("SELECT status FROM cutoff_run WHERE cutoff_at = :cutoffAt")
                .param("cutoffAt", cutoffAt)
                .query(CutoffRunStatus.class)
                .optional();
    }

    public CutoffRunStatus status(LocalDateTime cutoffAt) {
        return findStatus(cutoffAt).orElseThrow(() -> new IllegalStateException("마감 회차가 없습니다. (cutoffAt=" + cutoffAt + ")"));
    }

    public boolean transition(LocalDateTime cutoffAt, CutoffRunStatus from, CutoffRunStatus to) {
        return jdbcClient.sql("UPDATE cutoff_run SET status = :to, updated_at = NOW(3) WHERE cutoff_at = :cutoffAt AND status = :from")
                .param("cutoffAt", cutoffAt)
                .param("from", from.name())
                .param("to", to.name())
                .update() == 1;
    }

    public void markTargetFixed(LocalDateTime cutoffAt, int targetCount, int inputQuantity) {
        jdbcClient.sql("""
                UPDATE cutoff_run
                   SET status = 'TARGET_FIXED', target_count = :targetCount, input_quantity = :inputQuantity, updated_at = NOW(3)
                 WHERE cutoff_at = :cutoffAt AND status = 'STARTED'
                """)
                .param("cutoffAt", cutoffAt)
                .param("targetCount", targetCount)
                .param("inputQuantity", inputQuantity)
                .update();
    }

    public int inputQuantity(LocalDateTime cutoffAt) {
        return jdbcClient.sql("SELECT input_quantity FROM cutoff_run WHERE cutoff_at = :cutoffAt")
                .param("cutoffAt", cutoffAt)
                .query(Integer.class)
                .single();
    }
}
