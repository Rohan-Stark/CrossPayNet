package com.crosspaynet.operations.application.check;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.operations.domain.DiscrepancyId;
import com.crosspaynet.operations.domain.DiscrepancyKey;
import com.crosspaynet.operations.domain.DiscrepancyObservationId;
import com.crosspaynet.operations.domain.DiscrepancyStatus;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ObservationProvenance;
import com.crosspaynet.operations.domain.ReconciliationDiscrepancy;
import com.crosspaynet.operations.domain.ReconciliationDiscrepancyObservation;
import com.crosspaynet.operations.domain.ReconciliationRun;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.infrastructure.persistence.OperationsMapper;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyObservationEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyObservationRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PostgresTestContainerConfig.class, OperationsMapper.class, DiscrepancyRecorder.class})
class DiscrepancyRecorderIntegrationTest {

    @Autowired
    private DiscrepancyRecorder discrepancyRecorder;

    @Autowired
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @MockitoSpyBean
    private ReconciliationDiscrepancyObservationRepository observationRepository;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private OperationsMapper operationsMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        Mockito.reset(observationRepository);
        jdbcTemplate.execute("DELETE FROM operations.reconciliation_discrepancy_observation");
        jdbcTemplate.execute("DELETE FROM operations.reconciliation_discrepancy");
        jdbcTemplate.execute("DELETE FROM operations.reconciliation_check_execution");
        jdbcTemplate.execute("DELETE FROM operations.reconciliation_run");
    }

    private ReconciliationRunId createRun(String operator) {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ReconciliationRun run = ReconciliationRun.start(runId, operator, now);
        run.complete(4, 0, now.plus(1, ChronoUnit.SECONDS));
        runRepository.saveAndFlush(operationsMapper.toEntity(run));
        return runId;
    }

    @Test
    @DisplayName("Recording a new discrepancy creates aggregate and exactly one LIVE observation")
    void recordNewDiscrepancyCreatesAggregateAndObservation() {
        ReconciliationRunId runId = createRun("OPS_1");
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        discrepancyRecorder.recordDiscrepancy(
                runId,
                DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW,
                "PAY-001",
                "Payment PAY-001 is stale",
                "{\"ageMinutes\":45}",
                observedAt
        );

        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-001");
        ReconciliationDiscrepancyEntity entity = discrepancyRepository.findByDiscrepancyKey(key.getValue()).orElseThrow();

        assertThat(entity.getStatus()).isEqualTo("OPEN");
        assertThat(entity.getConsecutiveDetections()).isEqualTo(1);
        assertThat(entity.getFirstRunId()).isEqualTo(runId.getValue());
        assertThat(entity.getLatestRunId()).isEqualTo(runId.getValue());

        List<ReconciliationDiscrepancyObservationEntity> observations =
                observationRepository.findByDiscrepancyIdOrderByObservedAtAsc(entity.getDiscrepancyId());
        assertThat(observations).hasSize(1);
        ReconciliationDiscrepancyObservationEntity obs = observations.getFirst();
        assertThat(obs.getRunId()).isEqualTo(runId.getValue());
        assertThat(obs.getProvenance()).isEqualTo("LIVE");
        assertThat(obs.getStatusAtObservation()).isEqualTo("OPEN");
        assertThat(obs.getSummary()).isEqualTo("Payment PAY-001 is stale");
        assertThat(obs.getDetails()).contains("\"ageMinutes\"", "45");
    }

    @Test
    @DisplayName("Repeat detection in a second run increments consecutive detections and appends second observation")
    void repeatDetectionInNewRunAppendsObservation() {
        ReconciliationRunId run1 = createRun("OPS_1");
        ReconciliationRunId run2 = createRun("OPS_2");
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().truncatedTo(ChronoUnit.MICROS);

        discrepancyRecorder.recordDiscrepancy(
                run1,
                DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW,
                "PAY-002",
                "First observation summary",
                "{\"run\":1}",
                t1
        );

        discrepancyRecorder.recordDiscrepancy(
                run2,
                DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW,
                "PAY-002",
                "Second observation summary",
                "{\"run\":2}",
                t2
        );

        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-002");
        ReconciliationDiscrepancyEntity entity = discrepancyRepository.findByDiscrepancyKey(key.getValue()).orElseThrow();

        assertThat(entity.getConsecutiveDetections()).isEqualTo(2);
        assertThat(entity.getFirstRunId()).isEqualTo(run1.getValue());
        assertThat(entity.getLatestRunId()).isEqualTo(run2.getValue());

        List<ReconciliationDiscrepancyObservationEntity> observations =
                observationRepository.findByDiscrepancyIdOrderByObservedAtAsc(entity.getDiscrepancyId());
        assertThat(observations).hasSize(2);
        assertThat(observations.get(0).getRunId()).isEqualTo(run1.getValue());
        assertThat(observations.get(0).getSummary()).isEqualTo("First observation summary");
        assertThat(observations.get(1).getRunId()).isEqualTo(run2.getValue());
        assertThat(observations.get(1).getSummary()).isEqualTo("Second observation summary");
    }

    @Test
    @DisplayName("Repeated recording within the same run is idempotent and does not duplicate observations or increments")
    void repeatedRecordingInSameRunIsIdempotent() {
        ReconciliationRunId runId = createRun("OPS_1");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        discrepancyRecorder.recordDiscrepancy(
                runId,
                DiscrepancyType.RESERVATION_PAYMENT_MISMATCH,
                "PAY-003",
                "Initial run scan",
                "{\"attempt\":1}",
                now
        );

        // Second recording in the same run
        discrepancyRecorder.recordDiscrepancy(
                runId,
                DiscrepancyType.RESERVATION_PAYMENT_MISMATCH,
                "PAY-003",
                "Duplicate call within same run",
                "{\"attempt\":2}",
                now
        );

        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.RESERVATION_PAYMENT_MISMATCH, "PAY-003");
        ReconciliationDiscrepancyEntity entity = discrepancyRepository.findByDiscrepancyKey(key.getValue()).orElseThrow();

        assertThat(entity.getConsecutiveDetections()).isEqualTo(1);

        List<ReconciliationDiscrepancyObservationEntity> observations =
                observationRepository.findByDiscrepancyIdOrderByObservedAtAsc(entity.getDiscrepancyId());
        assertThat(observations).hasSize(1);
    }

    @Test
    @DisplayName("Database unique constraint rejects duplicate (discrepancy_id, run_id) observation")
    void databaseUniqueConstraintEnforced() {
        ReconciliationRunId runId = createRun("OPS_1");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        discrepancyRecorder.recordDiscrepancy(
                runId,
                DiscrepancyType.PAYMENT_LEDGER_BREAK,
                "PAY-004",
                "Break detected",
                "{}",
                now
        );

        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.PAYMENT_LEDGER_BREAK, "PAY-004");
        ReconciliationDiscrepancyEntity entity = discrepancyRepository.findByDiscrepancyKey(key.getValue()).orElseThrow();

        // Attempt direct insertion of duplicate observation row
        ReconciliationDiscrepancyObservation duplicate = ReconciliationDiscrepancyObservation.createLive(
                DiscrepancyObservationId.generate(),
                DiscrepancyId.of(entity.getDiscrepancyId()),
                runId,
                DiscrepancyType.PAYMENT_LEDGER_BREAK,
                DiscrepancyStatus.OPEN,
                "Direct duplicate insertion",
                "{}",
                now
        );

        assertThatThrownBy(() -> observationRepository.saveAndFlush(operationsMapper.toEntity(duplicate)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("New discrepancy aggregate and observation roll back atomically when observation save fails")
    void newDiscrepancyRollsBackAggregateWhenObservationSaveFails() {
        ReconciliationRunId runId = createRun("OPS_FAIL_NEW");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String entityId = "PAY-ROLLBACK-NEW";
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, entityId);

        // Inject controlled failure during observation persistence
        doThrow(new RuntimeException("Simulated observation persistence failure"))
                .when(observationRepository).save(any(ReconciliationDiscrepancyObservationEntity.class));

        assertThatThrownBy(() -> discrepancyRecorder.recordDiscrepancy(
                runId,
                DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW,
                entityId,
                "Stale workflow doomed to fail",
                "{\"error\":true}",
                now
        )).isInstanceOf(RuntimeException.class)
          .hasMessage("Simulated observation persistence failure");

        // Verify from database that NEITHER aggregate nor observation was persisted
        assertThat(discrepancyRepository.findByDiscrepancyKey(key.getValue())).isEmpty();
        assertThat(observationRepository.count()).isZero();
    }

    @Test
    @DisplayName("Repeat detection aggregate update rolls back atomically when observation save fails")
    void repeatDetectionRollsBackAggregateUpdateWhenObservationSaveFails() {
        ReconciliationRunId run1 = createRun("OPS_RUN1");
        ReconciliationRunId run2 = createRun("OPS_RUN2");
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String entityId = "PAY-ROLLBACK-REPEAT";
        DiscrepancyKey key = DiscrepancyKey.of(DiscrepancyType.PAYMENT_LEDGER_BREAK, entityId);

        // 1. Initial observation succeeds
        discrepancyRecorder.recordDiscrepancy(
                run1,
                DiscrepancyType.PAYMENT_LEDGER_BREAK,
                entityId,
                "Initial ledger break",
                "{\"v\":1}",
                t1
        );

        ReconciliationDiscrepancyEntity initialEntity =
                discrepancyRepository.findByDiscrepancyKey(key.getValue()).orElseThrow();
        assertThat(initialEntity.getConsecutiveDetections()).isEqualTo(1);
        assertThat(initialEntity.getLatestRunId()).isEqualTo(run1.getValue());
        Instant initialLastObserved = initialEntity.getLastObservedAt();

        // 2. Configure spy to fail during observation save for run2
        doThrow(new RuntimeException("Simulated observation persistence failure on repeat"))
                .when(observationRepository).save(any(ReconciliationDiscrepancyObservationEntity.class));

        // 3. Attempt repeat observation in run2
        assertThatThrownBy(() -> discrepancyRecorder.recordDiscrepancy(
                run2,
                DiscrepancyType.PAYMENT_LEDGER_BREAK,
                entityId,
                "Updated ledger break attempt",
                "{\"v\":2}",
                t2
        )).isInstanceOf(RuntimeException.class)
          .hasMessage("Simulated observation persistence failure on repeat");

        // 4. Verify from database that aggregate update rolled back:
        // consecutiveDetections is still 1, latestRunId is still run1, lastObservedAt unchanged, summary unchanged
        ReconciliationDiscrepancyEntity postFailureEntity =
                discrepancyRepository.findByDiscrepancyKey(key.getValue()).orElseThrow();
        assertThat(postFailureEntity.getConsecutiveDetections()).isEqualTo(1);
        assertThat(postFailureEntity.getLatestRunId()).isEqualTo(run1.getValue());
        assertThat(postFailureEntity.getLastObservedAt()).isEqualTo(initialLastObserved);
        assertThat(postFailureEntity.getSummary()).isEqualTo("Initial ledger break");

        // And only the first run's observation exists
        List<ReconciliationDiscrepancyObservationEntity> observations =
                observationRepository.findByDiscrepancyIdOrderByObservedAtAsc(postFailureEntity.getDiscrepancyId());
        assertThat(observations).hasSize(1);
        assertThat(observations.getFirst().getRunId()).isEqualTo(run1.getValue());
    }
}
