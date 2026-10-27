package com.crosspaynet.operations.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRunEntity, UUID> {

    List<ReconciliationRunEntity> findByStatus(String status);

    boolean existsByStatus(String status);

    java.util.Optional<ReconciliationRunEntity> findTopByOrderByStartedAtDesc();
}
