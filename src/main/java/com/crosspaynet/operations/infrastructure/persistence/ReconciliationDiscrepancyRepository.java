package com.crosspaynet.operations.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationDiscrepancyRepository extends JpaRepository<ReconciliationDiscrepancyEntity, UUID> {

    Optional<ReconciliationDiscrepancyEntity> findByDiscrepancyKey(String discrepancyKey);

    List<ReconciliationDiscrepancyEntity> findByStatus(String status);

    List<ReconciliationDiscrepancyEntity> findByDiscrepancyType(String discrepancyType);

    List<ReconciliationDiscrepancyEntity> findByLatestRunId(UUID latestRunId);
}
