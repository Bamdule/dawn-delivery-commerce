package io.github.bamdule.dawn.fulfillment;

import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.messaging.DeadLetterTopics;
import io.github.bamdule.dawn.messaging.RetentionRule;
import io.github.bamdule.dawn.messaging.RetentionRules;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

// 출고 기록은 회차 조사와 반품 처리 근거로 90일, 조정 이력은 재고 감사 근거로 1년 둔다 (docs/design/03 "보존과 정리").
// 규칙은 자식 표를 부모 표보다 먼저 둔다. 열린 출고 예외와 처리가 끝나지 않은 출고 대상은 기간이 지나도 지우지 않는다
@Configuration
public class FulfillmentRetention {

    private static final Duration SHIPMENT = Duration.ofDays(90);
    private static final Duration INBOX = Duration.ofDays(30);
    private static final Duration AUDIT = Duration.ofDays(365);
    private static final String OLD_JOB_EXECUTIONS = "SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE END_TIME < :before";

    @Bean
    public RetentionRules fulfillmentRetentionRules() {
        return new RetentionRules(List.of(
                new RetentionRule("inbox", "inbox_event", "processed_at < :before", INBOX),
                new RetentionRule("allocation-input", "allocation_input", "cutoff_at < :before", SHIPMENT),
                new RetentionRule("allocation-result", "allocation_result", "cutoff_at < :before", SHIPMENT),
                new RetentionRule("allocation-snapshot", "allocation_stock_snapshot", "cutoff_at < :before", SHIPMENT),
                new RetentionRule("unfillable-decision", "unfillable_decision", "cutoff_at < :before", SHIPMENT),
                new RetentionRule("target-fix-diff", "target_fix_diff", "cutoff_at < :before", SHIPMENT),
                new RetentionRule("shipment-order-line", "shipment_order_line",
                        "shipment_order_id IN (SELECT id FROM shipment_order WHERE cutoff_at < :before)", SHIPMENT),
                new RetentionRule("shipment-order", "shipment_order", "cutoff_at < :before", SHIPMENT),
                new RetentionRule("shipment-exception-line", "shipment_exception_line",
                        "shipment_exception_id IN (SELECT id FROM shipment_exception WHERE cutoff_at < :before AND status <> 'OPEN')", SHIPMENT),
                new RetentionRule("shipment-exception", "shipment_exception", "cutoff_at < :before AND status <> 'OPEN'", SHIPMENT),
                // 대기나 예외 수량이 남은 주문은 이월될 수 있어 남긴다. 같은 표를 하위 쿼리에서 읽으려고 한 번 감싼다
                new RetentionRule("shipment-target-line", "shipment_target_line", """
                        order_no IN (SELECT order_no FROM (
                            SELECT t.order_no FROM shipment_target t
                             WHERE COALESCE(t.paid_at, t.updated_at) < :before
                               AND NOT EXISTS (SELECT 1 FROM shipment_target_line l
                                                WHERE l.order_no = t.order_no AND (l.waiting_qty > 0 OR l.exception_qty > 0))) settled)
                        """, SHIPMENT),
                new RetentionRule("shipment-target", "shipment_target", """
                        COALESCE(paid_at, updated_at) < :before
                        AND NOT EXISTS (SELECT 1 FROM shipment_target_line l WHERE l.order_no = shipment_target.order_no)
                        """, SHIPMENT),
                // 빠진 회차 판단은 가장 최근 회차만 보므로 끝난 회차는 90일이면 충분하다
                new RetentionRule("cutoff-run", "cutoff_run", "cutoff_at < :before AND status IN ('VERIFIED', 'VERIFY_FAILED')", SHIPMENT),
                new RetentionRule("stock-adjustment", "stock_adjustment", "created_at < :before", AUDIT),
                // Spring Batch 메타는 재시작 근거로 90일. 끝난 실행만 지우고, 실행이 남지 않은 잡 인스턴스를 마지막에 지운다
                new RetentionRule("batch-step-context", "BATCH_STEP_EXECUTION_CONTEXT",
                        "STEP_EXECUTION_ID IN (SELECT STEP_EXECUTION_ID FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID IN (" + OLD_JOB_EXECUTIONS + "))", SHIPMENT),
                new RetentionRule("batch-step-execution", "BATCH_STEP_EXECUTION", "JOB_EXECUTION_ID IN (" + OLD_JOB_EXECUTIONS + ")", SHIPMENT),
                new RetentionRule("batch-job-context", "BATCH_JOB_EXECUTION_CONTEXT", "JOB_EXECUTION_ID IN (" + OLD_JOB_EXECUTIONS + ")", SHIPMENT),
                new RetentionRule("batch-job-params", "BATCH_JOB_EXECUTION_PARAMS", "JOB_EXECUTION_ID IN (" + OLD_JOB_EXECUTIONS + ")", SHIPMENT),
                new RetentionRule("batch-job-execution", "BATCH_JOB_EXECUTION", "END_TIME < :before", SHIPMENT),
                // 잡 인스턴스와 첫 실행은 한 트랜잭션에 생겨서, 실행이 없는 인스턴스는 앞 규칙이 실행을 지운 것뿐이다
                new RetentionRule("batch-job-instance", "BATCH_JOB_INSTANCE",
                        "NOT EXISTS (SELECT 1 FROM BATCH_JOB_EXECUTION e WHERE e.JOB_INSTANCE_ID = BATCH_JOB_INSTANCE.JOB_INSTANCE_ID)", SHIPMENT)));
    }

    @Bean
    public KafkaAdmin.NewTopics fulfillmentDeadLetterTopics() {
        return DeadLetterTopics.forSources(Topics.ORDER_EVENTS);
    }
}
