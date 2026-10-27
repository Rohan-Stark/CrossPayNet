package com.crosspaynet.operations;

import com.crosspaynet.PostgresTestContainerConfig;
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
import com.crosspaynet.operations.infrastructure.persistence.OperationsMapper;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationCheckExecutionEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationCheckExecutionRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, OperationsMapper.class})
class OperationsPersistenceIntegrationTest {

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private ReconciliationCheckExecutionRepository checkExecutionRepository;

    @Autowired
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired
    private OperationsMapper operationsMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldPersistAndRetrieveReconciliationRunAndCheckExecutions() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant startedAt = Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        ReconciliationRun run = ReconciliationRun.start(runId, "OPERATOR_BOB", startedAt);
        runRepository.saveAndFlush(operationsMapper.toEntity(run));

        Optional<ReconciliationRunEntity> loadedRunOpt = runRepository.findById(runId.getValue());
        assertThat(loadedRunOpt).isPresent();
        ReconciliationRunEntity runEntity = loadedRunOpt.get();
        assertThat(runEntity.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(runEntity.getTriggeredBy()).isEqualTo("OPERATOR_BOB");
        assertThat(runEntity.getChecksEvaluated()).isZero();

        // Add check execution linked by foreign key
        ReconciliationCheckExecutionId checkId = ReconciliationCheckExecutionId.generate();
        ReconciliationCheckExecution check = ReconciliationCheckExecution.create(
                checkId,
                runId,
                "WORKFLOW_STALENESS_SCAN",
                ReconciliationCheckStatus.COMPLETED,
                25,
                2,
                null,
                startedAt.plus(1, ChronoUnit.MINUTES)
        );
        checkExecutionRepository.saveAndFlush(operationsMapper.toEntity(check));

        List<ReconciliationCheckExecutionEntity> checks = checkExecutionRepository.findByRunId(runId.getValue());
        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).getCheckName()).isEqualTo("WORKFLOW_STALENESS_SCAN");
        assertThat(checks.get(0).getItemsScanned()).isEqualTo(25);
        assertThat(checks.get(0).getDiscrepanciesFound()).isEqualTo(2);

        // Complete the run
        run.complete(1, 2, startedAt.plus(2, ChronoUnit.MINUTES));
        operationsMapper.updateEntity(run, runEntity);
        runRepository.saveAndFlush(runEntity);

        ReconciliationRunEntity completedRun = runRepository.findById(runId.getValue()).orElseThrow();
        assertThat(completedRun.getStatus()).isEqualTo("COMPLETED");
        assertThat(completedRun.getChecksEvaluated()).isEqualTo(1);
        assertThat(completedRun.getDiscrepancyCount()).isEqualTo(2);
        assertThat(completedRun.getCompletedAt()).isNotNull();
    }

    @Test
    void shouldPersistAndQueryDiscrepancyWithJsonbDetails() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ReconciliationRun run = ReconciliationRun.start(runId, "AUTO_SCHEDULER", now);
        runRepository.saveAndFlush(operationsMapper.toEntity(run));

        DiscrepancyId discId = DiscrepancyId.generate();
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.PAYMENT_LEDGER_BREAK, "PAY-UUID-999");
        String jsonDetails = "{\"paymentId\":\"PAY-UUID-999\",\"amount\":500.00,\"currency\":\"USD\"}";

        ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.create(
                discId,
                key,
                runId,
                DiscrepancyType.PAYMENT_LEDGER_BREAK,
                "PAY-UUID-999",
                "Settled payment has no matching ledger entry",
                jsonDetails,
                now
        );
        discrepancyRepository.saveAndFlush(operationsMapper.toEntity(discrepancy));

        Optional<ReconciliationDiscrepancyEntity> loadedOpt = discrepancyRepository.findByDiscrepancyKey(key.getValue());
        assertThat(loadedOpt).isPresent();
        ReconciliationDiscrepancyEntity entity = loadedOpt.get();
        assertThat(entity.getDiscrepancyKey()).isEqualTo(key.getValue());
        assertThat(entity.getStatus()).isEqualTo("OPEN");
        assertThat(entity.getConsecutiveDetections()).isEqualTo(1);
        assertThat(entity.getDetails()).contains("\"currency\":\"USD\"");

        // Query jsonb using native postgres operator
        String extractedCurrency = jdbcTemplate.queryForObject(
                "SELECT details->>'currency' FROM operations.reconciliation_discrepancy WHERE discrepancy_id = ?",
                String.class,
                discId.getValue()
        );
        assertThat(extractedCurrency).isEqualTo("USD");
    }

    @Test
    void shouldEnforceUniqueDiscrepancyKeyConstraint() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        runRepository.saveAndFlush(operationsMapper.toEntity(ReconciliationRun.start(runId, "OPERATOR", now)));

        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.RESERVATION_PAYMENT_MISMATCH, "RES-DUPLICATE");

        ReconciliationDiscrepancy d1 = ReconciliationDiscrepancy.create(
                DiscrepancyId.generate(), key, runId, DiscrepancyType.RESERVATION_PAYMENT_MISMATCH,
                "RES-DUPLICATE", "First observation", null, now
        );
        discrepancyRepository.saveAndFlush(operationsMapper.toEntity(d1));

        ReconciliationDiscrepancy d2 = ReconciliationDiscrepancy.create(
                DiscrepancyId.generate(), key, runId, DiscrepancyType.RESERVATION_PAYMENT_MISMATCH,
                "RES-DUPLICATE", "Duplicate key observation", null, now
        );

        assertThatThrownBy(() -> discrepancyRepository.saveAndFlush(operationsMapper.toEntity(d2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldIncrementVersionOnOptimisticLockUpdate() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ReconciliationRunId run1 = ReconciliationRunId.generate();
        ReconciliationRunId run2 = ReconciliationRunId.generate();
        DiscrepancyId discId = DiscrepancyId.generate();
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-OPT-LOCK");
        Instant t1 = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        tx.executeWithoutResult(s -> {
            runRepository.save(operationsMapper.toEntity(ReconciliationRun.start(run1, "OP1", t1)));
            runRepository.save(operationsMapper.toEntity(ReconciliationRun.start(run2, "OP2", t2)));

            ReconciliationDiscrepancy disc = ReconciliationDiscrepancy.create(
                    discId, key, run1, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW,
                    "PAY-OPT-LOCK", "Stale payment", null, t1
            );
            discrepancyRepository.save(operationsMapper.toEntity(disc));
        });

        tx.executeWithoutResult(s -> {
            ReconciliationDiscrepancyEntity entity = discrepancyRepository.findById(discId.getValue()).orElseThrow();
            assertThat(entity.getVersion()).isZero();

            ReconciliationDiscrepancy domain = operationsMapper.toDomain(entity);
            domain.recordRepeatObservation(run2, "Still stale in run 2", null, t2);
            operationsMapper.updateEntity(domain, entity);
            discrepancyRepository.saveAndFlush(entity);
        });

        ReconciliationDiscrepancyEntity updated = discrepancyRepository.findById(discId.getValue()).orElseThrow();
        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.getConsecutiveDetections()).isEqualTo(2);
        assertThat(updated.getLatestRunId()).isEqualTo(run2.getValue());
    }

    @Test
    void shouldEnforceDatabaseCheckConstraintOnInvalidRunStatus() {
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_run (
                    run_id, version, status, started_at, checks_evaluated, discrepancy_count, triggered_by
                ) VALUES (?, 0, 'INVALID_RUN_STATUS', now(), 0, 0, 'OP')
                """, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldEnforceDatabaseCheckConstraintOnNegativeChecksEvaluated() {
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_run (
                    run_id, version, status, started_at, checks_evaluated, discrepancy_count, triggered_by
                ) VALUES (?, 0, 'IN_PROGRESS', now(), -5, 0, 'OP')
                """, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldEnforceDatabaseCheckConstraintOnIncoherentResolutionTimestamp() {
        UUID validRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_run (
                    run_id, version, status, started_at, checks_evaluated, discrepancy_count, triggered_by
                ) VALUES (?, 0, 'IN_PROGRESS', now(), 0, 0, 'OP')
                """, validRunId);

        UUID discId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy (
                    discrepancy_id, version, discrepancy_key, first_run_id, latest_run_id,
                    discrepancy_type, operational_entity_id, status, summary,
                    first_detected_at, last_observed_at, consecutive_detections, resolved_at
                ) VALUES (?, 0, 'KEY:1', ?, ?, 'PAYMENT_LEDGER_BREAK', 'ENT-1',
                          'RESOLVED_EXTERNALLY', 'Resolved without timestamp', now(), now(), 1, NULL)
                """, discId, validRunId, validRunId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
