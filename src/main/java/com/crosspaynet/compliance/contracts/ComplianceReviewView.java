package com.crosspaynet.compliance.contracts;

import com.crosspaynet.payment.contracts.PaymentId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ComplianceReviewView(
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
    public ComplianceReviewView {
        Objects.requireNonNull(reviewId, "reviewId cannot be null.");
        Objects.requireNonNull(paymentId, "paymentId cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        flagReasons = flagReasons != null ? List.copyOf(flagReasons) : List.of();
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");
    }
}
