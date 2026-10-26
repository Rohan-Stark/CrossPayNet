package com.crosspaynet.compliance.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "compliance_review", schema = "compliance")
public class ComplianceReviewEntity {

    @Id
    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "risk_score", nullable = false)
    private Integer riskScore;

    @Column(name = "flag_reasons", nullable = false, length = 1000)
    private String flagReasons;

    @Column(name = "reviewer_id", length = 100)
    private String reviewerId;

    @Column(name = "decision", length = 16)
    private String decision;

    @Column(name = "justification", length = 1000)
    private String justification;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ComplianceReviewEntity() {}

    public ComplianceReviewEntity(
            UUID reviewId,
            Long version,
            UUID paymentId,
            String status,
            Integer riskScore,
            String flagReasons,
            String reviewerId,
            String decision,
            String justification,
            Instant decidedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.reviewId = reviewId;
        this.version = version;
        this.paymentId = paymentId;
        this.status = status;
        this.riskScore = riskScore;
        this.flagReasons = flagReasons != null ? flagReasons : "";
        this.reviewerId = reviewerId;
        this.decision = decision;
        this.justification = justification;
        this.decidedAt = decidedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public Long getVersion() {
        return version;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public String getFlagReasons() {
        return flagReasons;
    }

    public void setFlagReasons(String flagReasons) {
        this.flagReasons = flagReasons;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(String reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getJustification() {
        return justification;
    }

    public void setJustification(String justification) {
        this.justification = justification;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
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
