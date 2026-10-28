package io.github.bamdule.dawn.payment.settlement;

import io.github.bamdule.dawn.payment.PaymentStatus;
import io.github.bamdule.dawn.payment.pg.PgClient;
import io.github.bamdule.dawn.payment.pg.PgClient.SettledPayment;
import io.github.bamdule.dawn.payment.pg.PgClient.SettledRefund;
import io.github.bamdule.dawn.payment.pg.PgClient.Settlement;
import io.github.bamdule.dawn.payment.refund.RefundRepository;
import io.github.bamdule.dawn.payment.refund.RefundRepository.RefundRow;
import io.github.bamdule.dawn.payment.refund.Refunds;
import io.github.bamdule.dawn.payment.round.PaymentRepository;
import io.github.bamdule.dawn.payment.round.PaymentRounds;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 하루 한 번 대행사 정산 파일과 맞춰 본다. 앞의 장치가 맞다면 어긋남은 0건이어야 하는 마지막 안전망이다 (docs/design/04)
@Component
public class SettlementReconciler {

    private static final Logger log = LoggerFactory.getLogger(SettlementReconciler.class);
    private static final Set<PaymentStatus> UNRESOLVED = EnumSet.of(PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN);

    private final PgClient pgClient;
    private final PaymentRepository paymentRepository;
    private final PaymentRounds paymentRounds;
    private final RefundRepository refundRepository;
    private final Refunds refunds;
    private final JdbcClient jdbcClient;

    public SettlementReconciler(PgClient pgClient, PaymentRepository paymentRepository, PaymentRounds paymentRounds,
                                RefundRepository refundRepository, Refunds refunds, JdbcClient jdbcClient) {
        this.pgClient = pgClient;
        this.paymentRepository = paymentRepository;
        this.paymentRounds = paymentRounds;
        this.refundRepository = refundRepository;
        this.refunds = refunds;
        this.jdbcClient = jdbcClient;
    }

    @Scheduled(cron = "${dawn.payment.settlement-cron:0 0 4 * * *}")
    public void reconcileYesterday() {
        reconcile(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));
    }

    public int reconcile(LocalDate date) {
        Settlement settlement = pgClient.settlement(date);
        int mismatches = 0;
        Map<String, SettledPayment> pgApprovals = settlement.approvals().stream()
                .collect(Collectors.toMap(SettledPayment::idempotencyKey, Function.identity()));
        for (SettledPayment settledPayment : settlement.approvals()) {
            var ours = paymentRepository.findByIdempotencyKey(settledPayment.idempotencyKey());
            if (ours.isPresent() && ours.get().status() == PaymentStatus.FAILED) {
                paymentRounds.correctToApproved(ours.get());
                mismatches += record(date, settledPayment.idempotencyKey(), "PG_APPROVED_OURS_FAILED", "APPROVED", "FAILED");
            } else if (ours.isPresent() && UNRESOLVED.contains(ours.get().status())) {
                // 사람이 확정할 몫이라 바꾸지 않는다. 정산 파일이 가장 강한 근거라 그 결과를 남겨 확정에 쓰게 한다
                mismatches += record(date, settledPayment.idempotencyKey(), "PG_APPROVED_OURS_UNRESOLVED", "APPROVED",
                        ours.get().status().name());
            }
        }
        for (String ourKey : approvedKeysOn(date)) {
            if (!pgApprovals.containsKey(ourKey)) {
                mismatches += record(date, ourKey, "OURS_APPROVED_PG_MISSING", null, "APPROVED");
            }
        }
        for (SettledRefund settledRefund : settlement.refunds()) {
            RefundRow ours = refundRepository.findByKey(settledRefund.refundKey()).orElse(null);
            if (ours == null || ours.status().equals(settledRefund.status())) {
                continue;
            }
            if (settledRefund.status().equals("COMPLETED")) {
                refunds.complete(ours, List.of("REQUESTED", "FAILED", "UNKNOWN"), true);
            } else if (ours.status().equals("COMPLETED")) {
                refunds.fail(ours, List.of("COMPLETED"), true);
            }
            mismatches += record(date, settledRefund.refundKey(), "REFUND_STATUS_DIFF", settledRefund.status(), ours.status());
        }
        if (mismatches > 0) {
            log.error("정산 대사 어긋남 (date={}, count={})", date, mismatches);
        }
        return mismatches;
    }

    private List<String> approvedKeysOn(LocalDate date) {
        return jdbcClient.sql("SELECT idempotency_key FROM payment WHERE status = 'APPROVED' AND DATE(approved_at) = :date")
                .param("date", date).query(String.class).list();
    }

    private int record(LocalDate date, String key, String kind, String pgStatus, String ourStatus) {
        return jdbcClient.sql("""
                INSERT IGNORE INTO settlement_mismatch (settlement_date, idempotency_key, kind, pg_status, our_status, resolution, created_at)
                VALUES (:date, :key, :kind, :pgStatus, :ourStatus, 'OPEN', NOW(3))
                """)
                .param("date", date).param("key", key).param("kind", kind)
                .param("pgStatus", pgStatus).param("ourStatus", ourStatus)
                .update();
    }
}
