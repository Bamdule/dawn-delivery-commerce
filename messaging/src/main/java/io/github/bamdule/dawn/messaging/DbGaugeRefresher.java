package io.github.bamdule.dawn.messaging;

import org.springframework.scheduling.annotation.Scheduled;

public class DbGaugeRefresher {

    private final DbGauges dbGauges;

    public DbGaugeRefresher(DbGauges dbGauges) {
        this.dbGauges = dbGauges;
    }

    @Scheduled(fixedDelayString = "${dawn.metrics.refresh-interval:15s}")
    public void refresh() {
        dbGauges.refresh();
    }
}
