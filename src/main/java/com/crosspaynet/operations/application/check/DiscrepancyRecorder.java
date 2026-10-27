package com.crosspaynet.operations.application.check;

import com.crosspaynet.operations.domain.DiscrepancyId;
import com.crosspaynet.operations.domain.DiscrepancyKey;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationDiscrepancy;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.infrastructure.persistence.OperationsMapper;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Encapsulates discrepancy deduplication, repeat-observation recording, and persistence.
 */
@Component
public class DiscrepancyRecorder {

    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final OperationsMapper operationsMapper;

    public DiscrepancyRecorder(
            ReconciliationDiscrepancyRepository discrepancyRepository,
            OperationsMapper operationsMapper
    ) {
        this.discrepancyRepository = Objects.requireNonNull(discrepancyRepository, "discrepancyRepository cannot be null.");
        this.operationsMapper = Objects.requireNonNull(operationsMapper, "operationsMapper cannot be null.");
    }

    /**
     * Records an observed discrepancy.
     * If the discrepancy already exists for the given (type, operationalEntityId) key,
     * updates the existing record with repeat observation semantics; otherwise creates a new one.
     */
    @Transactional
    public void recordDiscrepancy(
            ReconciliationRunId runId,
            DiscrepancyType type,
            String operationalEntityId,
            String summary,
            String detailsJson,
            Instant observedAt
    ) {
        Objects.requireNonNull(runId, "runId cannot be null.");
        Objects.requireNonNull(type, "type cannot be null.");
        Objects.requireNonNull(operationalEntityId, "operationalEntityId cannot be null.");
        Objects.requireNonNull(summary, "summary cannot be null.");
        Instant now = observedAt != null ? observedAt : Instant.now();

        DiscrepancyKey key = DiscrepancyKey.of(type, operationalEntityId);
        Optional<ReconciliationDiscrepancyEntity> existingOpt = discrepancyRepository.findByDiscrepancyKey(key.getValue());

        if (existingOpt.isEmpty()) {
            DiscrepancyId id = DiscrepancyId.generate();
            ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.create(
                    id,
                    key,
                    runId,
                    type,
                    operationalEntityId,
                    summary,
                    detailsJson,
                    now
            );
            discrepancyRepository.save(operationsMapper.toEntity(discrepancy));
        } else {
            ReconciliationDiscrepancyEntity entity = existingOpt.get();
            ReconciliationDiscrepancy domain = operationsMapper.toDomain(entity);
            domain.recordRepeatObservation(runId, summary, detailsJson, now);
            operationsMapper.updateEntity(domain, entity);
            discrepancyRepository.save(entity);
        }
    }
}
