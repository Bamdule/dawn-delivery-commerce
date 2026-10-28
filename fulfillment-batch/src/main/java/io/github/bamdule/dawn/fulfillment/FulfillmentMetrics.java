package io.github.bamdule.dawn.fulfillment;

import io.github.bamdule.dawn.fulfillment.run.CutoffRunStatus;
import io.github.bamdule.dawn.messaging.DbGauges;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// 출고 배치의 진행과 결과를 낸다. 시각에 따른 경보(23:15 미시작, 00:30 ALLOCATED 아님 등)는 경보 규칙이
// 마지막 회차의 마감 시각과 단계를 보고 정한다 (docs/design/01 "운영 지표", infra/prometheus/alerts.yml)
@Component
public class FulfillmentMetrics {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int REGION_MISMATCH_DAYS = 3;

    public FulfillmentMetrics(DbGauges dbGauges, JdbcClient jdbcClient) {
        dbGauges.register("dawn.fulfillment.run.latest.cutoff", "가장 최근에 시작한 마감 회차의 마감 시각(유닉스 초). 없으면 0", "seconds",
                () -> latestRun(jdbcClient).map(run -> run.cutoffAt().atZone(SEOUL).toEpochSecond()).orElse(0L));
        dbGauges.register("dawn.fulfillment.run.latest.stage",
                "가장 최근 회차의 단계. 0 STARTED, 1 TARGET_FIXED, 2 ALLOCATED, 3 UNFILLABLE_PUBLISHED, 4 SHIPMENT_CREATED, 5 VERIFIED, 6 VERIFY_FAILED. 없으면 -1",
                "stage", () -> latestRun(jdbcClient).map(run -> run.status().ordinal()).orElse(-1));
        dbGauges.register("dawn.fulfillment.run.verify.failed", "사람이 승인하기 전까지 배차로 넘기지 않는 검증 실패 회차", "runs",
                () -> count(jdbcClient, "SELECT COUNT(*) FROM cutoff_run WHERE status = 'VERIFY_FAILED'"));
        dbGauges.register("dawn.fulfillment.exception.open", "사람이 이월하거나 취소해야 하는 출고 예외", "exceptions",
                () -> count(jdbcClient, "SELECT COUNT(*) FROM shipment_exception WHERE status = 'OPEN'"));
        dbGauges.register("dawn.fulfillment.unfilled.ratio", "가장 최근에 검증까지 끝난 회차의 못 채운 수량 비율. 0.5% 넘으면 경보", "ratio",
                () -> jdbcClient.sql("""
                        SELECT COALESCE((SELECT SUM(d.quantity) FROM unfillable_decision d WHERE d.cutoff_at = r.cutoff_at), 0)
                               / NULLIF(r.input_quantity, 0)
                          FROM cutoff_run r WHERE r.status IN ('VERIFIED', 'VERIFY_FAILED')
                         ORDER BY r.cutoff_at DESC LIMIT 1
                        """).query(Double.class).optional().orElse(0.0));
        dbGauges.register("dawn.fulfillment.region.mismatch.repeated",
                "최근 세 회차 연속 권역 불일치로 못 채운 (상품, 권역). 판매 제한 권역이나 재고 이동이 필요", "pairs",
                () -> jdbcClient.sql("""
                        SELECT COUNT(*) FROM (
                            SELECT d.product_id, i.region_code FROM unfillable_decision d
                              JOIN allocation_input i ON i.cutoff_at = d.cutoff_at AND i.order_no = d.order_no AND i.product_id = d.product_id
                             WHERE d.reason = 'REGION_MISMATCH'
                               AND d.cutoff_at IN (SELECT cutoff_at FROM (SELECT cutoff_at FROM cutoff_run ORDER BY cutoff_at DESC LIMIT :days) recent)
                             GROUP BY d.product_id, i.region_code
                            HAVING COUNT(DISTINCT d.cutoff_at) = :days) repeated
                        """).param("days", REGION_MISMATCH_DAYS).query(Long.class).single());
    }

    private static Optional<LatestRun> latestRun(JdbcClient jdbcClient) {
        return jdbcClient.sql("SELECT cutoff_at, status FROM cutoff_run ORDER BY cutoff_at DESC LIMIT 1").query(LatestRun.class).optional();
    }

    private static long count(JdbcClient jdbcClient, String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private record LatestRun(LocalDateTime cutoffAt, CutoffRunStatus status) {
    }
}
