package com.crosspaynet.operations.application.check;

import com.crosspaynet.operations.domain.DiscrepancyId;
import com.crosspaynet.operations.domain.DiscrepancyKey;
import com.crosspaynet.operations.domain.DiscrepancyObservationId;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationDiscrepancy;
import com.crosspaynet.operations.domain.ReconciliationDiscrepancyObservation;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.infrastructure.persistence.OperationsMapper;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyObservationRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Encapsulates discrepancy deduplication, repeat-observation recording, and durable observation persistence.
 */
@Component
public class DiscrepancyRecorder {

    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final ReconciliationDiscrepancyObservationRepository observationRepository;
    private final OperationsMapper operationsMapper;

    public DiscrepancyRecorder(
            ReconciliationDiscrepancyRepository discrepancyRepository,
            ReconciliationDiscrepancyObservationRepository observationRepository,
            OperationsMapper operationsMapper
    ) {
        this.discrepancyRepository = Objects.requireNonNull(discrepancyRepository, "discrepancyRepository cannot be null.");
        this.observationRepository = Objects.requireNonNull(observationRepository, "observationRepository cannot be null.");
        this.operationsMapper = Objects.requireNonNull(operationsMapper, "operationsMapper cannot be null.");
    }

    /**
     * Records an observed discrepancy and its discrete run observation snapshot.
     *
     * <p>If the discrepancy already exists for the given (type, operationalEntityId) key,
     * updates the existing record with repeat observation semantics and appends a new observation snapshot.
     * If the discrepancy was already recorded during the current run, this method is idempotent and does
     * not double-count consecutive detections or duplicate observation rows.
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

            ReconciliationDiscrepancyObservation observation = ReconciliationDiscrepancyObservation.createLive(
                    DiscrepancyObservationId.generate(),
                    id,
                    runId,
                    type,
                    discrepancy.getStatus(),
                    summary,
                    detailsJson,
                    now
            );
            observationRepository.save(operationsMapper.toEntity(observation));
        } else {
            ReconciliationDiscrepancyEntity entity = existingOpt.get();

            // Idempotency guard: prevent duplicate observation and counter increments within the same run
            if (entity.getLatestRunId().equals(runId.getValue())
                    || observationRepository.existsByDiscrepancyIdAndRunId(entity.getDiscrepancyId(), runId.getValue())) {
                return;
            }

            ReconciliationDiscrepancy domain = operationsMapper.toDomain(entity);
            domain.recordRepeatObservation(runId, summary, detailsJson, now);
            operationsMapper.updateEntity(domain, entity);
            discrepancyRepository.save(entity);

            ReconciliationDiscrepancyObservation observation = ReconciliationDiscrepancyObservation.createLive(
                    DiscrepancyObservationId.generate(),
                    domain.getDiscrepancyId(),
                    runId,
                    type,
                    domain.getStatus(),
                    summary,
                    detailsJson,
                    now
            );
            observationRepository.save(operationsMapper.toEntity(observation));
        }
    }
}
