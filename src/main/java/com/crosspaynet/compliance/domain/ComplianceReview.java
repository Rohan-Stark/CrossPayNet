package com.crosspaynet.compliance.domain;

import com.crosspaynet.compliance.contracts.ComplianceConflictException;
import com.crosspaynet.compliance.contracts.ComplianceDecision;
import com.crosspaynet.compliance.contracts.ComplianceReviewId;
import com.crosspaynet.compliance.contracts.ComplianceReviewView;
import com.crosspaynet.compliance.contracts.ComplianceStatus;
import com.crosspaynet.compliance.contracts.ComplianceValidationException;
import com.crosspaynet.payment.contracts.PaymentId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure domain aggregate root managing compliance review lifecycle.
 */
public class ComplianceReview {

    private final ComplianceReviewId reviewId;
    private final PaymentId paymentId;
    private ComplianceStatus status;
    private int riskScore;
    private List<String> flagReasons;
    private String reviewerId;
    private ComplianceDecision decision;
    private String justification;
    private Instant decidedAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private ComplianceReview(
            ComplianceReviewId reviewId,
            PaymentId paymentId,
            ComplianceStatus status,
            int riskScore,
            List<String> flagReasons,
            String reviewerId,
            ComplianceDecision decision,
            String justification,
            Instant decidedAt,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId cannot be null.");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId cannot be null.");
        this.status = Objects.requireNonNull(status, "status cannot be null.");
        this.riskScore = Math.max(0, Math.min(100, riskScore));
        this.flagReasons = flagReasons != null ? new ArrayList<>(flagReasons) : new ArrayList<>();
        this.reviewerId = reviewerId;
        this.decision = decision;
        this.justification = justification;
        this.decidedAt = decidedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");
        this.version = version;
    }

    public static ComplianceReview createPending(
            ComplianceReviewId reviewId,
            PaymentId paymentId,
            int riskScore,
            List<String> flagReasons,
            Instant createdAt
    ) {
        if (flagReasons == null || flagReasons.isEmpty()) {
            throw new ComplianceValidationException("Pending review must have at least one flag reason.");
        }
        return new ComplianceReview(
                reviewId,
                paymentId,
                ComplianceStatus.PENDING,
                riskScore,
                flagReasons,
                null,
                null,
                null,
                null,
                createdAt,
                createdAt,
                0L
        );
    }

    public static ComplianceReview createAutoCleared(
            ComplianceReviewId reviewId,
            PaymentId paymentId,
            Instant createdAt
    ) {
        return new ComplianceReview(
                reviewId,
                paymentId,
                ComplianceStatus.CLEARED,
                0,
                Collections.emptyList(),
                "SYSTEM_AUTOMATED",
                ComplianceDecision.RELEASE,
                "Automated compliance screening cleared: no watchlist matches and amount below threshold.",
                createdAt,
                createdAt,
                createdAt,
                0L
        );
    }

    public static ComplianceReview restore(
            ComplianceReviewId reviewId,
            PaymentId paymentId,
            ComplianceStatus status,
            int riskScore,
            List<String> flagReasons,
            String reviewerId,
            ComplianceDecision decision,
            String justification,
            Instant decidedAt,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        return new ComplianceReview(
                reviewId,
                paymentId,
                status,
                riskScore,
                flagReasons,
                reviewerId,
                decision,
                justification,
                decidedAt,
                createdAt,
                updatedAt,
                version
        );
    }

    public void release(String reviewerId, String justification, Instant timestamp) {
        validateDecisionInputs(reviewerId, justification);

        if (status == ComplianceStatus.CLEARED) {
            if (this.decision == ComplianceDecision.RELEASE
                    && Objects.equals(this.reviewerId, reviewerId.trim())
                    && Objects.equals(this.justification, justification.trim())) {
                return; // Idempotent replay
            }
            throw new ComplianceConflictException("Review '" + reviewId + "' is already CLEARED with different parameters.");
        }
        if (status == ComplianceStatus.REJECTED) {
            throw new ComplianceConflictException("Review '" + reviewId + "' is already REJECTED; cannot release.");
        }

        this.status = ComplianceStatus.CLEARED;
        this.decision = ComplianceDecision.RELEASE;
        this.reviewerId = reviewerId.trim();
        this.justification = justification.trim();
        this.decidedAt = timestamp != null ? timestamp : Instant.now();
        this.updatedAt = this.decidedAt;
    }

    public void reject(String reviewerId, String justification, Instant timestamp) {
        validateDecisionInputs(reviewerId, justification);

        if (status == ComplianceStatus.REJECTED) {
            if (this.decision == ComplianceDecision.REJECT
                    && Objects.equals(this.reviewerId, reviewerId.trim())
                    && Objects.equals(this.justification, justification.trim())) {
                return; // Idempotent replay
            }
            throw new ComplianceConflictException("Review '" + reviewId + "' is already REJECTED with different parameters.");
        }
        if (status == ComplianceStatus.CLEARED) {
            throw new ComplianceConflictException("Review '" + reviewId + "' is already CLEARED; cannot reject.");
        }

        this.status = ComplianceStatus.REJECTED;
        this.decision = ComplianceDecision.REJECT;
        this.reviewerId = reviewerId.trim();
        this.justification = justification.trim();
        this.decidedAt = timestamp != null ? timestamp : Instant.now();
        this.updatedAt = this.decidedAt;
    }

    private void validateDecisionInputs(String reviewerId, String justification) {
        if (reviewerId == null || reviewerId.isBlank()) {
            throw new ComplianceValidationException("reviewerId cannot be blank.");
        }
        if (justification == null || justification.trim().length() < 3) {
            throw new ComplianceValidationException("Decision justification must be at least 3 characters.");
        }
    }

    public ComplianceReviewView toView() {
        return new ComplianceReviewView(
                reviewId,
                paymentId,
                status,
                riskScore,
                flagReasons,
                reviewerId,
                decision,
                justification,
                decidedAt,
                createdAt,
                updatedAt,
                version
        );
    }

    public ComplianceReviewId getReviewId() {
        return reviewId;
    }

    public PaymentId getPaymentId() {
        return paymentId;
    }

    public ComplianceStatus getStatus() {
        return status;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public List<String> getFlagReasons() {
        return Collections.unmodifiableList(flagReasons);
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public ComplianceDecision getDecision() {
        return decision;
    }

    public String getJustification() {
        return justification;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
