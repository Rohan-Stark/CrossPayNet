package com.crosspaynet.operations.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_discrepancy_observation", schema = "operations")
public class ReconciliationDiscrepancyObservationEntity {

    @Id
    @Column(name = "observation_id", nullable = false)
    private UUID observationId;

    @Column(name = "discrepancy_id", nullable = false)
    private UUID discrepancyId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "discrepancy_type", nullable = false, length = 50)
    private String discrepancyType;

    @Column(name = "status_at_observation", nullable = false, length = 30)
    private String statusAtObservation;

    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private String details;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "provenance", nullable = false, length = 30)
    private String provenance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationDiscrepancyObservationEntity() {}

    public ReconciliationDiscrepancyObservationEntity(
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
        this.observationId = observationId;
        this.discrepancyId = discrepancyId;
        this.runId = runId;
        this.discrepancyType = discrepancyType;
        this.statusAtObservation = statusAtObservation;
        this.summary = summary;
        this.details = details;
        this.observedAt = observedAt;
        this.provenance = provenance;
        this.createdAt = createdAt;
    }

    public UUID getObservationId() {
        return observationId;
    }

    public UUID getDiscrepancyId() {
        return discrepancyId;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getDiscrepancyType() {
        return discrepancyType;
    }

    public String getStatusAtObservation() {
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

    public String getProvenance() {
        return provenance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
