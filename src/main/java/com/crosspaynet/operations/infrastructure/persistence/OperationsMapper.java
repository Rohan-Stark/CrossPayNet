package com.crosspaynet.operations.infrastructure.persistence;

import com.crosspaynet.operations.contracts.ReconciliationCheckExecutionView;
import com.crosspaynet.operations.contracts.ReconciliationDiscrepancyView;
import com.crosspaynet.operations.contracts.ReconciliationRunView;
import com.crosspaynet.operations.domain.DiscrepancyId;
import com.crosspaynet.operations.domain.DiscrepancyKey;
import com.crosspaynet.operations.domain.DiscrepancyStatus;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationCheckExecution;
import com.crosspaynet.operations.domain.ReconciliationCheckExecutionId;
import com.crosspaynet.operations.domain.ReconciliationCheckStatus;
import com.crosspaynet.operations.domain.ReconciliationDiscrepancy;
import com.crosspaynet.operations.domain.ReconciliationRun;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.domain.ReconciliationRunStatus;
import org.springframework.stereotype.Component;

@Component
public class OperationsMapper {

    public ReconciliationRunEntity toEntity(ReconciliationRun domain) {
        if (domain == null) return null;
        return new ReconciliationRunEntity(
                domain.getRunId().getValue(),
                domain.getVersion() == 0L ? null : domain.getVersion(),
                domain.getStatus().name(),
                domain.getStartedAt(),
                domain.getCompletedAt(),
                domain.getChecksEvaluated(),
                domain.getDiscrepancyCount(),
                domain.getTriggeredBy(),
                domain.getFailureReason(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public ReconciliationRun toDomain(ReconciliationRunEntity entity) {
        if (entity == null) return null;
        return new ReconciliationRun(
                ReconciliationRunId.of(entity.getRunId()),
                entity.getVersion(),
                ReconciliationRunStatus.valueOf(entity.getStatus()),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getChecksEvaluated(),
                entity.getDiscrepancyCount(),
                entity.getTriggeredBy(),
                entity.getFailureReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public void updateEntity(ReconciliationRun domain, ReconciliationRunEntity entity) {
        entity.setStatus(domain.getStatus().name());
        entity.setCompletedAt(domain.getCompletedAt());
        entity.setChecksEvaluated(domain.getChecksEvaluated());
        entity.setDiscrepancyCount(domain.getDiscrepancyCount());
        entity.setFailureReason(domain.getFailureReason());
        entity.setUpdatedAt(domain.getUpdatedAt());
    }

    public ReconciliationCheckExecutionEntity toEntity(ReconciliationCheckExecution domain) {
        if (domain == null) return null;
        return new ReconciliationCheckExecutionEntity(
                domain.getCheckExecutionId().getValue(),
                domain.getRunId().getValue(),
                domain.getCheckName(),
                domain.getStatus().name(),
                domain.getItemsScanned(),
                domain.getDiscrepanciesFound(),
                domain.getErrorSummary(),
                domain.getExecutedAt(),
                domain.getCreatedAt()
        );
    }

    public ReconciliationCheckExecution toDomain(ReconciliationCheckExecutionEntity entity) {
        if (entity == null) return null;
        return new ReconciliationCheckExecution(
                ReconciliationCheckExecutionId.of(entity.getCheckExecutionId()),
                ReconciliationRunId.of(entity.getRunId()),
                entity.getCheckName(),
                ReconciliationCheckStatus.valueOf(entity.getStatus()),
                entity.getItemsScanned(),
                entity.getDiscrepanciesFound(),
                entity.getErrorSummary(),
                entity.getExecutedAt(),
                entity.getCreatedAt()
        );
    }

    public ReconciliationDiscrepancyEntity toEntity(ReconciliationDiscrepancy domain) {
        if (domain == null) return null;
        return new ReconciliationDiscrepancyEntity(
                domain.getDiscrepancyId().getValue(),
                domain.getVersion() == 0L ? null : domain.getVersion(),
                domain.getDiscrepancyKey().getValue(),
                domain.getFirstRunId().getValue(),
                domain.getLatestRunId().getValue(),
                domain.getDiscrepancyType().name(),
                domain.getOperationalEntityId(),
                domain.getStatus().name(),
                domain.getSummary(),
                domain.getDetails(),
                domain.getFirstDetectedAt(),
                domain.getLastObservedAt(),
                domain.getConsecutiveDetections(),
                domain.getResolvedAt(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public ReconciliationDiscrepancy toDomain(ReconciliationDiscrepancyEntity entity) {
        if (entity == null) return null;
        return new ReconciliationDiscrepancy(
                DiscrepancyId.of(entity.getDiscrepancyId()),
                entity.getVersion(),
                DiscrepancyKey.of(entity.getDiscrepancyKey()),
                ReconciliationRunId.of(entity.getFirstRunId()),
                ReconciliationRunId.of(entity.getLatestRunId()),
                DiscrepancyType.valueOf(entity.getDiscrepancyType()),
                entity.getOperationalEntityId(),
                DiscrepancyStatus.valueOf(entity.getStatus()),
                entity.getSummary(),
                entity.getDetails(),
                entity.getFirstDetectedAt(),
                entity.getLastObservedAt(),
                entity.getConsecutiveDetections(),
                entity.getResolvedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public void updateEntity(ReconciliationDiscrepancy domain, ReconciliationDiscrepancyEntity entity) {
        entity.setLatestRunId(domain.getLatestRunId().getValue());
        entity.setStatus(domain.getStatus().name());
        entity.setSummary(domain.getSummary());
        entity.setDetails(domain.getDetails());
        entity.setLastObservedAt(domain.getLastObservedAt());
        entity.setConsecutiveDetections(domain.getConsecutiveDetections());
        entity.setResolvedAt(domain.getResolvedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
    }

    public ReconciliationRunView toView(ReconciliationRun domain) {
        if (domain == null) return null;
        return new ReconciliationRunView(
                domain.getRunId(),
                domain.getStatus(),
                domain.getStartedAt(),
                domain.getCompletedAt(),
                domain.getChecksEvaluated(),
                domain.getDiscrepancyCount(),
                domain.getTriggeredBy(),
                domain.getFailureReason(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public ReconciliationRunView toView(ReconciliationRunEntity entity) {
        if (entity == null) return null;
        return toView(toDomain(entity));
    }

    public ReconciliationCheckExecutionView toView(ReconciliationCheckExecution domain) {
        if (domain == null) return null;
        return new ReconciliationCheckExecutionView(
                domain.getCheckExecutionId(),
                domain.getRunId(),
                domain.getCheckName(),
                domain.getStatus(),
                domain.getItemsScanned(),
                domain.getDiscrepanciesFound(),
                domain.getErrorSummary(),
                domain.getExecutedAt()
        );
    }

    public ReconciliationCheckExecutionView toView(ReconciliationCheckExecutionEntity entity) {
        if (entity == null) return null;
        return toView(toDomain(entity));
    }

    public ReconciliationDiscrepancyView toView(ReconciliationDiscrepancy domain) {
        if (domain == null) return null;
        return new ReconciliationDiscrepancyView(
                domain.getDiscrepancyId(),
                domain.getDiscrepancyKey(),
                domain.getFirstRunId(),
                domain.getLatestRunId(),
                domain.getDiscrepancyType(),
                domain.getOperationalEntityId(),
                domain.getStatus(),
                domain.getSummary(),
                domain.getDetails(),
                domain.getFirstDetectedAt(),
                domain.getLastObservedAt(),
                domain.getConsecutiveDetections(),
                domain.getResolvedAt(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public ReconciliationDiscrepancyView toView(ReconciliationDiscrepancyEntity entity) {
        if (entity == null) return null;
        return toView(toDomain(entity));
    }
}
