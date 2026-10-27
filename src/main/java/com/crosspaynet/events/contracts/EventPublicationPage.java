package com.crosspaynet.events.contracts;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Keyset-paginated result containing incomplete publication views and continuation cursor.
 */
public record EventPublicationPage(
        List<StalePublicationView> items,
        boolean hasMore,
        Optional<EventPublicationCursor> nextCursor
) {

    public EventPublicationPage {
        items = items != null ? List.copyOf(items) : List.of();
        Objects.requireNonNull(nextCursor, "nextCursor cannot be null.");
    }

    public static EventPublicationPage empty() {
        return new EventPublicationPage(List.of(), false, Optional.empty());
    }
}
