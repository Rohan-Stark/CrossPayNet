package com.crosspaynet.operations.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscrepancyObservationTest {

    @Test
    @DisplayName("DiscrepancyObservationId generates valid UUID and enforces non-blank string format")
    void discrepancyObservationIdValidation() {
        DiscrepancyObservationId id = DiscrepancyObservationId.generate();
        assertThat(id.getValue()).isNotNull();
        assertThat(DiscrepancyObservationId.of(id.getValue())).isEqualTo(id);
        assertThat(DiscrepancyObservationId.of(id.toString())).isEqualTo(id);

        assertThatThrownBy(() -> DiscrepancyObservationId.of((String) null))
                .isInstanceOf(OperationsValidationException.class);
        assertThatThrownBy(() -> DiscrepancyObservationId.of("   "))
                .isInstanceOf(OperationsValidationException.class);
        assertThatThrownBy(() -> DiscrepancyObservationId.of("invalid-uuid"))
                .isInstanceOf(OperationsValidationException.class);
    }

    @Test
    @DisplayName("createLive creates observation with LIVE provenance and valid attributes")
    void createLiveObservation() {
        DiscrepancyObservationId obsId = DiscrepancyObservationId.generate();
        DiscrepancyId discId = DiscrepancyId.generate();
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();

        ReconciliationDiscrepancyObservation obs = ReconciliationDiscrepancyObservation.createLive(
                obsId,
                discId,
                runId,
                DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW,
                DiscrepancyStatus.OPEN,
                "Payment workflow is stale",
                "{\"paymentId\":\"P-001\"}",
                now
        );

        assertThat(obs.getObservationId()).isEqualTo(obsId);
        assertThat(obs.getDiscrepancyId()).isEqualTo(discId);
        assertThat(obs.getRunId()).isEqualTo(runId);
        assertThat(obs.getDiscrepancyType()).isEqualTo(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW);
        assertThat(obs.getStatusAtObservation()).isEqualTo(DiscrepancyStatus.OPEN);
        assertThat(obs.getSummary()).isEqualTo("Payment workflow is stale");
        assertThat(obs.getDetails()).isEqualTo("{\"paymentId\":\"P-001\"}");
        assertThat(obs.getObservedAt()).isEqualTo(now);
        assertThat(obs.getProvenance()).isEqualTo(ObservationProvenance.LIVE);
        assertThat(obs.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Observation rejects blank or excessively long summary")
    void summaryValidation() {
        DiscrepancyObservationId obsId = DiscrepancyObservationId.generate();
        DiscrepancyId discId = DiscrepancyId.generate();
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();

        assertThatThrownBy(() -> ReconciliationDiscrepancyObservation.createLive(
                obsId, discId, runId, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, DiscrepancyStatus.OPEN,
                null, null, now))
                .isInstanceOf(OperationsValidationException.class)
                .hasMessageContaining("cannot be blank");

        assertThatThrownBy(() -> ReconciliationDiscrepancyObservation.createLive(
                obsId, discId, runId, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, DiscrepancyStatus.OPEN,
                "   ", null, now))
                .isInstanceOf(OperationsValidationException.class)
                .hasMessageContaining("cannot be blank");

        String over500 = "x".repeat(501);
        assertThatThrownBy(() -> ReconciliationDiscrepancyObservation.createLive(
                obsId, discId, runId, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, DiscrepancyStatus.OPEN,
                over500, null, now))
                .isInstanceOf(OperationsValidationException.class)
                .hasMessageContaining("exceeds maximum length of 500");
    }
}
