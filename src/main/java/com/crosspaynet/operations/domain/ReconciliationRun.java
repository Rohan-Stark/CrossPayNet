package com.crosspaynet.operations.domain;

import java.time.Instant;
import java.util.Objects;

public class ReconciliationRun {

    private static final int MAX_TRIGGERED_BY_LENGTH = 100;
    private static final int MAX_FAILURE_REASON_LENGTH = 1000;

    private final ReconciliationRunId runId;
    private Long version;
    private ReconciliationRunStatus status;
    private final Instant startedAt;
    private Instant completedAt;
    private int checksEvaluated;
    private int discrepancyCount;
    private final String triggeredBy;
    private String failureReason;
    private final Instant createdAt;
    private Instant updatedAt;

    public static ReconciliationRun start(ReconciliationRunId runId, String triggeredBy, Instant startedAt) {
        Objects.requireNonNull(runId, "ReconciliationRunId cannot be null.");
        Objects.requireNonNull(startedAt, "startedAt timestamp cannot be null.");
        if (triggeredBy == null || triggeredBy.isBlank()) {
            throw new OperationsValidationException("triggeredBy cannot be blank.");
        }
        String trimmedTriggeredBy = triggeredBy.trim();
        if (codePointLength(trimmedTriggeredBy) > MAX_TRIGGERED_BY_LENGTH) {
            throw new OperationsValidationException("triggeredBy exceeds maximum length of " + MAX_TRIGGERED_BY_LENGTH + " characters.");
        }

        return new ReconciliationRun(
                runId,
                0L,
                ReconciliationRunStatus.IN_PROGRESS,
                startedAt,
                null,
                0,
                0,
                trimmedTriggeredBy,
                null,
                startedAt,
                startedAt
        );
    }

    public ReconciliationRun(
            ReconciliationRunId runId,
            Long version,
            ReconciliationRunStatus status,
            Instant startedAt,
            Instant completedAt,
            int checksEvaluated,
            int discrepancyCount,
            String triggeredBy,
            String failureReason,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.runId = Objects.requireNonNull(runId, "ReconciliationRunId cannot be null.");
        this.version = version != null ? version : 0L;
        this.status = Objects.requireNonNull(status, "ReconciliationRunStatus cannot be null.");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt cannot be null.");
        this.completedAt = completedAt;
        this.checksEvaluated = validateNonNegative(checksEvaluated, "checksEvaluated");
        this.discrepancyCount = validateNonNegative(discrepancyCount, "discrepancyCount");
        this.triggeredBy = Objects.requireNonNull(triggeredBy, "triggeredBy cannot be null.");
        this.failureReason = sanitizeFailureReason(failureReason);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");

        validateStateInvariants();
    }

    public void complete(int checksEvaluated, int discrepancyCount, Instant completedAt) {
        requireInProgress("complete run");
        Objects.requireNonNull(completedAt, "completedAt timestamp cannot be null.");
        if (completedAt.isBefore(startedAt)) {
            throw new OperationsValidationException("completedAt (" + completedAt + ") cannot be before startedAt (" + startedAt + ").");
        }

        this.checksEvaluated = validateNonNegative(checksEvaluated, "checksEvaluated");
        this.discrepancyCount = validateNonNegative(discrepancyCount, "discrepancyCount");
        this.completedAt = completedAt;
        this.status = ReconciliationRunStatus.COMPLETED;
        this.failureReason = null;
        this.updatedAt = completedAt;
    }

    public void markPartialFailure(int checksEvaluated, int discrepancyCount, String failureReason, Instant completedAt) {
        requireInProgress("mark run as partial failure");
        Objects.requireNonNull(completedAt, "completedAt timestamp cannot be null.");
        if (completedAt.isBefore(startedAt)) {
            throw new OperationsValidationException("completedAt (" + completedAt + ") cannot be before startedAt (" + startedAt + ").");
        }

        this.checksEvaluated = validateNonNegative(checksEvaluated, "checksEvaluated");
        this.discrepancyCount = validateNonNegative(discrepancyCount, "discrepancyCount");
        this.failureReason = sanitizeFailureReason(failureReason);
        this.completedAt = completedAt;
        this.status = ReconciliationRunStatus.PARTIAL_FAILURE;
        this.updatedAt = completedAt;
    }

    public void fail(String failureReason, Instant completedAt) {
        requireInProgress("fail run");
        Objects.requireNonNull(completedAt, "completedAt timestamp cannot be null.");
        if (completedAt.isBefore(startedAt)) {
            throw new OperationsValidationException("completedAt (" + completedAt + ") cannot be before startedAt (" + startedAt + ").");
        }
        if (failureReason == null || failureReason.isBlank()) {
            throw new OperationsValidationException("failureReason cannot be blank when failing a reconciliation run.");
        }

        this.failureReason = sanitizeFailureReason(failureReason);
        this.completedAt = completedAt;
        this.status = ReconciliationRunStatus.FAILED;
        this.updatedAt = completedAt;
    }

    private void requireInProgress(String action) {
        if (this.status.isTerminal()) {
            throw new InvalidOperationsStateException(
                    "Cannot " + action + " on reconciliation run " + runId + " because it is already in terminal status: " + status
            );
        }
    }

    private void validateStateInvariants() {
        if (this.version < 0) {
            throw new OperationsValidationException("version cannot be negative.");
        }
        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new OperationsValidationException("updatedAt cannot be before createdAt.");
        }
        if (this.completedAt != null && this.completedAt.isBefore(this.startedAt)) {
            throw new OperationsValidationException("completedAt cannot be before startedAt.");
        }
        if (this.status == ReconciliationRunStatus.IN_PROGRESS) {
            if (this.completedAt != null) {
                throw new OperationsValidationException("completedAt must be null for IN_PROGRESS run.");
            }
            if (this.failureReason != null) {
                throw new OperationsValidationException("failureReason must be null for IN_PROGRESS run.");
            }
        } else {
            if (this.completedAt == null) {
                throw new OperationsValidationException("completedAt cannot be null for terminal status: " + status);
            }
        }
    }

    private static int validateNonNegative(int count, String fieldName) {
        if (count < 0) {
            throw new OperationsValidationException(fieldName + " cannot be negative: " + count);
        }
        return count;
    }

    private static String sanitizeFailureReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (codePointLength(trimmed) > MAX_FAILURE_REASON_LENGTH) {
            throw new OperationsValidationException("failureReason exceeds maximum length of " + MAX_FAILURE_REASON_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static int codePointLength(String str) {
        return str.codePointCount(0, str.length());
    }

    public ReconciliationRunId getRunId() {
        return runId;
    }

    public Long getVersion() {
        return version;
    }

    public ReconciliationRunStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public int getChecksEvaluated() {
        return checksEvaluated;
    }

    public int getDiscrepancyCount() {
        return discrepancyCount;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
