package io.github.bamdule.dawn.payment;

import io.github.bamdule.dawn.messaging.RetentionRule;
import io.github.bamdule.dawn.messaging.RetentionRules;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 결제 회차와 환불은 돈의 기록이라 지우지 않는다. 정산 대사의 어긋남만 회계 감사 기간인 1년 둔다 (docs/design/03 "보존과 정리")
@Configuration
public class PaymentRetention {

    @Bean
    public RetentionRules paymentRetentionRules() {
        return new RetentionRules(List.of(
                new RetentionRule("settlement-mismatch", "settlement_mismatch", "created_at < :before", Duration.ofDays(365))));
    }
}
