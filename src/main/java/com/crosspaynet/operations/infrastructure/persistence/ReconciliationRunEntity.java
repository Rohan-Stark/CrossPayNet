package com.crosspaynet.operations.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_run", schema = "operations")
public class ReconciliationRunEntity {

    @Id
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "checks_evaluated", nullable = false)
    private Integer checksEvaluated;

    @Column(name = "discrepancy_count", nullable = false)
    private Integer discrepancyCount;

    @Column(name = "triggered_by", nullable = false, length = 100)
    private String triggeredBy;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReconciliationRunEntity() {}

    public ReconciliationRunEntity(
            UUID runId,
            Long version,
            String status,
            Instant startedAt,
            Instant completedAt,
            Integer checksEvaluated,
            Integer discrepancyCount,
            String triggeredBy,
            String failureReason,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.runId = runId;
        this.version = version;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.checksEvaluated = checksEvaluated;
        this.discrepancyCount = discrepancyCount;
        this.triggeredBy = triggeredBy;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getRunId() {
        return runId;
    }

    public Long getVersion() {
        return version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getChecksEvaluated() {
        return checksEvaluated;
    }

    public void setChecksEvaluated(Integer checksEvaluated) {
        this.checksEvaluated = checksEvaluated;
    }

    public Integer getDiscrepancyCount() {
        return discrepancyCount;
    }

    public void setDiscrepancyCount(Integer discrepancyCount) {
        this.discrepancyCount = discrepancyCount;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
