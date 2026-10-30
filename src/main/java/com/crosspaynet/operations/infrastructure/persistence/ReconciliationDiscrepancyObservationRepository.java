package com.crosspaynet.operations.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationDiscrepancyObservationRepository extends JpaRepository<ReconciliationDiscrepancyObservationEntity, UUID> {

    boolean existsByDiscrepancyIdAndRunId(UUID discrepancyId, UUID runId);

    List<ReconciliationDiscrepancyObservationEntity> findByDiscrepancyIdOrderByObservedAtAsc(UUID discrepancyId);

    Page<ReconciliationDiscrepancyObservationEntity> findByDiscrepancyId(UUID discrepancyId, Pageable pageable);

    List<ReconciliationDiscrepancyObservationEntity> findByRunId(UUID runId);
}
