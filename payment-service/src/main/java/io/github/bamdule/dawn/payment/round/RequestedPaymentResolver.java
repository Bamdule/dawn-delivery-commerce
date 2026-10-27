package io.github.bamdule.dawn.payment.round;

import io.github.bamdule.dawn.payment.guard.PgCallGuard;
import io.github.bamdule.dawn.payment.pg.PgApproveResult;
import io.github.bamdule.dawn.payment.pg.PgClient;
import io.github.bamdule.dawn.payment.round.PaymentRepository.RequestedPayment;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 결과를 모르는 회차를 멱등키로 대행사에 묻고 확정한다 (docs/design/04 "결제 서비스: 조회 배치")
@Component
public class RequestedPaymentResolver {

    private static final Logger log = LoggerFactory.getLogger(RequestedPaymentResolver.class);
    // 대행사가 1분 멈추면 12,000건이 쌓인다. 회복 중인 대행사를 다시 누르지 않게 한 주기에 1,000건만 묻는다
    static final int BATCH_LIMIT = 1_000;
    static final int MAX_RESENDS = 5;
    static final String NOT_FOUND_AT_PG = "NOT_FOUND_AT_PG";

    private final PaymentRepository paymentRepository;
    private final PaymentRounds paymentRounds;
    private final PgClient pgClient;
    private final PgCallGuard pgCallGuard;

    public RequestedPaymentResolver(PaymentRepository paymentRepository, PaymentRounds paymentRounds,
                                    PgClient pgClient, PgCallGuard pgCallGuard) {
        this.paymentRepository = paymentRepository;
        this.paymentRounds = paymentRounds;
        this.pgClient = pgClient;
        this.pgCallGuard = pgCallGuard;
    }

    @Scheduled(fixedDelayString = "${dawn.payment.inquiry-interval:10s}")
    public void run() {
        if (pgCallGuard.isCircuitOpen()) {
            return;
        }
        for (RequestedPayment requestedPayment : paymentRepository.findRequestedDue(BATCH_LIMIT)) {
            if (!resolve(requestedPayment)) {
                return;
            }
        }
    }

    // 대행사에 묻지 못했으면 false 를 돌려 이번 주기를 멈춘다
    private boolean resolve(RequestedPayment requestedPayment) {
        PaymentRow paymentRow = paymentRepository.findByIdempotencyKey(requestedPayment.idempotencyKey()).orElseThrow();
        String status;
        if (!pgCallGuard.tryEnter()) {
            return false;
        }
        Instant startedAt = Instant.now();
        boolean failed = true;
        try {
            status = pgClient.inquire(requestedPayment.idempotencyKey());
            failed = false;
        } catch (RuntimeException exception) {
            log.warn("결제 결과 조회 실패 (idempotencyKey={})", requestedPayment.idempotencyKey());
            return false;
        } finally {
            pgCallGuard.exit(failed, Duration.between(startedAt, Instant.now()));
        }
        switch (status) {
            case "APPROVED" -> paymentRounds.recordApproved(paymentRow);
            case "DECLINED" -> paymentRounds.recordFailed(paymentRow, "DECLINED");
            default -> handleNotFound(requestedPayment, paymentRow);
        }
        return true;
    }

    // "요청 없음"은 마지막 전송에서 60초가 지난 뒤에만 믿는다. 다시 보낸 요청이 아직 처리 중일 수 있다
    private void handleNotFound(RequestedPayment requestedPayment, PaymentRow paymentRow) {
        if (requestedPayment.notFoundTrustworthy()) {
            paymentRounds.recordFailed(paymentRow, NOT_FOUND_AT_PG);
            return;
        }
        if (PgClient.UNKNOWN_SERVER_ERROR.equals(requestedPayment.failureCode()) && pgClient.idempotencySupported()
                && requestedPayment.attemptCount() <= MAX_RESENDS) {
            resend(requestedPayment, paymentRow);
            return;
        }
        if (requestedPayment.overdue()) {
            paymentRounds.markUnknown(paymentRow);
            log.error("결제 결과 미확정 (orderNo={}, paymentRound={})", requestedPayment.orderNo(), requestedPayment.paymentRound());
        }
    }

    // 멱등키를 지원하는 대행사면 5xx 건을 같은 키로 다시 보낸다. 1초부터 두 배씩, 지터를 더한다
    private void resend(RequestedPayment requestedPayment, PaymentRow paymentRow) {
        long backoffSeconds = (1L << Math.min(requestedPayment.attemptCount() - 1, 6))
                + ThreadLocalRandom.current().nextLong(2);
        paymentRepository.recordResend(paymentRow.id(), backoffSeconds);
        if (!pgCallGuard.tryEnter()) {
            return;
        }
        Instant startedAt = Instant.now();
        PgApproveResult pgApproveResult = PgApproveResult.unknown(PgClient.UNKNOWN_TIMEOUT);
        try {
            pgApproveResult = pgClient.approve(paymentRow.idempotencyKey(), paymentRow.amount(), "RESEND");
        } finally {
            pgCallGuard.exit(pgApproveResult.outcome() == PgApproveResult.Outcome.UNKNOWN,
                    Duration.between(startedAt, Instant.now()));
        }
        if (pgApproveResult.outcome() == PgApproveResult.Outcome.APPROVED) {
            paymentRounds.recordApproved(paymentRow);
        } else if (pgApproveResult.outcome() == PgApproveResult.Outcome.DECLINED) {
            paymentRounds.recordFailed(paymentRow, pgApproveResult.code());
        }
    }
}
