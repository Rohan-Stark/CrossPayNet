package com.crosspaynet.messaging.contracts;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain event published when an ISO 20022 message has been successfully generated,
 * signed, persisted, and dispatched across the network boundary.
 */
@Externalized("crosspaynet.messaging.message-dispatched.v1::#{#this.paymentId()}")
public record PaymentSentToNetwork(
        UUID eventId,
        UUID paymentId,
        UUID messageId,
        int attemptNumber,
        String routeReference,
        Instant occurredAt,
        String correlationId,
        String causationId
) {

    public PaymentSentToNetwork {
        Objects.requireNonNull(eventId, "eventId cannot be null.");
        Objects.requireNonNull(paymentId, "paymentId cannot be null.");
        Objects.requireNonNull(messageId, "messageId cannot be null.");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be at least 1, but was: " + attemptNumber);
        }
        Objects.requireNonNull(routeReference, "routeReference cannot be null.");
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
