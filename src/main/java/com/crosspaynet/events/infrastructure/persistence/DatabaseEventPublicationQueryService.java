package com.crosspaynet.events.infrastructure.persistence;

import com.crosspaynet.events.contracts.EventPublicationCursor;
import com.crosspaynet.events.contracts.EventPublicationPage;
import com.crosspaynet.events.contracts.EventPublicationQueryPort;
import com.crosspaynet.events.contracts.EventValidationException;
import com.crosspaynet.events.contracts.StalePublicationView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only JDBC implementation of {@link EventPublicationQueryPort}.
 *
 * <p>Queries Spring Modulith's {@code system.event_publication} table strictly on read
 * with deterministic keyset pagination and data minimization. Never replays, modifies,
 * or completes event publications.
 */
@Service
public class DatabaseEventPublicationQueryService implements EventPublicationQueryPort {

    private static final int MAX_PAGE_SIZE = 500;

    private static final String SELECT_FIRST_PAGE_SQL = """
        SELECT id, listener_id, event_type, publication_date, completion_attempts, last_resubmission_date
          FROM system.event_publication
         WHERE completion_date IS NULL
           AND publication_date <= ?
         ORDER BY publication_date ASC, id ASC
         LIMIT ?
        """;

    private static final String SELECT_NEXT_PAGE_SQL = """
        SELECT id, listener_id, event_type, publication_date, completion_attempts, last_resubmission_date
          FROM system.event_publication
         WHERE completion_date IS NULL
           AND publication_date <= ?
           AND (publication_date > ? OR (publication_date = ? AND id > ?))
         ORDER BY publication_date ASC, id ASC
         LIMIT ?
        """;

    private static final String COUNT_INCOMPLETE_SQL = """
        SELECT COUNT(*)
          FROM system.event_publication
         WHERE completion_date IS NULL
           AND publication_date <= ?
        """;

    private final JdbcTemplate jdbcTemplate;
    private final StalePublicationRowMapper rowMapper = new StalePublicationRowMapper();

    public DatabaseEventPublicationQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null.");
    }

    @Override
    @Transactional(readOnly = true)
    public EventPublicationPage findIncompletePublications(
            Instant cutoffTime,
            EventPublicationCursor cursor,
            int limit) {
        if (cutoffTime == null) {
            throw new EventValidationException("cutoffTime cannot be null.");
        }
        if (limit < 1) {
            throw new EventValidationException("limit must be at least 1.");
        }
        int effectiveLimit = Math.min(limit, MAX_PAGE_SIZE);
        int fetchSize = effectiveLimit + 1;

        List<StalePublicationView> items;
        if (cursor == null) {
            items = jdbcTemplate.query(
                    SELECT_FIRST_PAGE_SQL,
                    rowMapper,
                    Timestamp.from(cutoffTime),
                    fetchSize
            );
        } else {
            Timestamp cutoffTimestamp = Timestamp.from(cutoffTime);
            Timestamp cursorTimestamp = Timestamp.from(cursor.publicationDate());
            items = jdbcTemplate.query(
                    SELECT_NEXT_PAGE_SQL,
                    rowMapper,
                    cutoffTimestamp,
                    cursorTimestamp,
                    cursorTimestamp,
                    cursor.publicationId(),
                    fetchSize
            );
        }

        boolean hasMore = items.size() > effectiveLimit;
        List<StalePublicationView> pagedItems = hasMore ? items.subList(0, effectiveLimit) : items;

        Optional<EventPublicationCursor> nextCursor = Optional.empty();
        if (hasMore && !pagedItems.isEmpty()) {
            StalePublicationView last = pagedItems.get(pagedItems.size() - 1);
            nextCursor = Optional.of(new EventPublicationCursor(last.publicationDate(), last.publicationId()));
        }

        return new EventPublicationPage(pagedItems, hasMore, nextCursor);
    }

    @Override
    @Transactional(readOnly = true)
    public int countIncompletePublications(Instant cutoffTime) {
        if (cutoffTime == null) {
            throw new EventValidationException("cutoffTime cannot be null.");
        }
        Integer count = jdbcTemplate.queryForObject(
                COUNT_INCOMPLETE_SQL,
                Integer.class,
                Timestamp.from(cutoffTime)
        );
        return count != null ? count : 0;
    }

    private static class StalePublicationRowMapper implements RowMapper<StalePublicationView> {
        @Override
        public StalePublicationView mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID id = (UUID) rs.getObject("id");
            String listenerId = rs.getString("listener_id");
            String eventType = rs.getString("event_type");
            Timestamp pubTimestamp = rs.getTimestamp("publication_date");
            Instant publicationDate = pubTimestamp != null ? pubTimestamp.toInstant() : Instant.EPOCH;
            int attempts = rs.getInt("completion_attempts");
            Timestamp resubTimestamp = rs.getTimestamp("last_resubmission_date");
            Optional<Instant> lastResubmissionDate = Optional.ofNullable(resubTimestamp).map(Timestamp::toInstant);

            return new StalePublicationView(
                    id,
                    listenerId,
                    eventType,
                    publicationDate,
                    attempts,
                    lastResubmissionDate
            );
        }
    }
}
