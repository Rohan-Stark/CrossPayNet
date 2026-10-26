package com.crosspaynet.compliance.domain;

import com.crosspaynet.compliance.contracts.ComplianceConflictException;
import com.crosspaynet.compliance.contracts.ComplianceDecision;
import com.crosspaynet.compliance.contracts.ComplianceReviewId;
import com.crosspaynet.compliance.contracts.ComplianceStatus;
import com.crosspaynet.compliance.contracts.ComplianceValidationException;
import com.crosspaynet.payment.contracts.PaymentId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceReviewTest {

    @Test
    void shouldCreatePendingReviewWithValidFlagsAndScore() {
        ComplianceReviewId reviewId = ComplianceReviewId.generate();
        PaymentId paymentId = PaymentId.generate();
        Instant now = Instant.now();

        ComplianceReview review = ComplianceReview.createPending(
                reviewId,
                paymentId,
                50,
                List.of(SanctionsMatcher.FLAG_NAME_MATCH),
                now
        );

        assertThat(review.getReviewId()).isEqualTo(reviewId);
        assertThat(review.getPaymentId()).isEqualTo(paymentId);
        assertThat(review.getStatus()).isEqualTo(ComplianceStatus.PENDING);
        assertThat(review.getRiskScore()).isEqualTo(50);
        assertThat(review.getFlagReasons()).containsExactly(SanctionsMatcher.FLAG_NAME_MATCH);
        assertThat(review.getDecision()).isNull();
        assertThat(review.getReviewerId()).isNull();
        assertThat(review.getJustification()).isNull();
    }

    @Test
    void shouldCreateAutoClearedReviewWithStandardDefaults() {
        ComplianceReviewId reviewId = ComplianceReviewId.generate();
        PaymentId paymentId = PaymentId.generate();
        Instant now = Instant.now();

        ComplianceReview review = ComplianceReview.createAutoCleared(reviewId, paymentId, now);

        assertThat(review.getStatus()).isEqualTo(ComplianceStatus.CLEARED);
        assertThat(review.getDecision()).isEqualTo(ComplianceDecision.RELEASE);
        assertThat(review.getRiskScore()).isEqualTo(0);
        assertThat(review.getReviewerId()).isEqualTo("SYSTEM_AUTOMATED");
        assertThat(review.getJustification()).isNotBlank();
        assertThat(review.getDecidedAt()).isEqualTo(now);
    }

    @Test
    void shouldTransitionPendingToClearedOnRelease() {
        ComplianceReview review = ComplianceReview.createPending(
                ComplianceReviewId.generate(),
                PaymentId.generate(),
                30,
                List.of(SanctionsMatcher.FLAG_HIGH_VALUE),
                Instant.now()
        );

        Instant decidedAt = Instant.now();
        review.release("OFFICER_ALICE", "Verified customer source of wealth documentation.", decidedAt);

        assertThat(review.getStatus()).isEqualTo(ComplianceStatus.CLEARED);
        assertThat(review.getDecision()).isEqualTo(ComplianceDecision.RELEASE);
        assertThat(review.getReviewerId()).isEqualTo("OFFICER_ALICE");
        assertThat(review.getJustification()).isEqualTo("Verified customer source of wealth documentation.");
        assertThat(review.getDecidedAt()).isEqualTo(decidedAt);
    }

    @Test
    void shouldTransitionPendingToRejectedOnReject() {
        ComplianceReview review = ComplianceReview.createPending(
                ComplianceReviewId.generate(),
                PaymentId.generate(),
                90,
                List.of(SanctionsMatcher.FLAG_NAME_MATCH),
                Instant.now()
        );

        Instant decidedAt = Instant.now();
        review.reject("OFFICER_BOB", "Confirmed match against simulated sanctions watchlist.", decidedAt);

        assertThat(review.getStatus()).isEqualTo(ComplianceStatus.REJECTED);
        assertThat(review.getDecision()).isEqualTo(ComplianceDecision.REJECT);
        assertThat(review.getReviewerId()).isEqualTo("OFFICER_BOB");
        assertThat(review.getJustification()).isEqualTo("Confirmed match against simulated sanctions watchlist.");
        assertThat(review.getDecidedAt()).isEqualTo(decidedAt);
    }

    @Test
    void shouldAllowIdempotentReleaseReplayWithIdenticalInputs() {
        ComplianceReview review = ComplianceReview.createPending(
                ComplianceReviewId.generate(),
                PaymentId.generate(),
                30,
                List.of(SanctionsMatcher.FLAG_HIGH_VALUE),
                Instant.now()
        );

        review.release("OFFICER_ALICE", "Clearance documentation confirmed.", Instant.now());
        // Identical replay
        review.release("OFFICER_ALICE", "Clearance documentation confirmed.", Instant.now());

        assertThat(review.getStatus()).isEqualTo(ComplianceStatus.CLEARED);
    }

    @Test
    void shouldRejectConflictingDecisionReplay() {
        ComplianceReview review = ComplianceReview.createPending(
                ComplianceReviewId.generate(),
                PaymentId.generate(),
                30,
                List.of(SanctionsMatcher.FLAG_HIGH_VALUE),
                Instant.now()
        );

        review.release("OFFICER_ALICE", "First approval note.", Instant.now());

        // Conflicting release parameters
        assertThatThrownBy(() -> review.release("OFFICER_BOB", "Different justification note.", Instant.now()))
                .isInstanceOf(ComplianceConflictException.class);

        // Conflicting reject after release
        assertThatThrownBy(() -> review.reject("OFFICER_ALICE", "Attempted switch to reject.", Instant.now()))
                .isInstanceOf(ComplianceConflictException.class);
    }

    @Test
    void shouldEnforceDecisionJustificationMinimumLength() {
        ComplianceReview review = ComplianceReview.createPending(
                ComplianceReviewId.generate(),
                PaymentId.generate(),
                30,
                List.of(SanctionsMatcher.FLAG_HIGH_VALUE),
                Instant.now()
        );

        assertThatThrownBy(() -> review.release("OFFICER_ALICE", "ok", Instant.now()))
                .isInstanceOf(ComplianceValidationException.class);

        assertThatThrownBy(() -> review.release("OFFICER_ALICE", "   ", Instant.now()))
                .isInstanceOf(ComplianceValidationException.class);

        assertThatThrownBy(() -> review.release("", "Valid justification note.", Instant.now()))
                .isInstanceOf(ComplianceValidationException.class);
    }
}
