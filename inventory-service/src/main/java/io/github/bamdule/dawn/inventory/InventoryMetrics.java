package io.github.bamdule.dawn.inventory;

import io.github.bamdule.dawn.inventory.reservation.ReservationExpiryJob;
import io.github.bamdule.dawn.messaging.DbGauges;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// 만료 배치가 멈추면 특가 재고가 묶여 판매를 놓친다. 배치가 돈 지 얼마나 됐는지와, 만료 5분 지난 HELD 를 센다.
// PAYING 은 결제 결과를 기다리는 정상 상태라 넣지 않는다 (docs/design/01 "운영 지표")
@Component
public class InventoryMetrics {

    public InventoryMetrics(DbGauges dbGauges, JdbcClient jdbcClient, ReservationExpiryJob reservationExpiryJob,
                            MeterRegistry meterRegistry) {
        Gauge.builder("dawn.inventory.expiry.idle", reservationExpiryJob,
                        job -> Duration.between(job.lastCompletedAt(), Instant.now()).toSeconds())
                .description("만료 배치가 마지막으로 끝난 뒤 지난 시간. 5분 넘으면 경보")
                .baseUnit("seconds")
                .register(meterRegistry);
        dbGauges.register("dawn.inventory.held.expired.overdue", "만료 시각이 5분 넘게 지났는데 풀리지 않은 HELD 예약", "reservations",
                () -> jdbcClient.sql("""
                        SELECT COUNT(*) FROM stock_reservation WHERE status = 'HELD' AND expires_at < NOW(3) - INTERVAL 5 MINUTE
                        """).query(Long.class).single());
        dbGauges.register("dawn.inventory.reconciliation.mismatch", "10분 간격 두 번 연속 재고 대사가 어긋난 상품. 사람이 보정해야 함", "products",
                () -> jdbcClient.sql("SELECT COUNT(*) FROM stock_reconciliation WHERE status = 'CONFIRMED'").query(Long.class).single());
    }
}
