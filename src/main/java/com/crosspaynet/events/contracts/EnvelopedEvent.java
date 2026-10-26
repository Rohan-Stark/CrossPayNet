package com.crosspaynet.events.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Interface implemented by domain events that participate in transactional outbox
 * externalization to Kafka with stable event identity and partition key routing.
 *
 * @param <T> event payload type
 */
public interface EnvelopedEvent<T> {

    /**
     * Stable, immutable event ID generated once prior to publication.
     */
    UUID eventId();

    /**
     * Logical type identifier for schema dispatch and serialization.
     */
    String eventType();

    /**
     * Event schema version.
     */
    int eventVersion();

    /**
     * UTC timestamp when the domain event occurred.
     */
    Instant occurredAt();

    /**
     * Correlation identifier tracing asynchronous flows across hops.
     */
    String correlationId();

    /**
     * Causation identifier indicating the causative message or command.
     */
    String causationId();

    /**
     * Deterministic partition key for ordering in Apache Kafka.
     */
    String partitionKey();

    /**
     * Maps this event to a standardized {@link EventEnvelope}.
     */
    EventEnvelope<T> toEnvelope();
}
