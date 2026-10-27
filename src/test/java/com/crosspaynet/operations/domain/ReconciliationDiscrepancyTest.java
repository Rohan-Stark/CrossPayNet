package com.crosspaynet.operations.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationDiscrepancyTest {

    @Test
    void shouldCreateOpenDiscrepancyWithInitialEpisode() {
        DiscrepancyId id = DiscrepancyId.generate();
        ReconciliationRunId runId = ReconciliationRunId.generate();
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.PAYMENT_LEDGER_BREAK, "PAY-1234");
        Instant detectedAt = Instant.now().minus(1, ChronoUnit.HOURS);

        ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.create(
                id,
                key,
                runId,
                DiscrepancyType.PAYMENT_LEDGER_BREAK,
                "PAY-1234",
                "Payment in SETTLED state without corresponding Ledger posting",
                "{\"paymentId\":\"PAY-1234\",\"amount\":100.00}",
                detectedAt
        );

        assertThat(discrepancy.getDiscrepancyId()).isEqualTo(id);
        assertThat(discrepancy.getDiscrepancyKey()).isEqualTo(key);
        assertThat(discrepancy.getFirstRunId()).isEqualTo(runId);
        assertThat(discrepancy.getLatestRunId()).isEqualTo(runId);
        assertThat(discrepancy.getDiscrepancyType()).isEqualTo(DiscrepancyType.PAYMENT_LEDGER_BREAK);
        assertThat(discrepancy.getOperationalEntityId()).isEqualTo("PAY-1234");
        assertThat(discrepancy.getStatus()).isEqualTo(DiscrepancyStatus.OPEN);
        assertThat(discrepancy.getConsecutiveDetections()).isEqualTo(1);
        assertThat(discrepancy.getResolvedAt()).isNull();
        assertThat(discrepancy.getFirstDetectedAt()).isEqualTo(detectedAt);
        assertThat(discrepancy.getLastObservedAt()).isEqualTo(detectedAt);
    }

    @Test
    void shouldIncrementConsecutiveDetectionsOnRepeatObservationWhileOpen() {
        DiscrepancyId id = DiscrepancyId.generate();
        ReconciliationRunId run1 = ReconciliationRunId.generate();
        ReconciliationRunId run2 = ReconciliationRunId.generate();
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.RESERVATION_PAYMENT_MISMATCH, "RES-9999");
        Instant t1 = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant t2 = Instant.now().minus(1, ChronoUnit.HOURS);

        ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.create(
                id, key, run1, DiscrepancyType.RESERVATION_PAYMENT_MISMATCH, "RES-9999", "Mismatch 1", null, t1
        );

        discrepancy.recordRepeatObservation(run2, "Updated mismatch description", "{\"extra\":\"info\"}", t2);

        assertThat(discrepancy.getStatus()).isEqualTo(DiscrepancyStatus.OPEN);
        assertThat(discrepancy.getConsecutiveDetections()).isEqualTo(2);
        assertThat(discrepancy.getFirstRunId()).isEqualTo(run1);
        assertThat(discrepancy.getLatestRunId()).isEqualTo(run2);
        assertThat(discrepancy.getFirstDetectedAt()).isEqualTo(t1);
        assertThat(discrepancy.getLastObservedAt()).isEqualTo(t2);
        assertThat(discrepancy.getSummary()).isEqualTo("Updated mismatch description");
        assertThat(discrepancy.getDetails()).isEqualTo("{\"extra\":\"info\"}");
    }

    @Test
    void shouldTransitionToAcknowledgedAndPreserveStatusOnRepeatObservation() {
        DiscrepancyId id = DiscrepancyId.generate();
        ReconciliationRunId run1 = ReconciliationRunId.generate();
        ReconciliationRunId run2 = ReconciliationRunId.generate();
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-STALE");
        Instant t1 = Instant.now().minus(3, ChronoUnit.HOURS);
        Instant t2 = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant t3 = Instant.now().minus(1, ChronoUnit.HOURS);

        ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.create(
                id, key, run1, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-STALE", "Stale workflow", null, t1
        );

        discrepancy.acknowledge(t2);
        assertThat(discrepancy.getStatus()).isEqualTo(DiscrepancyStatus.ACKNOWLEDGED);

        discrepancy.recordRepeatObservation(run2, null, null, t3);
        assertThat(discrepancy.getStatus()).isEqualTo(DiscrepancyStatus.ACKNOWLEDGED);
        assertThat(discrepancy.getConsecutiveDetections()).isEqualTo(2);
        assertThat(discrepancy.getLatestRunId()).isEqualTo(run2);
    }

    @Test
    void shouldResolveExternallyAndRejectAcknowledgment() {
        DiscrepancyId id = DiscrepancyId.generate();
        ReconciliationRunId run1 = ReconciliationRunId.generate();
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.INCOMPLETE_EVENT_PUBLICATION, "PUB-101");
        Instant t1 = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant tResolved = Instant.now().minus(1, ChronoUnit.HOURS);

        ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.create(
                id, key, run1, DiscrepancyType.INCOMPLETE_EVENT_PUBLICATION, "PUB-101", "Outbox pub stuck", null, t1
        );

        discrepancy.resolveExternally(tResolved);
        assertThat(discrepancy.getStatus()).isEqualTo(DiscrepancyStatus.RESOLVED_EXTERNALLY);
        assertThat(discrepancy.getResolvedAt()).isEqualTo(tResolved);

        // Idempotent resolution
        discrepancy.resolveExternally(tResolved);
        assertThat(discrepancy.getStatus()).isEqualTo(DiscrepancyStatus.RESOLVED_EXTERNALLY);

        // Cannot acknowledge resolved discrepancy
        assertThatThrownBy(() -> discrepancy.acknowledge(Instant.now()))
                .isInstanceOf(InvalidOperationsStateException.class)
                .hasMessageContaining("Cannot acknowledge a resolved discrepancy");
    }

    @Test
    void shouldReopenResolvedDiscrepancyOnRepeatObservationResettingEpisodeCount() {
        DiscrepancyId id = DiscrepancyId.generate();
        ReconciliationRunId run1 = ReconciliationRunId.generate();
        ReconciliationRunId run2 = ReconciliationRunId.generate();
        ReconciliationRunId run3 = ReconciliationRunId.generate();
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.PAYMENT_LEDGER_BREAK, "PAY-REOPEN");
        Instant t1 = Instant.now().minus(5, ChronoUnit.HOURS);
        Instant t2 = Instant.now().minus(4, ChronoUnit.HOURS);
        Instant tResolved = Instant.now().minus(3, ChronoUnit.HOURS);
        Instant tReopened = Instant.now().minus(1, ChronoUnit.HOURS);

        ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.create(
                id, key, run1, DiscrepancyType.PAYMENT_LEDGER_BREAK, "PAY-REOPEN", "Initial break", null, t1
        );
        discrepancy.recordRepeatObservation(run2, "Still broken", null, t2);
        assertThat(discrepancy.getConsecutiveDetections()).isEqualTo(2);

        discrepancy.resolveExternally(tResolved);
        assertThat(discrepancy.getStatus()).isEqualTo(DiscrepancyStatus.RESOLVED_EXTERNALLY);
        assertThat(discrepancy.getResolvedAt()).isEqualTo(tResolved);

        // Reappears on run 3: must reopen and reset consecutiveDetections to 1 for the new episode
        discrepancy.recordRepeatObservation(run3, "Reappeared break", "{\"notes\":\"new episode\"}", tReopened);

        assertThat(discrepancy.getStatus()).isEqualTo(DiscrepancyStatus.OPEN);
        assertThat(discrepancy.getResolvedAt()).isNull();
        assertThat(discrepancy.getConsecutiveDetections()).isEqualTo(1);
        assertThat(discrepancy.getFirstRunId()).isEqualTo(run1); // original first run preserved
        assertThat(discrepancy.getFirstDetectedAt()).isEqualTo(t1); // original first detected preserved
        assertThat(discrepancy.getLatestRunId()).isEqualTo(run3);
        assertThat(discrepancy.getLastObservedAt()).isEqualTo(tReopened);
        assertThat(discrepancy.getSummary()).isEqualTo("Reappeared break");
        assertThat(discrepancy.getDetails()).isEqualTo("{\"notes\":\"new episode\"}");
    }

    @Test
    void shouldRejectInvalidInvariants() {
        DiscrepancyId id = DiscrepancyId.generate();
        ReconciliationRunId run1 = ReconciliationRunId.generate();
        DiscrepancyKey key = DiscrepancyKey.of("STALE_IN_FLIGHT_WORKFLOW:P1");
        Instant now = Instant.now();

        // consecutiveDetections < 1
        assertThatThrownBy(() -> new ReconciliationDiscrepancy(
                id, 0L, key, run1, run1, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "P1",
                DiscrepancyStatus.OPEN, "Summary", null, now, now, 0, null, now, now
        )).isInstanceOf(OperationsValidationException.class).hasMessageContaining("consecutiveDetections");

        // resolvedAt non-null for OPEN
        assertThatThrownBy(() -> new ReconciliationDiscrepancy(
                id, 0L, key, run1, run1, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "P1",
                DiscrepancyStatus.OPEN, "Summary", null, now, now, 1, now, now, now
        )).isInstanceOf(OperationsValidationException.class).hasMessageContaining("resolvedAt must be null");

        // resolvedAt null for RESOLVED_EXTERNALLY
        assertThatThrownBy(() -> new ReconciliationDiscrepancy(
                id, 0L, key, run1, run1, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "P1",
                DiscrepancyStatus.RESOLVED_EXTERNALLY, "Summary", null, now, now, 1, null, now, now
        )).isInstanceOf(OperationsValidationException.class).hasMessageContaining("resolvedAt cannot be null");

        // resolvedAt before firstDetectedAt
        assertThatThrownBy(() -> new ReconciliationDiscrepancy(
                id, 0L, key, run1, run1, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "P1",
                DiscrepancyStatus.RESOLVED_EXTERNALLY, "Summary", null, now, now, 1,
                now.minus(1, ChronoUnit.MINUTES), now, now
        )).isInstanceOf(OperationsValidationException.class).hasMessageContaining("resolvedAt cannot be before firstDetectedAt");
    }
}
