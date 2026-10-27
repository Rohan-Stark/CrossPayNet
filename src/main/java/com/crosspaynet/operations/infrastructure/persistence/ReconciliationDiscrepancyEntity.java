package com.crosspaynet.operations.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_discrepancy", schema = "operations")
public class ReconciliationDiscrepancyEntity {

    @Id
    @Column(name = "discrepancy_id", nullable = false)
    private UUID discrepancyId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "discrepancy_key", nullable = false, unique = true, length = 150)
    private String discrepancyKey;

    @Column(name = "first_run_id", nullable = false)
    private UUID firstRunId;

    @Column(name = "latest_run_id", nullable = false)
    private UUID latestRunId;

    @Column(name = "discrepancy_type", nullable = false, length = 50)
    private String discrepancyType;

    @Column(name = "operational_entity_id", nullable = false, length = 100)
    private String operationalEntityId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private String details;

    @Column(name = "first_detected_at", nullable = false)
    private Instant firstDetectedAt;

    @Column(name = "last_observed_at", nullable = false)
    private Instant lastObservedAt;

    @Column(name = "consecutive_detections", nullable = false)
    private Integer consecutiveDetections;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReconciliationDiscrepancyEntity() {}

    public ReconciliationDiscrepancyEntity(
            UUID discrepancyId,
            Long version,
            String discrepancyKey,
            UUID firstRunId,
            UUID latestRunId,
            String discrepancyType,
            String operationalEntityId,
            String status,
            String summary,
            String details,
            Instant firstDetectedAt,
            Instant lastObservedAt,
            Integer consecutiveDetections,
            Instant resolvedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.discrepancyId = discrepancyId;
        this.version = version;
        this.discrepancyKey = discrepancyKey;
        this.firstRunId = firstRunId;
        this.latestRunId = latestRunId;
        this.discrepancyType = discrepancyType;
        this.operationalEntityId = operationalEntityId;
        this.status = status;
        this.summary = summary;
        this.details = details;
        this.firstDetectedAt = firstDetectedAt;
        this.lastObservedAt = lastObservedAt;
        this.consecutiveDetections = consecutiveDetections;
        this.resolvedAt = resolvedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getDiscrepancyId() {
        return discrepancyId;
    }

    public Long getVersion() {
        return version;
    }

    public String getDiscrepancyKey() {
        return discrepancyKey;
    }

    public UUID getFirstRunId() {
        return firstRunId;
    }

    public UUID getLatestRunId() {
        return latestRunId;
    }

    public void setLatestRunId(UUID latestRunId) {
        this.latestRunId = latestRunId;
    }

    public String getDiscrepancyType() {
        return discrepancyType;
    }

    public String getOperationalEntityId() {
        return operationalEntityId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Instant getFirstDetectedAt() {
        return firstDetectedAt;
    }

    public Instant getLastObservedAt() {
        return lastObservedAt;
    }

    public void setLastObservedAt(Instant lastObservedAt) {
        this.lastObservedAt = lastObservedAt;
    }

    public Integer getConsecutiveDetections() {
        return consecutiveDetections;
    }

    public void setConsecutiveDetections(Integer consecutiveDetections) {
        this.consecutiveDetections = consecutiveDetections;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
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
