package com.crosspaynet.compliance;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.compliance.contracts.ComplianceDecision;
import com.crosspaynet.compliance.contracts.ComplianceReviewId;
import com.crosspaynet.compliance.contracts.ComplianceStatus;
import com.crosspaynet.compliance.domain.ComplianceReview;
import com.crosspaynet.compliance.infrastructure.persistence.ComplianceReviewEntity;
import com.crosspaynet.compliance.infrastructure.persistence.ComplianceReviewMapper;
import com.crosspaynet.compliance.infrastructure.persistence.ComplianceReviewRepository;
import com.crosspaynet.payment.contracts.PaymentId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class CompliancePersistenceIntegrationTest {

    @Autowired
    private ComplianceReviewRepository reviewRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldPersistAndRetrievePendingReviewWithFlags() {
        ComplianceReviewId reviewId = ComplianceReviewId.generate();
        PaymentId paymentId = PaymentId.generate();
        Instant now = Instant.now();

        ComplianceReview pending = ComplianceReview.createPending(
                reviewId,
                paymentId,
                75,
                List.of("SANCTION_WATCHLIST_NAME_MATCH", "HIGH_VALUE_TRANSACTION_THRESHOLD"),
                now
        );

        reviewRepository.save(ComplianceReviewMapper.toEntity(pending));

        Optional<ComplianceReviewEntity> loaded = reviewRepository.findByPaymentId(paymentId.getValue());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getStatus()).isEqualTo("PENDING");
        assertThat(loaded.get().getRiskScore()).isEqualTo(75);
        assertThat(loaded.get().getDecision()).isNull();

        ComplianceReview restored = ComplianceReviewMapper.toDomain(loaded.get());
        assertThat(restored.getReviewId()).isEqualTo(reviewId);
        assertThat(restored.getFlagReasons()).containsExactly(
                "SANCTION_WATCHLIST_NAME_MATCH",
                "HIGH_VALUE_TRANSACTION_THRESHOLD"
        );
    }

    @Test
    void shouldEnforceUniquePaymentIdConstraint() {
        PaymentId paymentId = PaymentId.generate();

        ComplianceReview review1 = ComplianceReview.createAutoCleared(ComplianceReviewId.generate(), paymentId, Instant.now());
        reviewRepository.saveAndFlush(ComplianceReviewMapper.toEntity(review1));

        ComplianceReview review2 = ComplianceReview.createAutoCleared(ComplianceReviewId.generate(), paymentId, Instant.now());
        assertThatThrownBy(() -> reviewRepository.saveAndFlush(ComplianceReviewMapper.toEntity(review2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldIncrementVersionOnOptimisticLockUpdate() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ComplianceReviewId reviewId = ComplianceReviewId.generate();
        PaymentId paymentId = PaymentId.generate();

        tx.executeWithoutResult(s -> {
            ComplianceReview pending = ComplianceReview.createPending(
                    reviewId,
                    paymentId,
                    40,
                    List.of("HIGH_VALUE_TRANSACTION_THRESHOLD"),
                    Instant.now()
            );
            reviewRepository.save(ComplianceReviewMapper.toEntity(pending));
        });

        tx.executeWithoutResult(s -> {
            ComplianceReviewEntity entity = reviewRepository.findById(reviewId.getValue()).orElseThrow();
            assertThat(entity.getVersion()).isEqualTo(0L);

            ComplianceReview domain = ComplianceReviewMapper.toDomain(entity);
            domain.release("OFFICER_TEST", "Clearance documentation confirmed.", Instant.now());
            ComplianceReviewMapper.updateEntity(domain, entity);
            reviewRepository.saveAndFlush(entity);
        });

        ComplianceReviewEntity updated = reviewRepository.findById(reviewId.getValue()).orElseThrow();
        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.getStatus()).isEqualTo("CLEARED");
        assertThat(updated.getDecision()).isEqualTo("RELEASE");
    }

    @Test
    void shouldEnforceDatabaseCheckConstraintOnInvalidStatus() {
        UUID reviewId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO compliance.compliance_review (
                    review_id, payment_id, status, risk_score, flag_reasons, created_at, updated_at
                ) VALUES (?, ?, 'INVALID_STATUS', 0, '', now(), now())
                """, reviewId, paymentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldEnforceDatabaseCheckConstraintOnClearedStatusWithoutJustification() {
        UUID reviewId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO compliance.compliance_review (
                    review_id, payment_id, status, risk_score, flag_reasons, reviewer_id, decision, justification, decided_at, created_at, updated_at
                ) VALUES (?, ?, 'CLEARED', 0, '', 'OFFICER', 'RELEASE', NULL, now(), now(), now())
                """, reviewId, paymentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
