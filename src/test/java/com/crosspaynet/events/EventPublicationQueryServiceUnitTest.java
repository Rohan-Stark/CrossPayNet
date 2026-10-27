package com.crosspaynet.events;

import com.crosspaynet.events.contracts.EventPublicationCursor;
import com.crosspaynet.events.contracts.EventPublicationPage;
import com.crosspaynet.events.contracts.EventValidationException;
import com.crosspaynet.events.contracts.StalePublicationView;
import com.crosspaynet.events.infrastructure.persistence.DatabaseEventPublicationQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventPublicationQueryServiceUnitTest {

    private JdbcTemplate jdbcTemplate;
    private DatabaseEventPublicationQueryService queryService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        queryService = new DatabaseEventPublicationQueryService(jdbcTemplate);
    }

    @Test
    void shouldQueryFirstPageAndComputeContinuationCursor() {
        Instant cutoff = Instant.now();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        StalePublicationView v1 = new StalePublicationView(id1, "listener.1", "Event1", cutoff.minusSeconds(30), 0, Optional.empty());
        StalePublicationView v2 = new StalePublicationView(id2, "listener.2", "Event2", cutoff.minusSeconds(20), 1, Optional.empty());
        StalePublicationView v3 = new StalePublicationView(id3, "listener.3", "Event3", cutoff.minusSeconds(10), 2, Optional.empty());

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(Timestamp.from(cutoff)), eq(3)))
                .thenReturn(List.of(v1, v2, v3));

        EventPublicationPage page = queryService.findIncompletePublications(cutoff, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isPresent();
        EventPublicationCursor cursor = page.nextCursor().get();
        assertThat(cursor.publicationId()).isEqualTo(id2);
        assertThat(cursor.publicationDate()).isEqualTo(cutoff.minusSeconds(20));
    }

    @Test
    void shouldQueryNextPageUsingCursorAndIndicateEnd() {
        Instant cutoff = Instant.now();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        EventPublicationCursor cursor = new EventPublicationCursor(cutoff.minusSeconds(20), id2);
        StalePublicationView v3 = new StalePublicationView(id3, "listener.3", "Event3", cutoff.minusSeconds(10), 2, Optional.empty());

        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                eq(Timestamp.from(cutoff)),
                eq(Timestamp.from(cursor.publicationDate())),
                eq(Timestamp.from(cursor.publicationDate())),
                eq(id2),
                eq(3)
        )).thenReturn(List.of(v3));

        EventPublicationPage page = queryService.findIncompletePublications(cutoff, cursor, 2);

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isEmpty();
        assertThat(page.items().get(0).publicationId()).isEqualTo(id3);
    }

    @Test
    void shouldCountIncompletePublications() {
        Instant cutoff = Instant.now();
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(Timestamp.from(cutoff))))
                .thenReturn(7);

        int count = queryService.countIncompletePublications(cutoff);
        assertThat(count).isEqualTo(7);
    }

    @Test
    void shouldEnforceInputValidation() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> queryService.findIncompletePublications(null, null, 10))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("cutoffTime cannot be null");

        assertThatThrownBy(() -> queryService.findIncompletePublications(now, null, 0))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("limit must be at least 1");

        assertThatThrownBy(() -> queryService.countIncompletePublications(null))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("cutoffTime cannot be null");
    }
}
