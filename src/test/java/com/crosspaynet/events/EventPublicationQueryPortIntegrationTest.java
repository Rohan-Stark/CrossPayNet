package com.crosspaynet.events;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.events.contracts.EventPublicationCursor;
import com.crosspaynet.events.contracts.EventPublicationPage;
import com.crosspaynet.events.contracts.EventPublicationQueryPort;
import com.crosspaynet.events.contracts.EventValidationException;
import com.crosspaynet.events.contracts.StalePublicationView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class EventPublicationQueryPortIntegrationTest {

    @Autowired
    private EventPublicationQueryPort eventPublicationQueryPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPaginateIncompletePublicationsDeterministicallyAndExcludeCompleted() {
        Instant baseTime = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

        UUID pub1 = UUID.randomUUID();
        UUID pub2 = UUID.randomUUID();
        UUID pub3 = UUID.randomUUID();
        UUID completedPub = UUID.randomUUID();

        insertPublication(pub1, "listener.a", "TestEventA", baseTime.plus(10, ChronoUnit.MINUTES), null, 0);
        insertPublication(pub2, "listener.b", "TestEventB", baseTime.plus(20, ChronoUnit.MINUTES), null, 1);
        insertPublication(pub3, "listener.c", "TestEventC", baseTime.plus(30, ChronoUnit.MINUTES), null, 2);
        insertPublication(completedPub, "listener.d", "TestEventD", baseTime.plus(15, ChronoUnit.MINUTES), baseTime.plus(16, ChronoUnit.MINUTES), 1);

        Instant cutoffTime = baseTime.plus(1, ChronoUnit.HOURS);

        EventPublicationPage page1 = eventPublicationQueryPort.findIncompletePublications(
                cutoffTime,
                null,
                2
        );

        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isPresent();
        assertThat(page1.items().get(0).publicationId()).isEqualTo(pub1);
        assertThat(page1.items().get(1).publicationId()).isEqualTo(pub2);

        EventPublicationCursor cursor1 = page1.nextCursor().orElseThrow();
        EventPublicationPage page2 = eventPublicationQueryPort.findIncompletePublications(
                cutoffTime,
                cursor1,
                2
        );

        assertThat(page2.items()).hasSize(1);
        assertThat(page2.hasMore()).isFalse();
        assertThat(page2.nextCursor()).isEmpty();
        assertThat(page2.items().get(0).publicationId()).isEqualTo(pub3);

        List<UUID> allReturned = List.of(
                page1.items().get(0).publicationId(),
                page1.items().get(1).publicationId(),
                page2.items().get(0).publicationId()
        );
        assertThat(allReturned).doesNotContain(completedPub);
    }

    @Test
    void shouldExcludePublicationsAfterCutoffTime() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        UUID oldPub = UUID.randomUUID();
        UUID futurePub = UUID.randomUUID();

        insertPublication(oldPub, "listener.old", "OldEvent", cutoff.minus(5, ChronoUnit.MINUTES), null, 0);
        insertPublication(futurePub, "listener.future", "FutureEvent", cutoff.plus(5, ChronoUnit.MINUTES), null, 0);

        EventPublicationPage page = eventPublicationQueryPort.findIncompletePublications(cutoff, null, 100);
        List<UUID> ids = page.items().stream().map(StalePublicationView::publicationId).toList();

        assertThat(ids).contains(oldPub);
        assertThat(ids).doesNotContain(futurePub);
    }

    @Test
    void shouldCountIncompletePublicationsAccurately() {
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID pub = UUID.randomUUID();
        insertPublication(pub, "listener.count", "CountEvent", cutoff.minus(10, ChronoUnit.MINUTES), null, 0);

        int count = eventPublicationQueryPort.countIncompletePublications(cutoff);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldEnforceInputValidation() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> eventPublicationQueryPort.findIncompletePublications(null, null, 10))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("cutoffTime cannot be null");

        assertThatThrownBy(() -> eventPublicationQueryPort.findIncompletePublications(now, null, 0))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("limit must be at least 1");

        assertThatThrownBy(() -> eventPublicationQueryPort.countIncompletePublications(null))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("cutoffTime cannot be null");
    }

    @Test
    void shouldNeverModifyOrResubmitPublicationsOnQuery() {
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID pub = UUID.randomUUID();
        insertPublication(pub, "listener.readOnly", "ReadOnlyEvent", cutoff.minus(5, ChronoUnit.MINUTES), null, 3);

        Map<String, Object> before = jdbcTemplate.queryForMap(
                "SELECT completion_date, completion_attempts, last_resubmission_date FROM system.event_publication WHERE id = ?",
                pub
        );

        eventPublicationQueryPort.findIncompletePublications(cutoff, null, 10);
        eventPublicationQueryPort.countIncompletePublications(cutoff);

        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT completion_date, completion_attempts, last_resubmission_date FROM system.event_publication WHERE id = ?",
                pub
        );

        assertThat(after.get("completion_date")).isNull();
        assertThat(after.get("completion_attempts")).isEqualTo(before.get("completion_attempts"));
        assertThat(after.get("last_resubmission_date")).isEqualTo(before.get("last_resubmission_date"));
    }

    private void insertPublication(
            UUID id,
            String listenerId,
            String eventType,
            Instant publicationDate,
            Instant completionDate,
            int attempts) {
        jdbcTemplate.update("""
            INSERT INTO system.event_publication (
                id, listener_id, event_type, serialized_event, publication_date, completion_date, status, completion_attempts, last_resubmission_date
            ) VALUES (?, ?, ?, '{"simulated": true}', ?, ?, ?, ?, NULL)
            """,
                id,
                listenerId,
                eventType,
                Timestamp.from(publicationDate),
                completionDate != null ? Timestamp.from(completionDate) : null,
                completionDate != null ? "COMPLETED" : "INCOMPLETE",
                attempts
        );
    }
}
