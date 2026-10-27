package io.github.bamdule.dawn.fulfillment.target;

public class TargetFixMismatchException extends RuntimeException {

    public TargetFixMismatchException(int listed, int fixed) {
        super("결제 완료 목록과 출고 대상 수가 다릅니다. (listed=" + listed + ", fixed=" + fixed + ")");
    }
}
