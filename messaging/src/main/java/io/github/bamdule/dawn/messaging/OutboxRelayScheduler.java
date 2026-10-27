package io.github.bamdule.dawn.messaging;

import org.springframework.scheduling.annotation.Scheduled;

public class OutboxRelayScheduler {

    private final OutboxRelay outboxRelay;
    private final OutboxProperties outboxProperties;

    public OutboxRelayScheduler(OutboxRelay outboxRelay, OutboxProperties outboxProperties) {
        this.outboxRelay = outboxRelay;
        this.outboxProperties = outboxProperties;
    }

    // 한 바퀴를 꽉 채워 집었으면 더 남았을 수 있으니 주기를 기다리지 않고 바로 다음 바퀴를 돈다
    @Scheduled(fixedDelayString = "${dawn.outbox.relay.poll-interval:200ms}")
    public void relay() {
        int relayed;
        do {
            relayed = outboxRelay.relayOnce();
        } while (relayed == outboxProperties.batchSize());
    }
}
