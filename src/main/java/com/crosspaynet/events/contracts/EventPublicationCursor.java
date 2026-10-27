package com.crosspaynet.events.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic keyset cursor for keyset pagination of incomplete event publications.
 */
public record EventPublicationCursor(
        Instant publicationDate,
        UUID publicationId
) {

    public EventPublicationCursor {
        Objects.requireNonNull(publicationDate, "publicationDate cannot be null in cursor.");
        Objects.requireNonNull(publicationId, "publicationId cannot be null in cursor.");
    }
}
