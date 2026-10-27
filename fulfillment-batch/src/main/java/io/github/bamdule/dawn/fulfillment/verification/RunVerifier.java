package io.github.bamdule.dawn.fulfillment.verification;

import io.github.bamdule.dawn.fulfillment.run.CutoffRunRepository;
import io.github.bamdule.dawn.fulfillment.run.CutoffRunStatus;
import io.github.bamdule.dawn.fulfillment.run.StepPreconditionException;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

// 배치 중에 사람이 예외를 이월하거나 취소해도 바뀌지 않는 기록으로만, 수량으로 센다 (docs/design/05 "결과 검증").
// 넘으면 다시 돌려도 같은 판정이라 자동으로 재실행하지 않고 사람이 승인한다
@Service
public class RunVerifier {

    private static final Logger log = LoggerFactory.getLogger(RunVerifier.class);
    // 출고 예외 수량이 할당 입력의 0.1% 를 넘으면 데이터 문제로 본다
    private static final int EXCEPTION_RATIO_PER_MILLE = 1;

    private final CutoffRunRepository cutoffRunRepository;
    private final JdbcClient jdbcClient;

    public RunVerifier(CutoffRunRepository cutoffRunRepository, JdbcClient jdbcClient) {
        this.cutoffRunRepository = cutoffRunRepository;
        this.jdbcClient = jdbcClient;
    }

    // 출고 지시 파티션이 모두 끝나야 이 스텝이 돈다. 그래서 여기서 SHIPMENT_CREATED 로 올린다
    public CutoffRunStatus verify(LocalDateTime cutoffAt) {
        cutoffRunRepository.transition(cutoffAt, CutoffRunStatus.UNFILLABLE_PUBLISHED, CutoffRunStatus.SHIPMENT_CREATED);
        CutoffRunStatus status = cutoffRunRepository.status(cutoffAt);
        if (status.finished()) {
            return status;
        }
        if (status != CutoffRunStatus.SHIPMENT_CREATED) {
            throw new StepPreconditionException("출고 지시 전에는 검증할 수 없습니다. (cutoffAt=" + cutoffAt + ")");
        }
        Totals totals = totals(cutoffAt);
        boolean passed = totals.input() == totals.allocated() + totals.unfilled()
                && totals.allocated() == totals.shipped() + totals.excepted()
                && totals.excepted() * 1_000L <= (long) totals.input() * EXCEPTION_RATIO_PER_MILLE;
        CutoffRunStatus result = passed ? CutoffRunStatus.VERIFIED : CutoffRunStatus.VERIFY_FAILED;
        cutoffRunRepository.transition(cutoffAt, CutoffRunStatus.SHIPMENT_CREATED, result);
        if (!passed) {
            log.error("출고 결과 검증 실패 (cutoffAt={}, totals={})", cutoffAt, totals);
        }
        return result;
    }

    Totals totals(LocalDateTime cutoffAt) {
        return jdbcClient.sql("""
                SELECT r.input_quantity AS input,
                       (SELECT COALESCE(SUM(quantity), 0) FROM allocation_result WHERE cutoff_at = :cutoffAt) AS allocated,
                       (SELECT COALESCE(SUM(quantity), 0) FROM unfillable_decision WHERE cutoff_at = :cutoffAt) AS unfilled,
                       (SELECT COALESCE(SUM(l.quantity), 0) FROM shipment_order o JOIN shipment_order_line l ON l.shipment_order_id = o.id
                         WHERE o.cutoff_at = :cutoffAt AND o.status <> 'CANCELLED') AS shipped,
                       (SELECT COALESCE(SUM(l.quantity), 0) FROM shipment_exception e JOIN shipment_exception_line l ON l.shipment_exception_id = e.id
                         WHERE e.cutoff_at = :cutoffAt) AS excepted
                  FROM cutoff_run r WHERE r.cutoff_at = :cutoffAt
                """)
                .param("cutoffAt", cutoffAt)
                .query(Totals.class)
                .single();
    }

    record Totals(int input, int allocated, int unfilled, int shipped, int excepted) {
    }
}
