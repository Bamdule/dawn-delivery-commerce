package io.github.bamdule.dawn.payment.round;

import io.github.bamdule.dawn.payment.guard.PgCallGuard;
import io.github.bamdule.dawn.payment.pg.PgApproveResult;
import io.github.bamdule.dawn.payment.pg.PgClient;
import io.github.bamdule.dawn.payment.round.PaymentRounds.OpenResult;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

// 사용자가 기다리는 동안 대행사는 한 번만 부른다. 백오프 재시도는 조회 배치가 한다 (docs/design/04 "동기 경로에서는 한 번만")
@Service
public class PaymentApproval {

    static final String NOT_CALLED = "NOT_CALLED";
    private static final long FIRST_INQUIRY_DELAY_SECONDS = 10;

    private final PaymentRounds paymentRounds;
    private final PgClient pgClient;
    private final PgCallGuard pgCallGuard;
    private final PaymentRepository paymentRepository;

    public PaymentApproval(PaymentRounds paymentRounds, PgClient pgClient, PgCallGuard pgCallGuard,
                           PaymentRepository paymentRepository) {
        this.paymentRounds = paymentRounds;
        this.pgClient = pgClient;
        this.pgCallGuard = pgCallGuard;
        this.paymentRepository = paymentRepository;
    }

    // 트랜잭션 밖에서 부른다. 회차 열기와 결과 기록만 짧은 트랜잭션이라 대행사를 기다리는 동안 DB 커넥션을 잡지 않는다
    public ApproveResult approve(ApproveCommand approveCommand) {
        OpenResult openResult = paymentRounds.open(approveCommand.orderNo(), approveCommand.paymentRound(), approveCommand.amount());
        if (openResult.outcome() != OpenOutcome.OPENED) {
            return ApproveResult.notOpened(openResult);
        }
        PaymentRow paymentRow = openResult.paymentRow();
        if (!pgCallGuard.tryEnter()) {
            paymentRounds.recordFailed(paymentRow, NOT_CALLED);
            return new ApproveResult(ApproveOutcome.DECLINED, paymentRow.paymentRound(), NOT_CALLED);
        }
        Instant startedAt = Instant.now();
        PgApproveResult pgApproveResult = PgApproveResult.unknown(PgClient.UNKNOWN_TIMEOUT);
        try {
            pgApproveResult = pgClient.approve(paymentRow.idempotencyKey(), paymentRow.amount(), approveCommand.method());
        } finally {
            pgCallGuard.exit(pgApproveResult.countsAsPgFailure(), Duration.between(startedAt, Instant.now()));
        }
        return switch (pgApproveResult.outcome()) {
            case APPROVED -> {
                paymentRounds.recordApproved(paymentRow);
                yield new ApproveResult(ApproveOutcome.APPROVED, paymentRow.paymentRound(), null);
            }
            case DECLINED -> {
                paymentRounds.recordFailed(paymentRow, pgApproveResult.code());
                yield new ApproveResult(ApproveOutcome.DECLINED, paymentRow.paymentRound(), pgApproveResult.code());
            }
            // 요청이 가지 않았으니 회차를 실패로 닫는다. 주문은 "결제 지연"으로 답하고 예약 유지 시간 안에 다시 결제를 받는다
            case NOT_SENT -> {
                paymentRounds.recordFailed(paymentRow, NOT_CALLED);
                yield new ApproveResult(ApproveOutcome.DECLINED, paymentRow.paymentRound(), NOT_CALLED);
            }
            case UNKNOWN -> {
                paymentRepository.recordUnknownAttempt(paymentRow.id(), pgApproveResult.code(), FIRST_INQUIRY_DELAY_SECONDS);
                yield new ApproveResult(ApproveOutcome.UNKNOWN, paymentRow.paymentRound(), pgApproveResult.code());
            }
        };
    }

    public record ApproveCommand(String orderNo, int paymentRound, long amount, String method) {
    }

    public enum ApproveOutcome {
        APPROVED,
        DECLINED,
        UNKNOWN,
        IN_PROGRESS,
        ALREADY_PAID,
        SEALED,
        STALE
    }

    public record ApproveResult(ApproveOutcome outcome, int paymentRound, String failureCode) {

        static ApproveResult notOpened(OpenResult openResult) {
            PaymentRow paymentRow = openResult.paymentRow();
            ApproveOutcome outcome = switch (openResult.outcome()) {
                case EXISTING -> switch (paymentRow.status()) {
                    case APPROVED -> ApproveOutcome.APPROVED;
                    case FAILED -> ApproveOutcome.DECLINED;
                    case EXPIRED -> ApproveOutcome.SEALED;
                    case REQUESTED, UNKNOWN -> ApproveOutcome.UNKNOWN;
                };
                case SEALED -> ApproveOutcome.SEALED;
                case IN_PROGRESS -> ApproveOutcome.IN_PROGRESS;
                case ALREADY_PAID -> ApproveOutcome.ALREADY_PAID;
                case STALE -> ApproveOutcome.STALE;
                case OPENED -> throw new IllegalStateException("연 회차는 여기로 오지 않습니다.");
            };
            return new ApproveResult(outcome, paymentRow.paymentRound(), paymentRow.failureCode());
        }
    }
}
