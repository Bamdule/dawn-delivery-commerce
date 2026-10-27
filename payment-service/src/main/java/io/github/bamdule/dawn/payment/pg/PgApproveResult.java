package io.github.bamdule.dawn.payment.pg;

// 대행사 호출 결과는 성공, 실패, 모름 세 가지다. 모름을 실패로 단정하지 않는다 (docs/design/04)
public record PgApproveResult(Outcome outcome, String code) {

    public enum Outcome {
        APPROVED,
        DECLINED,
        UNKNOWN
    }

    public static PgApproveResult approved() {
        return new PgApproveResult(Outcome.APPROVED, null);
    }

    public static PgApproveResult declined(String code) {
        return new PgApproveResult(Outcome.DECLINED, code);
    }

    public static PgApproveResult unknown(String code) {
        return new PgApproveResult(Outcome.UNKNOWN, code);
    }
}
