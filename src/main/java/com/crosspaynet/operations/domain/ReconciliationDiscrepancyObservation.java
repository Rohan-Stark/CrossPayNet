package com.crosspaynet.operations.domain;

import java.time.Instant;
import java.util.Objects;

public class ReconciliationDiscrepancyObservation {

    private static final int MAX_SUMMARY_LENGTH = 500;

    private final DiscrepancyObservationId observationId;
    private final DiscrepancyId discrepancyId;
    private final ReconciliationRunId runId;
    private final DiscrepancyType discrepancyType;
    private final DiscrepancyStatus statusAtObservation;
    private final String summary;
    private final String details;
    private final Instant observedAt;
    private final ObservationProvenance provenance;
    private final Instant createdAt;

    public ReconciliationDiscrepancyObservation(
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
        this.observationId = Objects.requireNonNull(observationId, "observationId cannot be null.");
        this.discrepancyId = Objects.requireNonNull(discrepancyId, "discrepancyId cannot be null.");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null.");
        this.discrepancyType = Objects.requireNonNull(discrepancyType, "discrepancyType cannot be null.");
        this.statusAtObservation = Objects.requireNonNull(statusAtObservation, "statusAtObservation cannot be null.");
        this.summary = validateSummary(summary);
        this.details = details;
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt cannot be null.");
        this.provenance = Objects.requireNonNull(provenance, "provenance cannot be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null.");
    }

    public static ReconciliationDiscrepancyObservation createLive(
            DiscrepancyObservationId observationId,
            DiscrepancyId discrepancyId,
            ReconciliationRunId runId,
            DiscrepancyType discrepancyType,
            DiscrepancyStatus statusAtObservation,
            String summary,
            String details,
            Instant observedAt
    ) {
        Instant timestamp = observedAt != null ? observedAt : Instant.now();
        return new ReconciliationDiscrepancyObservation(
                observationId,
                discrepancyId,
                runId,
                discrepancyType,
                statusAtObservation,
                summary,
                details,
                timestamp,
                ObservationProvenance.LIVE,
                timestamp
        );
    }

    private static String validateSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            throw new OperationsValidationException("Observation summary cannot be blank.");
        }
        String trimmed = summary.trim();
        if (codePointLength(trimmed) > MAX_SUMMARY_LENGTH) {
            throw new OperationsValidationException("Observation summary exceeds maximum length of " + MAX_SUMMARY_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static int codePointLength(String str) {
        return str.codePointCount(0, str.length());
    }

    public DiscrepancyObservationId getObservationId() {
        return observationId;
    }

    public DiscrepancyId getDiscrepancyId() {
        return discrepancyId;
    }

    public ReconciliationRunId getRunId() {
        return runId;
    }

    public DiscrepancyType getDiscrepancyType() {
        return discrepancyType;
    }

    public DiscrepancyStatus getStatusAtObservation() {
        return statusAtObservation;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetails() {
        return details;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public ObservationProvenance getProvenance() {
        return provenance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReconciliationDiscrepancyObservation that = (ReconciliationDiscrepancyObservation) o;
        return Objects.equals(observationId, that.observationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(observationId);
    }
}
