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
        for (DueRefund dueRefund : refundRepository.findRequested(BATCH_LIMIT)) {
            if (!pgCallGuard.tryEnter()) {
                return;
            }
            Instant startedAt = Instant.now();
            String result = "UNKNOWN";
            try {
                result = pgClient.refund(dueRefund.refundKey(), dueRefund.paymentKey(), dueRefund.amount());
            } finally {
                pgCallGuard.exit("UNKNOWN".equals(result), Duration.between(startedAt, Instant.now()));
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
}
