package com.crosspaynet.operations.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_check_execution", schema = "operations")
public class ReconciliationCheckExecutionEntity {

    @Id
    @Column(name = "check_execution_id", nullable = false)
    private UUID checkExecutionId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "check_name", nullable = false, length = 100)
    private String checkName;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "items_scanned", nullable = false)
    private Integer itemsScanned;

    @Column(name = "discrepancies_found", nullable = false)
    private Integer discrepanciesFound;

    @Column(name = "error_summary", length = 1000)
    private String errorSummary;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationCheckExecutionEntity() {}

    public ReconciliationCheckExecutionEntity(
            UUID checkExecutionId,
            UUID runId,
            String checkName,
            String status,
            Integer itemsScanned,
            Integer discrepanciesFound,
            String errorSummary,
            Instant executedAt,
            Instant createdAt
    ) {
        this.checkExecutionId = checkExecutionId;
        this.runId = runId;
        this.checkName = checkName;
        this.status = status;
        this.itemsScanned = itemsScanned;
        this.discrepanciesFound = discrepanciesFound;
        this.errorSummary = errorSummary;
        this.executedAt = executedAt;
        this.createdAt = createdAt;
    }

    public UUID getCheckExecutionId() {
        return checkExecutionId;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getCheckName() {
        return checkName;
    }

    public String getStatus() {
        return status;
    }

    public Integer getItemsScanned() {
        return itemsScanned;
    }

    public Integer getDiscrepanciesFound() {
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
