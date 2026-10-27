package com.crosspaynet.operations.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationCheckExecutionRepository extends JpaRepository<ReconciliationCheckExecutionEntity, UUID> {

    List<ReconciliationCheckExecutionEntity> findByRunId(UUID runId);
}
