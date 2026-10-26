package com.crosspaynet.events.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Standardized immutable distributed event envelope.
 *
 * <p>Enforces stable identity across network retries, broker retransmissions,
 * and transactional outbox/inbox processing.
 *
 * @param <T> typed payload
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String correlationId,
        String causationId,
        T payload
) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId cannot be null.");
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType cannot be blank.");
        }
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be at least 1, but was: " + eventVersion);
        }
        Objects.requireNonNull(occurredAt, "occurredAt cannot be null.");
        Objects.requireNonNull(payload, "payload cannot be null.");
    }
}
