package com.crosspaynet.compliance.infrastructure.persistence;

import com.crosspaynet.compliance.contracts.ComplianceDecision;
import com.crosspaynet.compliance.contracts.ComplianceReviewId;
import com.crosspaynet.compliance.contracts.ComplianceStatus;
import com.crosspaynet.compliance.domain.ComplianceReview;
import com.crosspaynet.payment.contracts.PaymentId;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public final class ComplianceReviewMapper {

    private static final String DELIMITER = ";";

    private ComplianceReviewMapper() {}

    public static ComplianceReview toDomain(ComplianceReviewEntity entity) {
        if (entity == null) {
            return null;
        }

        List<String> flagReasons = parseFlagReasons(entity.getFlagReasons());
        ComplianceDecision decision = entity.getDecision() != null
                ? ComplianceDecision.valueOf(entity.getDecision())
                : null;

        return ComplianceReview.restore(
                ComplianceReviewId.of(entity.getReviewId()),
                PaymentId.of(entity.getPaymentId()),
                ComplianceStatus.valueOf(entity.getStatus()),
                entity.getRiskScore(),
                flagReasons,
                entity.getReviewerId(),
                decision,
                entity.getJustification(),
                entity.getDecidedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }

    public static ComplianceReviewEntity toEntity(ComplianceReview domain) {
        if (domain == null) {
            return null;
        }

        return new ComplianceReviewEntity(
                domain.getReviewId().getValue(),
                domain.getVersion() == 0L ? null : domain.getVersion(),
                domain.getPaymentId().getValue(),
                domain.getStatus().name(),
                domain.getRiskScore(),
                serializeFlagReasons(domain.getFlagReasons()),
                domain.getReviewerId(),
                domain.getDecision() != null ? domain.getDecision().name() : null,
                domain.getJustification(),
                domain.getDecidedAt(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public static void updateEntity(ComplianceReview domain, ComplianceReviewEntity entity) {
        entity.setStatus(domain.getStatus().name());
        entity.setRiskScore(domain.getRiskScore());
        entity.setFlagReasons(serializeFlagReasons(domain.getFlagReasons()));
        entity.setReviewerId(domain.getReviewerId());
        entity.setDecision(domain.getDecision() != null ? domain.getDecision().name() : null);
        entity.setJustification(domain.getJustification());
        entity.setDecidedAt(domain.getDecidedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
    }

    public static String serializeFlagReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "";
        }
        return String.join(DELIMITER, reasons);
    }

    public static List<String> parseFlagReasons(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(DELIMITER))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
