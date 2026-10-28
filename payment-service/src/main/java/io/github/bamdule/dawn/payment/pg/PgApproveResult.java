package io.github.bamdule.dawn.payment.pg;

// 대행사 호출 결과는 성공, 실패, 모름에 "닿지 않음"을 더한 네 가지다. 모름을 실패로 단정하지 않는다 (docs/design/04).
// 닿지 않음은 연결조차 못 해 요청이 대행사에 가지 않은 것이라 확실한 실패지만, 회로 차단기는 대행사 장애로 센다
public record PgApproveResult(Outcome outcome, String code) {

    public enum Outcome {
        APPROVED,
        DECLINED,
        UNKNOWN,
        NOT_SENT
    }

    public static PgApproveResult approved() {
        return new PgApproveResult(Outcome.APPROVED, null);
    }

    public static PgApproveResult declined(String code) {
        return new PgApproveResult(Outcome.DECLINED, code);
    }

    public static PgApproveResult notSent() {
        return new PgApproveResult(Outcome.NOT_SENT, null);
    }

    public boolean countsAsPgFailure() {
        return outcome == Outcome.UNKNOWN || outcome == Outcome.NOT_SENT;
    }

    public static PgApproveResult unknown(String code) {
        return new PgApproveResult(Outcome.UNKNOWN, code);
    }
}
