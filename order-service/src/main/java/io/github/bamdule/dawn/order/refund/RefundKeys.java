package io.github.bamdule.dawn.order.refund;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// 환불 키는 사유에서 만든다. "기존 환불 건수 + 1" 처럼 세면 재시작 때 같은 환불에 새 번호가 붙어 두 번 환불된다 (docs/design/04)
public final class RefundKeys {

    private static final DateTimeFormatter CUTOFF_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private RefundKeys() {
    }

    public static String customerCancel(String orderNo) {
        return "refund-" + orderNo + "-cancel";
    }

    public static String unfillable(String orderNo, LocalDateTime cutoffAt) {
        return "refund-" + orderNo + "-cutoff-" + CUTOFF_FORMAT.format(cutoffAt);
    }

    public static String adminCancel(String orderNo, long exceptionNo) {
        return "refund-" + orderNo + "-admin-" + exceptionNo;
    }

    public static String orphan(String orderNo, int paymentRound) {
        return "refund-" + orderNo + "-orphan-" + paymentRound;
    }

    public static String reissue(String orderNo, long originalRequestId) {
        return "refund-" + orderNo + "-reissue-" + originalRequestId;
    }
}
