package com.crosspaynet.compliance.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ComplianceReviewRepository extends JpaRepository<ComplianceReviewEntity, UUID> {

    Optional<ComplianceReviewEntity> findByPaymentId(UUID paymentId);

    @Modifying
    @Query(value = """
            INSERT INTO compliance.compliance_review (
                review_id,
                version,
                payment_id,
                status,
                risk_score,
                flag_reasons,
                reviewer_id,
                decision,
                justification,
                decided_at,
                created_at,
                updated_at
            ) VALUES (
                :reviewId,
                0,
                :paymentId,
                :status,
                :riskScore,
                :flagReasons,
                :reviewerId,
                :decision,
                :justification,
                :decidedAt,
                :createdAt,
                :updatedAt
            ) ON CONFLICT (payment_id) DO NOTHING
            """, nativeQuery = true)
    int insertClaimOnConflictDoNothing(
            @Param("reviewId") UUID reviewId,
            @Param("paymentId") UUID paymentId,
            @Param("status") String status,
            @Param("riskScore") Integer riskScore,
            @Param("flagReasons") String flagReasons,
            @Param("reviewerId") String reviewerId,
            @Param("decision") String decision,
            @Param("justification") String justification,
            @Param("decidedAt") Instant decidedAt,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );
}
