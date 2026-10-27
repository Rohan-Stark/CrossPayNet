package com.crosspaynet.operations.domain;

import java.time.Instant;
import java.util.Objects;

public class ReconciliationCheckExecution {

    private static final int MAX_CHECK_NAME_LENGTH = 100;
    private static final int MAX_ERROR_SUMMARY_LENGTH = 1000;

    private final ReconciliationCheckExecutionId checkExecutionId;
    private final ReconciliationRunId runId;
    private final String checkName;
    private final ReconciliationCheckStatus status;
    private final int itemsScanned;
    private final int discrepanciesFound;
    private final String errorSummary;
    private final Instant executedAt;
    private final Instant createdAt;

    public static ReconciliationCheckExecution create(
            ReconciliationCheckExecutionId checkExecutionId,
            ReconciliationRunId runId,
            String checkName,
            ReconciliationCheckStatus status,
            int itemsScanned,
            int discrepanciesFound,
            String errorSummary,
            Instant executedAt
    ) {
        Instant now = executedAt != null ? executedAt : Instant.now();
        return new ReconciliationCheckExecution(
                checkExecutionId,
                runId,
                checkName,
                status,
                itemsScanned,
                discrepanciesFound,
                errorSummary,
                now,
                now
        );
    }

    public ReconciliationCheckExecution(
            ReconciliationCheckExecutionId checkExecutionId,
            ReconciliationRunId runId,
            String checkName,
            ReconciliationCheckStatus status,
            int itemsScanned,
            int discrepanciesFound,
            String errorSummary,
            Instant executedAt,
            Instant createdAt
    ) {
        this.checkExecutionId = Objects.requireNonNull(checkExecutionId, "ReconciliationCheckExecutionId cannot be null.");
        this.runId = Objects.requireNonNull(runId, "ReconciliationRunId cannot be null.");
        this.status = Objects.requireNonNull(status, "ReconciliationCheckStatus cannot be null.");
        this.executedAt = Objects.requireNonNull(executedAt, "executedAt cannot be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null.");

        if (checkName == null || checkName.isBlank()) {
            throw new OperationsValidationException("checkName cannot be blank.");
        }
        String trimmedName = checkName.trim();
        if (codePointLength(trimmedName) > MAX_CHECK_NAME_LENGTH) {
            throw new OperationsValidationException("checkName exceeds maximum length of " + MAX_CHECK_NAME_LENGTH + " characters.");
        }
        this.checkName = trimmedName;

        this.itemsScanned = validateNonNegative(itemsScanned, "itemsScanned");
        this.discrepanciesFound = validateNonNegative(discrepanciesFound, "discrepanciesFound");
        this.errorSummary = sanitizeErrorSummary(errorSummary);
    }

    private static int validateNonNegative(int count, String fieldName) {
        if (count < 0) {
            throw new OperationsValidationException(fieldName + " cannot be negative: " + count);
        }
        return count;
    }

    private static String sanitizeErrorSummary(String summary) {
        if (summary == null) {
            return null;
        }
        String trimmed = summary.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (codePointLength(trimmed) > MAX_ERROR_SUMMARY_LENGTH) {
            throw new OperationsValidationException("errorSummary exceeds maximum length of " + MAX_ERROR_SUMMARY_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static int codePointLength(String str) {
        return str.codePointCount(0, str.length());
    }

    public ReconciliationCheckExecutionId getCheckExecutionId() {
        return checkExecutionId;
    }

    public ReconciliationRunId getRunId() {
        return runId;
    }

    public String getCheckName() {
        return checkName;
    }

    public ReconciliationCheckStatus getStatus() {
        return status;
    }

    public int getItemsScanned() {
        return itemsScanned;
    }

    public int getDiscrepanciesFound() {
        return discrepanciesFound;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
