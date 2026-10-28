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
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyObservationEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyObservationRepository;
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

import java.sql.Timestamp;
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
    private ReconciliationDiscrepancyObservationRepository observationRepository;

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
    void shouldPersistAndRetrieveObservationEntity() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ReconciliationRun run = ReconciliationRun.start(runId, "OPERATOR_ALICE", now);
        runRepository.saveAndFlush(operationsMapper.toEntity(run));

        DiscrepancyId discId = DiscrepancyId.generate();
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-OBS-1");
        ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.create(
                discId, key, runId, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-OBS-1",
                "Summary 1", "{\"data\":1}", now
        );
        discrepancyRepository.saveAndFlush(operationsMapper.toEntity(discrepancy));

        UUID obsId = UUID.randomUUID();
        ReconciliationDiscrepancyObservationEntity obsEntity = new ReconciliationDiscrepancyObservationEntity(
                obsId, discId.getValue(), runId.getValue(),
                DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW.name(),
                DiscrepancyStatus.OPEN.name(),
                "Summary 1", "{\"data\":1}", now, "LIVE", now
        );
        observationRepository.saveAndFlush(obsEntity);

        ReconciliationDiscrepancyObservationEntity loaded = observationRepository.findById(obsId).orElseThrow();
        assertThat(loaded.getDiscrepancyId()).isEqualTo(discId.getValue());
        assertThat(loaded.getRunId()).isEqualTo(runId.getValue());
        assertThat(loaded.getProvenance()).isEqualTo("LIVE");
        assertThat(loaded.getStatusAtObservation()).isEqualTo("OPEN");
    }

    @Test
    void shouldEnforceObservationForeignKeyConstraints() {
        UUID randomRunId = UUID.randomUUID();
        UUID randomDiscId = UUID.randomUUID();

        // Foreign key failure on non-existent discrepancy and run
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy_observation (
                    observation_id, discrepancy_id, run_id, discrepancy_type, status_at_observation,
                    summary, details, observed_at, provenance
                ) VALUES (?, ?, ?, 'STALE_IN_FLIGHT_WORKFLOW', 'OPEN', 'Summary', NULL, now(), 'LIVE')
                """, UUID.randomUUID(), randomDiscId, randomRunId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldEnforceObservationProvenanceCheckConstraint() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ReconciliationRunId validRunId = ReconciliationRunId.generate();
        ReconciliationRun run = ReconciliationRun.start(validRunId, "OPERATOR_ALICE", now);
        runRepository.saveAndFlush(operationsMapper.toEntity(run));

        DiscrepancyId validDiscId = DiscrepancyId.generate();
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-OBS-PROV");
        ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.create(
                validDiscId, key, validRunId, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-OBS-PROV",
                "Summary", null, now
        );
        discrepancyRepository.saveAndFlush(operationsMapper.toEntity(discrepancy));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy_observation (
                    observation_id, discrepancy_id, run_id, discrepancy_type, status_at_observation,
                    summary, details, observed_at, provenance
                ) VALUES (?, ?, ?, 'STALE_IN_FLIGHT_WORKFLOW', 'OPEN', 'Summary', NULL, now(), 'INVALID_PROVENANCE')
                """, UUID.randomUUID(), validDiscId.getValue(), validRunId.getValue()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldEnforceObservationStatusCheckConstraint() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ReconciliationRunId validRunId = ReconciliationRunId.generate();
        ReconciliationRun run = ReconciliationRun.start(validRunId, "OPERATOR_ALICE", now);
        runRepository.saveAndFlush(operationsMapper.toEntity(run));

        DiscrepancyId validDiscId = DiscrepancyId.generate();
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-OBS-STAT");
        ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.create(
                validDiscId, key, validRunId, DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-OBS-STAT",
                "Summary", null, now
        );
        discrepancyRepository.saveAndFlush(operationsMapper.toEntity(discrepancy));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy_observation (
                    observation_id, discrepancy_id, run_id, discrepancy_type, status_at_observation,
                    summary, details, observed_at, provenance
                ) VALUES (?, ?, ?, 'STALE_IN_FLIGHT_WORKFLOW', 'BOGUS_STATUS', 'Summary', NULL, now(), 'LIVE')
                """, UUID.randomUUID(), validDiscId.getValue(), validRunId.getValue()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldBackfillLegacyObservationsWithHonestProvenanceAndDeduplication() {
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

        ReconciliationRunId run1 = ReconciliationRunId.generate();
        ReconciliationRunId run2 = ReconciliationRunId.generate();
        runRepository.saveAndFlush(operationsMapper.toEntity(ReconciliationRun.start(run1, "OP", t0)));
        runRepository.saveAndFlush(operationsMapper.toEntity(ReconciliationRun.start(run2, "OP", t1)));

        // Legacy Row A: first_run_id == latest_run_id
        UUID discA = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy (
                    discrepancy_id, version, discrepancy_key, first_run_id, latest_run_id,
                    discrepancy_type, operational_entity_id, status, summary, details,
                    first_detected_at, last_observed_at, consecutive_detections
                ) VALUES (?, 0, 'KEY:LEGACY_A', ?, ?, 'STALE_IN_FLIGHT_WORKFLOW', 'ENT-A',
                          'OPEN', 'Legacy A summary', '{"v":1}'::jsonb, ?, ?, 1)
                """, discA, run1.getValue(), run1.getValue(), Timestamp.from(t0), Timestamp.from(t0));

        // Legacy Row B: first_run_id <> latest_run_id
        UUID discB = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy (
                    discrepancy_id, version, discrepancy_key, first_run_id, latest_run_id,
                    discrepancy_type, operational_entity_id, status, summary, details,
                    first_detected_at, last_observed_at, consecutive_detections
                ) VALUES (?, 0, 'KEY:LEGACY_B', ?, ?, 'RESERVATION_PAYMENT_MISMATCH', 'ENT-B',
                          'ACKNOWLEDGED', 'Legacy B summary', '{"v":2}'::jsonb, ?, ?, 2)
                """, discB, run1.getValue(), run2.getValue(), Timestamp.from(t0), Timestamp.from(t1));

        // Clean any existing observations for discA/discB to test backfill query explicitly
        jdbcTemplate.update("DELETE FROM operations.reconciliation_discrepancy_observation WHERE discrepancy_id IN (?, ?)", discA, discB);

        // Execute V17 backfill logic
        jdbcTemplate.execute("""
                INSERT INTO operations.reconciliation_discrepancy_observation (
                    observation_id, discrepancy_id, run_id, discrepancy_type, status_at_observation,
                    summary, details, observed_at, provenance, created_at
                )
                SELECT
                    gen_random_uuid(), d.discrepancy_id, d.first_run_id, d.discrepancy_type, 'OPEN',
                    d.summary,
                    CASE WHEN d.first_run_id = d.latest_run_id THEN d.details ELSE NULL END,
                    d.first_detected_at, 'LEGACY_RECONSTRUCTED', d.created_at
                FROM operations.reconciliation_discrepancy d
                WHERE d.discrepancy_id IN ('""" + discA + "', '" + discB + """
                ');

                INSERT INTO operations.reconciliation_discrepancy_observation (
                    observation_id, discrepancy_id, run_id, discrepancy_type, status_at_observation,
                    summary, details, observed_at, provenance, created_at
                )
                SELECT
                    gen_random_uuid(), d.discrepancy_id, d.latest_run_id, d.discrepancy_type, d.status,
                    d.summary, d.details, d.last_observed_at, 'LEGACY_RECONSTRUCTED', d.updated_at
                FROM operations.reconciliation_discrepancy d
                WHERE d.latest_run_id <> d.first_run_id
                  AND d.discrepancy_id IN ('""" + discA + "', '" + discB + """
                ')
                ON CONFLICT (discrepancy_id, run_id) DO NOTHING;
                """);

        // Verify Discrepancy A: exactly 1 observation backfilled
        List<ReconciliationDiscrepancyObservationEntity> obsA =
                observationRepository.findByDiscrepancyIdOrderByObservedAtAsc(discA);
        assertThat(obsA).hasSize(1);
        assertThat(obsA.getFirst().getRunId()).isEqualTo(run1.getValue());
        assertThat(obsA.getFirst().getProvenance()).isEqualTo("LEGACY_RECONSTRUCTED");
        assertThat(obsA.getFirst().getDetails()).isEqualTo("{\"v\": 1}");

        // Verify Discrepancy B: exactly 2 observations backfilled
        List<ReconciliationDiscrepancyObservationEntity> obsB =
                observationRepository.findByDiscrepancyIdOrderByObservedAtAsc(discB);
        assertThat(obsB).hasSize(2);

        // Observation 1: first run (honest: details IS NULL, status OPEN)
        assertThat(obsB.get(0).getRunId()).isEqualTo(run1.getValue());
        assertThat(obsB.get(0).getProvenance()).isEqualTo("LEGACY_RECONSTRUCTED");
        assertThat(obsB.get(0).getStatusAtObservation()).isEqualTo("OPEN");
        assertThat(obsB.get(0).getDetails()).isNull();

        // Observation 2: latest run (reconstructed with latest status and details)
        assertThat(obsB.get(1).getRunId()).isEqualTo(run2.getValue());
        assertThat(obsB.get(1).getProvenance()).isEqualTo("LEGACY_RECONSTRUCTED");
        assertThat(obsB.get(1).getStatusAtObservation()).isEqualTo("ACKNOWLEDGED");
        assertThat(obsB.get(1).getDetails()).isEqualTo("{\"v\": 2}");
    }
}
