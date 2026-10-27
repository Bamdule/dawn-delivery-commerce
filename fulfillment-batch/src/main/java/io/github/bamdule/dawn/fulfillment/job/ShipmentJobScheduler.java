package io.github.bamdule.dawn.fulfillment.job;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 23:10 에 그날 23:00 회차를 돌린다. 마감 직전 결제의 PAID 이벤트가 도착할 여유를 10분 둔다 (docs/design/05)
@Component
@ConditionalOnProperty(prefix = "dawn.fulfillment.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShipmentJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(ShipmentJobScheduler.class);

    private final ShipmentJobRunner shipmentJobRunner;

    public ShipmentJobScheduler(ShipmentJobRunner shipmentJobRunner) {
        this.shipmentJobRunner = shipmentJobRunner;
    }

    @Scheduled(cron = "${dawn.fulfillment.scheduler.cron:0 10 23 * * *}", zone = "Asia/Seoul")
    public void runTonight() {
        try {
            shipmentJobRunner.run(shipmentJobRunner.cutoffOf(LocalDate.now()));
        } catch (Exception exception) {
            log.error("출고 배치 시작 실패", exception);
        }
    }
}
