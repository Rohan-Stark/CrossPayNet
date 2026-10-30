package com.crosspaynet.operations.infrastructure.web;

import com.crosspaynet.operations.contracts.ReconciliationDiscrepancyObservationView;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public response DTO for historical discrepancy observations.
 */
public record ReconciliationDiscrepancyObservationResponse(
        UUID observationId,
        UUID discrepancyId,
        UUID runId,
        String discrepancyType,
        String statusAtObservation,
        String summary,
        String details,
        Instant observedAt,
        String provenance,
        Instant createdAt
) {
    public ReconciliationDiscrepancyObservationResponse {
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

    public static ReconciliationDiscrepancyObservationResponse from(ReconciliationDiscrepancyObservationView view) {
        if (view == null) return null;
        return new ReconciliationDiscrepancyObservationResponse(
                view.observationId().getValue(),
                view.discrepancyId().getValue(),
                view.runId().getValue(),
                view.discrepancyType().name(),
                view.statusAtObservation().name(),
                view.summary(),
                view.details(),
                view.observedAt(),
                view.provenance().name(),
                view.createdAt()
        );
    }
}
