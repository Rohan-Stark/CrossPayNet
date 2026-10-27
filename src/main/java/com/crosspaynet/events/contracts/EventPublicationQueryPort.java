package com.crosspaynet.events.contracts;

import java.time.Instant;

/**
 * Public read-only query port for inspecting incomplete transactional event publications.
 *
 * <p>Enforces strict encapsulation: provides read-only inspection only; never triggers
 * resubmissions, replays, or modifications of the event publication registry.
 */
public interface EventPublicationQueryPort {

    /**
     * Returns a bounded, deterministic keyset-paginated slice of incomplete event publications
     * published on or before the given cutoff timestamp.
     *
     * @param cutoffTime upper bound for publication date (inclusive)
     * @param cursor     optional continuation cursor; if {@code null}, scans from the beginning
     * @param limit      maximum number of records to return (bounded between 1 and system maximum)
     * @return paginated result with continuation information
     */
    EventPublicationPage findIncompletePublications(
            Instant cutoffTime,
            EventPublicationCursor cursor,
            int limit
    );

    /**
     * Returns total count of incomplete publications published on or before {@code cutoffTime}.
     */
    int countIncompletePublications(Instant cutoffTime);
}
