package com.crosspaynet.compliance.contracts;

import java.util.Objects;

public record SubmitComplianceDecisionCommand(
        ComplianceReviewId reviewId,
        ComplianceDecision decision,
        String reviewerId,
        String justification
) {
    public SubmitComplianceDecisionCommand {
        Objects.requireNonNull(reviewId, "reviewId cannot be null.");
        Objects.requireNonNull(decision, "decision cannot be null.");
        if (reviewerId == null || reviewerId.isBlank()) {
            throw new ComplianceValidationException("reviewerId cannot be blank.");
        }
        if (justification == null || justification.trim().length() < 3) {
            throw new ComplianceValidationException("justification must be at least 3 characters.");
        }
        reviewerId = reviewerId.trim();
        justification = justification.trim();
    }
}
