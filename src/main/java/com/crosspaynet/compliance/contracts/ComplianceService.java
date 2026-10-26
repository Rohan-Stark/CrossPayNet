package com.crosspaynet.compliance.contracts;

import com.crosspaynet.payment.contracts.PaymentId;

import java.util.Optional;

public interface ComplianceService {

    /**
     * Screens a payment instruction against simulated sanctions watchlists and high-value policy rules.
     * Idempotent: returns the existing review if already screened.
     */
    ComplianceReviewView screenPayment(ScreenPaymentCommand command);

    /**
     * Submits a manual compliance review decision ({@code RELEASE} or {@code REJECT}) by a compliance officer.
     * Idempotent for identical replays; rejects conflicting decisions.
     */
    ComplianceReviewView submitDecision(SubmitComplianceDecisionCommand command);

    /**
     * Finds the compliance review associated with a payment, if one exists.
     */
    Optional<ComplianceReviewView> findReviewByPaymentId(PaymentId paymentId);

    /**
     * Finds a compliance review by its unique review ID.
     */
    Optional<ComplianceReviewView> findReviewById(ComplianceReviewId reviewId);
}
