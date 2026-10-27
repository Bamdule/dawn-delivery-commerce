package io.github.bamdule.dawn.messaging;

import java.util.Objects;
import java.util.UUID;

public record OutboxMessage(UUID eventId, String topic, String partitionKey, String payload) {

    public OutboxMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(payload, "payload");
    }
}
