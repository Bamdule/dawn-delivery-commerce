package io.github.bamdule.dawn.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 값의 근거는 docs/design/01 규모 가정과 docs/design/03 릴레이 절
@ConfigurationProperties(prefix = "dawn.outbox.relay")
public record OutboxProperties(
        Boolean enabled,
        Duration pollInterval,
        Integer batchSize,
        Duration claimDuration,
        Duration sendTimeout,
        Duration maxBackoff,
        Integer alertAttemptCount
) {

    public OutboxProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        pollInterval = pollInterval == null ? Duration.ofMillis(200) : pollInterval;
        batchSize = batchSize == null ? 500 : batchSize;
        claimDuration = claimDuration == null ? Duration.ofSeconds(30) : claimDuration;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(25) : sendTimeout;
        maxBackoff = maxBackoff == null ? Duration.ofSeconds(60) : maxBackoff;
        alertAttemptCount = alertAttemptCount == null ? 10 : alertAttemptCount;
    }
}
