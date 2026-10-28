package io.github.bamdule.dawn.payment.refund;

import io.github.bamdule.dawn.payment.guard.PgCallGuard;
import io.github.bamdule.dawn.payment.pg.PgClient;
import io.github.bamdule.dawn.payment.refund.RefundRepository.DueRefund;
import io.github.bamdule.dawn.payment.refund.RefundRepository.RefundRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 접수한 환불을 같은 환불 키로 대행사에 보내 확정한다. 대행사가 키로 멱등이라 다시 보내도 한 번이다
@Component
public class RefundJob {

    private static final Logger log = LoggerFactory.getLogger(RefundJob.class);
    private static final int BATCH_LIMIT = 1_000;
    // 결과를 모르는 환불을 10초마다 다시 보내면 한 건이 한 시간에 360번 나간다. 30초에 한 번으로 둔다
    private static final int RESEND_INTERVAL_SECONDS = 30;

    private final RefundRepository refundRepository;
    private final Refunds refunds;
    private final PgClient pgClient;
    private final PgCallGuard pgCallGuard;

    public RefundJob(RefundRepository refundRepository, Refunds refunds, PgClient pgClient, PgCallGuard pgCallGuard) {
        this.refundRepository = refundRepository;
        this.refunds = refunds;
        this.pgClient = pgClient;
        this.pgCallGuard = pgCallGuard;
    }

    @Scheduled(fixedDelayString = "${dawn.payment.refund-interval:10s}")
    public void run() {
        for (DueRefund dueRefund : refundRepository.findRequested(BATCH_LIMIT, RESEND_INTERVAL_SECONDS)) {
            if (!pgCallGuard.tryEnter()) {
                return;
            }
            try {
                send(dueRefund);
            } catch (RuntimeException exception) {
                // 한 건의 예상 못 한 오류가 나머지 환불을 막지 않게 그 건만 건너뛴다
                log.error("환불 처리 오류 (refundKey={})", dueRefund.refundKey(), exception);
            }
        }
    }

    // 같은 키로 다시 보내는 것이 곧 조회다. 대행사는 이미 처리한 환불이면 처음 결과를 돌려준다
    private void send(DueRefund dueRefund) {
        Instant startedAt = Instant.now();
        String result = "UNKNOWN";
        try {
            result = pgClient.refund(dueRefund.refundKey(), dueRefund.paymentKey(), dueRefund.amount());
        } finally {
            pgCallGuard.exit("UNKNOWN".equals(result), Duration.between(startedAt, Instant.now()));
            refundRepository.recordSent(dueRefund.id());
        }
        RefundRow refundRow = refundRepository.findByKey(dueRefund.refundKey()).orElseThrow();
        switch (result) {
            case "COMPLETED" -> refunds.complete(refundRow, List.of("REQUESTED"), false);
            case "DECLINED" -> {
                refunds.fail(refundRow, List.of("REQUESTED"), false);
                log.error("대행사 환불 거절 (refundKey={})", dueRefund.refundKey());
            }
            default -> {
                if (dueRefund.overdue()) {
                    refunds.markUnknown(refundRow);
                    log.error("환불 결과 미확정 (refundKey={})", dueRefund.refundKey());
                }
            }
        }
    }
}
