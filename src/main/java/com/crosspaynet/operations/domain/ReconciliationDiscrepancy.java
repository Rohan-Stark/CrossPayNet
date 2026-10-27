package com.crosspaynet.operations.domain;

import java.time.Instant;
import java.util.Objects;

public class ReconciliationDiscrepancy {

    private static final int MAX_ENTITY_ID_LENGTH = 100;
    private static final int MAX_SUMMARY_LENGTH = 500;

    private final DiscrepancyId discrepancyId;
    private Long version;
    private final DiscrepancyKey discrepancyKey;
    private final ReconciliationRunId firstRunId;
    private ReconciliationRunId latestRunId;
    private final DiscrepancyType discrepancyType;
    private final String operationalEntityId;
    private DiscrepancyStatus status;
    private String summary;
    private String details;
    private final Instant firstDetectedAt;
    private Instant lastObservedAt;
    private int consecutiveDetections;
    private Instant resolvedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public static ReconciliationDiscrepancy create(
            DiscrepancyId discrepancyId,
            DiscrepancyKey discrepancyKey,
            ReconciliationRunId runId,
            DiscrepancyType discrepancyType,
            String operationalEntityId,
            String summary,
            String details,
            Instant detectedAt
    ) {
        Objects.requireNonNull(discrepancyId, "DiscrepancyId cannot be null.");
        Objects.requireNonNull(discrepancyKey, "DiscrepancyKey cannot be null.");
        Objects.requireNonNull(runId, "ReconciliationRunId cannot be null.");
        Objects.requireNonNull(discrepancyType, "DiscrepancyType cannot be null.");
        Instant timestamp = detectedAt != null ? detectedAt : Instant.now();

        return new ReconciliationDiscrepancy(
                discrepancyId,
                0L,
                discrepancyKey,
                runId,
                runId,
                discrepancyType,
                operationalEntityId,
                DiscrepancyStatus.OPEN,
                summary,
                details,
                timestamp,
                timestamp,
                1,
                null,
                timestamp,
                timestamp
        );
    }

    public ReconciliationDiscrepancy(
            DiscrepancyId discrepancyId,
            Long version,
            DiscrepancyKey discrepancyKey,
            ReconciliationRunId firstRunId,
            ReconciliationRunId latestRunId,
            DiscrepancyType discrepancyType,
            String operationalEntityId,
            DiscrepancyStatus status,
            String summary,
            String details,
            Instant firstDetectedAt,
            Instant lastObservedAt,
            int consecutiveDetections,
            Instant resolvedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.discrepancyId = Objects.requireNonNull(discrepancyId, "DiscrepancyId cannot be null.");
        this.version = version != null ? version : 0L;
        this.discrepancyKey = Objects.requireNonNull(discrepancyKey, "DiscrepancyKey cannot be null.");
        this.firstRunId = Objects.requireNonNull(firstRunId, "firstRunId cannot be null.");
        this.latestRunId = Objects.requireNonNull(latestRunId, "latestRunId cannot be null.");
        this.discrepancyType = Objects.requireNonNull(discrepancyType, "DiscrepancyType cannot be null.");
        this.status = Objects.requireNonNull(status, "DiscrepancyStatus cannot be null.");
        this.firstDetectedAt = Objects.requireNonNull(firstDetectedAt, "firstDetectedAt cannot be null.");
        this.lastObservedAt = Objects.requireNonNull(lastObservedAt, "lastObservedAt cannot be null.");
        this.resolvedAt = resolvedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");

        if (operationalEntityId == null || operationalEntityId.isBlank()) {
            throw new OperationsValidationException("operationalEntityId cannot be blank.");
        }
        String trimmedEntityId = operationalEntityId.trim();
        if (codePointLength(trimmedEntityId) > MAX_ENTITY_ID_LENGTH) {
            throw new OperationsValidationException("operationalEntityId exceeds maximum length of " + MAX_ENTITY_ID_LENGTH + " characters.");
        }
        this.operationalEntityId = trimmedEntityId;

        this.summary = validateSummary(summary);
        this.details = details;

        if (consecutiveDetections < 1) {
            throw new OperationsValidationException("consecutiveDetections must be at least 1: " + consecutiveDetections);
        }
        this.consecutiveDetections = consecutiveDetections;

        validateStateInvariants();
    }

    public void recordRepeatObservation(ReconciliationRunId runId, String newSummary, String newDetails, Instant observedAt) {
        Objects.requireNonNull(runId, "ReconciliationRunId cannot be null.");
        Objects.requireNonNull(observedAt, "observedAt timestamp cannot be null.");
        if (observedAt.isBefore(firstDetectedAt)) {
            throw new OperationsValidationException("observedAt (" + observedAt + ") cannot be before firstDetectedAt (" + firstDetectedAt + ").");
        }

        if (this.status == DiscrepancyStatus.RESOLVED_EXTERNALLY) {
            // Reopen the existing record for a new episode
            this.status = DiscrepancyStatus.OPEN;
            this.resolvedAt = null;
            this.consecutiveDetections = 1;
        } else {
            // Increment recurring count for open or acknowledged finding
            this.consecutiveDetections++;
        }

        this.latestRunId = runId;
        this.lastObservedAt = observedAt;
        if (newSummary != null && !newSummary.isBlank()) {
            this.summary = validateSummary(newSummary);
        }
        if (newDetails != null) {
            this.details = newDetails;
        }
        this.updatedAt = observedAt;

        validateStateInvariants();
    }

    public void resolveExternally(Instant resolvedAt) {
        Objects.requireNonNull(resolvedAt, "resolvedAt timestamp cannot be null.");
        if (resolvedAt.isBefore(firstDetectedAt)) {
            throw new OperationsValidationException("resolvedAt (" + resolvedAt + ") cannot be before firstDetectedAt (" + firstDetectedAt + ").");
        }

        if (this.status == DiscrepancyStatus.RESOLVED_EXTERNALLY) {
            return;
        }

        this.status = DiscrepancyStatus.RESOLVED_EXTERNALLY;
        this.resolvedAt = resolvedAt;
        this.updatedAt = resolvedAt;

        validateStateInvariants();
    }

    public void acknowledge(Instant acknowledgedAt) {
        Objects.requireNonNull(acknowledgedAt, "acknowledgedAt timestamp cannot be null.");
        if (this.status == DiscrepancyStatus.RESOLVED_EXTERNALLY) {
            throw new InvalidOperationsStateException("Cannot acknowledge a resolved discrepancy: " + discrepancyId);
        }

        this.status = DiscrepancyStatus.ACKNOWLEDGED;
        this.updatedAt = acknowledgedAt;

        validateStateInvariants();
    }

    private void validateStateInvariants() {
        if (this.version < 0) {
            throw new OperationsValidationException("version cannot be negative.");
        }
        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new OperationsValidationException("updatedAt cannot be before createdAt.");
        }
        if (this.lastObservedAt.isBefore(this.firstDetectedAt)) {
            throw new OperationsValidationException("lastObservedAt cannot be before firstDetectedAt.");
        }
        if (this.status == DiscrepancyStatus.RESOLVED_EXTERNALLY) {
            if (this.resolvedAt == null) {
                throw new OperationsValidationException("resolvedAt cannot be null when discrepancy is RESOLVED_EXTERNALLY.");
            }
            if (this.resolvedAt.isBefore(this.firstDetectedAt)) {
                throw new OperationsValidationException("resolvedAt cannot be before firstDetectedAt.");
            }
        } else {
            if (this.resolvedAt != null) {
                throw new OperationsValidationException("resolvedAt must be null when discrepancy is " + this.status);
            }
        }
    }

    private static String validateSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            throw new OperationsValidationException("summary cannot be blank.");
        }
        String trimmed = summary.trim();
        if (codePointLength(trimmed) > MAX_SUMMARY_LENGTH) {
            throw new OperationsValidationException("summary exceeds maximum length of " + MAX_SUMMARY_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static int codePointLength(String str) {
        return str.codePointCount(0, str.length());
    }

    public DiscrepancyId getDiscrepancyId() {
        return discrepancyId;
    }

    public Long getVersion() {
        return version;
    }

    public DiscrepancyKey getDiscrepancyKey() {
        return discrepancyKey;
    }

    public ReconciliationRunId getFirstRunId() {
        return firstRunId;
    }

    public ReconciliationRunId getLatestRunId() {
        return latestRunId;
    }

    public DiscrepancyType getDiscrepancyType() {
        return discrepancyType;
    }

    public String getOperationalEntityId() {
        return operationalEntityId;
    }

    public DiscrepancyStatus getStatus() {
        return status;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetails() {
        return details;
    }

    public Instant getFirstDetectedAt() {
        return firstDetectedAt;
    }

    public Instant getLastObservedAt() {
        return lastObservedAt;
    }

    public int getConsecutiveDetections() {
        return consecutiveDetections;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
