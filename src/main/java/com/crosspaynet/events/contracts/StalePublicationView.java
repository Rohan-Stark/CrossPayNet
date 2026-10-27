package com.crosspaynet.events.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal, safe read-only metadata of an incomplete event publication for operational reconciliation.
 *
 * <p>Intentionally omits the raw serialized event payload to preserve data minimization and security.
 */
public record StalePublicationView(
        UUID publicationId,
        String listenerId,
        String eventType,
        Instant publicationDate,
        int completionAttempts,
        Optional<Instant> lastResubmissionDate
) {

    public StalePublicationView {
        Objects.requireNonNull(publicationId, "publicationId cannot be null.");
        Objects.requireNonNull(listenerId, "listenerId cannot be null.");
        Objects.requireNonNull(eventType, "eventType cannot be null.");
        Objects.requireNonNull(publicationDate, "publicationDate cannot be null.");
        Objects.requireNonNull(lastResubmissionDate, "lastResubmissionDate Optional cannot be null.");
        if (completionAttempts < 0) {
            throw new EventValidationException("completionAttempts cannot be negative.");
        }
    }
}
