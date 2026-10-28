package com.crosspaynet.operations.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class OperationsFlywayV17MigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18");

    @Test
    @DisplayName("Migrate genuine V16 database to V17 and verify honest legacy observation backfill without modifying aggregates")
    void shouldMigrateV16DatabaseToV17WithHonestObservationBackfill() {
        String jdbcUrl = postgres.getJdbcUrl();
        String username = postgres.getUsername();
        String password = postgres.getPassword();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        // 1. Run Flyway only through version 16
        Flyway flywayToV16 = Flyway.configure()
                .dataSource(dataSource)
                .target("16")
                .load();
        flywayToV16.migrate();

        // Verify operations schema exists and V17 table does NOT exist yet
        Integer observationTableCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables 
                WHERE table_schema = 'operations' AND table_name = 'reconciliation_discrepancy_observation'
                """, Integer.class);
        assertThat(observationTableCount).isZero();

        // 2. Insert valid legacy V16 fixtures
        Instant t0 = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t1 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

        UUID run1Id = UUID.randomUUID();
        UUID run2Id = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_run (
                    run_id, version, status, started_at, completed_at, checks_evaluated, discrepancy_count, triggered_by
                ) VALUES (?, 0, 'COMPLETED', ?, ?, 4, 1, 'OP_MIGRATION_1')
                """, run1Id, Timestamp.from(t0), Timestamp.from(t0.plusSeconds(5)));

        jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_run (
                    run_id, version, status, started_at, completed_at, checks_evaluated, discrepancy_count, triggered_by
                ) VALUES (?, 0, 'COMPLETED', ?, ?, 4, 2, 'OP_MIGRATION_2')
                """, run2Id, Timestamp.from(t1), Timestamp.from(t1.plusSeconds(5)));

        // Discrepancy A: single-run discrepancy (first_run_id == latest_run_id)
        UUID discAId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy (
                    discrepancy_id, version, discrepancy_key, first_run_id, latest_run_id,
                    discrepancy_type, operational_entity_id, status, summary, details,
                    first_detected_at, last_observed_at, consecutive_detections, created_at, updated_at
                ) VALUES (?, 0, 'STALE_IN_FLIGHT_WORKFLOW:PAY-V16-A', ?, ?, 'STALE_IN_FLIGHT_WORKFLOW', 'PAY-V16-A',
                          'OPEN', 'Payment PAY-V16-A workflow stale', '{"ageMinutes": 120}'::jsonb,
                          ?, ?, 1, ?, ?)
                """, discAId, run1Id, run1Id, Timestamp.from(t0), Timestamp.from(t0), Timestamp.from(t0), Timestamp.from(t0));

        // Discrepancy B: multi-run discrepancy (first_run_id != latest_run_id)
        UUID discBId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy (
                    discrepancy_id, version, discrepancy_key, first_run_id, latest_run_id,
                    discrepancy_type, operational_entity_id, status, summary, details,
                    first_detected_at, last_observed_at, consecutive_detections, created_at, updated_at
                ) VALUES (?, 1, 'PAYMENT_LEDGER_BREAK:PAY-V16-B', ?, ?, 'PAYMENT_LEDGER_BREAK', 'PAY-V16-B',
                          'ACKNOWLEDGED', 'Settlement ledger break on PAY-V16-B', '{"ledgerDiff": "50.00 USD"}'::jsonb,
                          ?, ?, 2, ?, ?)
                """, discBId, run1Id, run2Id, Timestamp.from(t0), Timestamp.from(t1), Timestamp.from(t0), Timestamp.from(t1));

        // 3. Capture the relevant legacy discrepancy aggregate rows before migration
        Map<String, Object> beforeA = jdbcTemplate.queryForMap(
                "SELECT * FROM operations.reconciliation_discrepancy WHERE discrepancy_id = ?", discAId);
        Map<String, Object> beforeB = jdbcTemplate.queryForMap(
                "SELECT * FROM operations.reconciliation_discrepancy WHERE discrepancy_id = ?", discBId);

        // 4. Run the actual Flyway migration through V17 (NOT an inline copy)
        Flyway flywayToV17 = Flyway.configure()
                .dataSource(dataSource)
                .target("17")
                .load();
        flywayToV17.migrate();

        // 5. Verify the migration creates exactly one observation for single-run and two for multi-run
        List<Map<String, Object>> observationsA = jdbcTemplate.queryForList("""
                SELECT * FROM operations.reconciliation_discrepancy_observation 
                WHERE discrepancy_id = ? 
                ORDER BY observed_at ASC
                """, discAId);
        assertThat(observationsA).hasSize(1);

        List<Map<String, Object>> observationsB = jdbcTemplate.queryForList("""
                SELECT * FROM operations.reconciliation_discrepancy_observation 
                WHERE discrepancy_id = ? 
                ORDER BY observed_at ASC
                """, discBId);
        assertThat(observationsB).hasSize(2);

        // Overall observation table count is exactly 3 (no intermediate or extra rows fabricated)
        Integer totalObservations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operations.reconciliation_discrepancy_observation", Integer.class);
        assertThat(totalObservations).isEqualTo(3);

        // 6. Verify all reconstructed observations use LEGACY_RECONSTRUCTED provenance
        assertThat(observationsA.getFirst().get("provenance")).isEqualTo("LEGACY_RECONSTRUCTED");
        assertThat(observationsB.get(0).get("provenance")).isEqualTo("LEGACY_RECONSTRUCTED");
        assertThat(observationsB.get(1).get("provenance")).isEqualTo("LEGACY_RECONSTRUCTED");

        // 7. Verify historical fields are handled honestly:
        // Discrepancy A (single-run): has details and status preserved from the single observation
        Map<String, Object> obsA = observationsA.getFirst();
        assertThat(obsA.get("run_id")).isEqualTo(run1Id);
        assertThat(obsA.get("status_at_observation")).isEqualTo("OPEN");
        assertThat(obsA.get("summary")).isEqualTo("Payment PAY-V16-A workflow stale");
        assertThat(obsA.get("details").toString()).contains("120");
        assertThat(obsA.get("observed_at")).isEqualTo(beforeA.get("first_detected_at"));

        // Discrepancy B - Observation 1 (first run): details IS NULL, status OPEN, no fabricated snapshot
        Map<String, Object> obsBFirst = observationsB.get(0);
        assertThat(obsBFirst.get("run_id")).isEqualTo(run1Id);
        assertThat(obsBFirst.get("status_at_observation")).isEqualTo("OPEN");
        assertThat(obsBFirst.get("summary")).isEqualTo("Settlement ledger break on PAY-V16-B");
        assertThat(obsBFirst.get("details")).isNull();
        assertThat(obsBFirst.get("observed_at")).isEqualTo(beforeB.get("first_detected_at"));

        // Discrepancy B - Observation 2 (latest run): uses available latest snapshot
        Map<String, Object> obsBLatest = observationsB.get(1);
        assertThat(obsBLatest.get("run_id")).isEqualTo(run2Id);
        assertThat(obsBLatest.get("status_at_observation")).isEqualTo("ACKNOWLEDGED");
        assertThat(obsBLatest.get("summary")).isEqualTo("Settlement ledger break on PAY-V16-B");
        assertThat(obsBLatest.get("details").toString()).contains("50.00 USD");
        assertThat(obsBLatest.get("observed_at")).isEqualTo(beforeB.get("last_observed_at"));

        // 8. Verify the pre-existing aggregate records and their fields remain unchanged by the backfill
        Map<String, Object> afterA = jdbcTemplate.queryForMap(
                "SELECT * FROM operations.reconciliation_discrepancy WHERE discrepancy_id = ?", discAId);
        Map<String, Object> afterB = jdbcTemplate.queryForMap(
                "SELECT * FROM operations.reconciliation_discrepancy WHERE discrepancy_id = ?", discBId);

        assertThat(afterA).isEqualTo(beforeA);
        assertThat(afterB).isEqualTo(beforeB);

        // 9. Verify the resulting foreign keys, uniqueness constraints, and check constraints
        // A. Foreign key constraint to non-existent discrepancy
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy_observation (
                    observation_id, discrepancy_id, run_id, discrepancy_type, status_at_observation,
                    summary, details, observed_at, provenance
                ) VALUES (?, ?, ?, 'STALE_IN_FLIGHT_WORKFLOW', 'OPEN', 'Summary', NULL, now(), 'LIVE')
                """, UUID.randomUUID(), UUID.randomUUID(), run1Id))
                .isInstanceOf(DataIntegrityViolationException.class);

        // B. Foreign key constraint to non-existent run
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy_observation (
                    observation_id, discrepancy_id, run_id, discrepancy_type, status_at_observation,
                    summary, details, observed_at, provenance
                ) VALUES (?, ?, ?, 'STALE_IN_FLIGHT_WORKFLOW', 'OPEN', 'Summary', NULL, now(), 'LIVE')
                """, UUID.randomUUID(), discAId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // C. Unique constraint (discrepancy_id, run_id)
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy_observation (
                    observation_id, discrepancy_id, run_id, discrepancy_type, status_at_observation,
                    summary, details, observed_at, provenance
                ) VALUES (?, ?, ?, 'STALE_IN_FLIGHT_WORKFLOW', 'OPEN', 'Duplicate attempt', NULL, now(), 'LIVE')
                """, UUID.randomUUID(), discAId, run1Id))
                .isInstanceOf(DataIntegrityViolationException.class);

        // D. Check constraint on provenance
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO operations.reconciliation_discrepancy_observation (
                    observation_id, discrepancy_id, run_id, discrepancy_type, status_at_observation,
                    summary, details, observed_at, provenance
                ) VALUES (?, ?, ?, 'STALE_IN_FLIGHT_WORKFLOW', 'OPEN', 'Summary', NULL, now(), 'INVALID_PROV')
                """, UUID.randomUUID(), discAId, run2Id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
