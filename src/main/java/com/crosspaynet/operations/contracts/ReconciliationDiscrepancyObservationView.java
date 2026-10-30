package com.crosspaynet.operations.contracts;

import com.crosspaynet.operations.domain.DiscrepancyId;
import com.crosspaynet.operations.domain.DiscrepancyObservationId;
import com.crosspaynet.operations.domain.DiscrepancyStatus;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ObservationProvenance;
import com.crosspaynet.operations.domain.ReconciliationRunId;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable read-only view of a historical operational discrepancy observation.
 */
public record ReconciliationDiscrepancyObservationView(
        DiscrepancyObservationId observationId,
        DiscrepancyId discrepancyId,
        ReconciliationRunId runId,
        DiscrepancyType discrepancyType,
        DiscrepancyStatus statusAtObservation,
        String summary,
        String details,
        Instant observedAt,
        ObservationProvenance provenance,
        Instant createdAt
) {
    public ReconciliationDiscrepancyObservationView {
        Objects.requireNonNull(observationId, "observationId cannot be null.");
        Objects.requireNonNull(discrepancyId, "discrepancyId cannot be null.");
        Objects.requireNonNull(runId, "runId cannot be null.");
        Objects.requireNonNull(discrepancyType, "discrepancyType cannot be null.");
        Objects.requireNonNull(statusAtObservation, "statusAtObservation cannot be null.");
        Objects.requireNonNull(summary, "summary cannot be null.");
        Objects.requireNonNull(observedAt, "observedAt cannot be null.");
        Objects.requireNonNull(provenance, "provenance cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
    }
}
