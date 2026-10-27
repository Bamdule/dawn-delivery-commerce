package io.github.bamdule.dawn.order.cancel;

import java.time.LocalDateTime;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 주문이 속한 마감 회차는 PAID 커밋 시각(paid_at)으로 정한다. 마감 시각과 같으면 그 회차다 (docs/design/05 "회차")
@Component
public class CutoffPolicy {

    private final LocalTime cutoffTime;

    public CutoffPolicy(@Value("${dawn.order.cutoff-time:23:00}") LocalTime cutoffTime) {
        this.cutoffTime = cutoffTime;
    }

    public LocalDateTime cutoffOf(LocalDateTime paidAt) {
        LocalDateTime sameDayCutoff = paidAt.toLocalDate().atTime(cutoffTime);
        return paidAt.isAfter(sameDayCutoff) ? sameDayCutoff.plusDays(1) : sameDayCutoff;
    }
}
