package io.github.bamdule.dawn.inventory;

import io.github.bamdule.dawn.event.Topics;
import io.github.bamdule.dawn.messaging.DeadLetterTopics;
import io.github.bamdule.dawn.messaging.RetentionRule;
import io.github.bamdule.dawn.messaging.RetentionRules;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

// 재고 서비스가 가진 표의 보존 기간 (docs/design/03 "보존과 정리"). 자식 표인 슬롯 내역을 예약보다 먼저 지운다
@Configuration
public class InventoryRetention {

    private static final Duration INBOX = Duration.ofDays(30);
    private static final Duration FINISHED_RESERVATION = Duration.ofDays(90);
    private static final Duration AUDIT = Duration.ofDays(365);
    private static final String FINISHED = "status IN ('CONFIRMED', 'RELEASED', 'RESTORED') AND updated_at < :before";

    @Bean
    public RetentionRules inventoryRetentionRules() {
        return new RetentionRules(List.of(
                new RetentionRule("inbox", "inbox_event", "processed_at < :before", INBOX),
                new RetentionRule("reservation-slot", "reservation_slot",
                        "reservation_id IN (SELECT reservation_id FROM stock_reservation WHERE " + FINISHED + ")", FINISHED_RESERVATION),
                new RetentionRule("finished-reservation", "stock_reservation", FINISHED, FINISHED_RESERVATION),
                new RetentionRule("stock-adjustment-log", "stock_adjustment_log", "created_at < :before", AUDIT),
                new RetentionRule("closed-reconciliation", "stock_reconciliation",
                        "status IN ('RESOLVED', 'CORRECTED') AND first_seen_at < :before", AUDIT)));
    }

    @Bean
    public KafkaAdmin.NewTopics inventoryDeadLetterTopics() {
        return DeadLetterTopics.forSources(Topics.ORDER_EVENTS, Topics.FULFILLMENT_EVENTS);
    }
}
