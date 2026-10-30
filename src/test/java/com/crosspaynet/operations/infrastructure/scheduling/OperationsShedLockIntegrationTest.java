package com.crosspaynet.operations.infrastructure.scheduling;

import com.crosspaynet.PostgresTestContainerConfig;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OperationsShedLockIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LockProvider lockProvider1;
    private LockProvider lockProvider2;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM operations.shedlock");

        lockProvider1 = new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .withTableName(OperationsSchedulingConfiguration.SHEDLOCK_TABLE_NAME)
                        .usingDbTime()
                        .build()
        );

        lockProvider2 = new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .withTableName(OperationsSchedulingConfiguration.SHEDLOCK_TABLE_NAME)
                        .usingDbTime()
                        .build()
        );
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM operations.shedlock");
    }

    @Test
    @DisplayName("Flyway V18 migration creates operations.shedlock table with expected schema and constraints")
    void v18MigrationShouldCreateOperationsShedLockTable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'operations' AND table_name = 'shedlock'
                ORDER BY ordinal_position
                """);

        assertThat(columns).hasSize(4);

        assertThat(columns.get(0).get("column_name")).isEqualTo("name");
        assertThat(columns.get(0).get("data_type")).isEqualTo("character varying");
        assertThat(columns.get(0).get("is_nullable")).isEqualTo("NO");

        assertThat(columns.get(1).get("column_name")).isEqualTo("lock_until");
        assertThat(columns.get(1).get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(columns.get(1).get("is_nullable")).isEqualTo("NO");

        assertThat(columns.get(2).get("column_name")).isEqualTo("locked_at");
        assertThat(columns.get(2).get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(columns.get(2).get("is_nullable")).isEqualTo("NO");

        assertThat(columns.get(3).get("column_name")).isEqualTo("locked_by");
        assertThat(columns.get(3).get("data_type")).isEqualTo("character varying");
        assertThat(columns.get(3).get("is_nullable")).isEqualTo("NO");

        // Verify primary key on column 'name'
        List<String> pkColumns = jdbcTemplate.queryForList("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
                WHERE tc.table_schema = 'operations'
                  AND tc.table_name = 'shedlock'
                  AND tc.constraint_type = 'PRIMARY KEY'
                """, String.class);

        assertThat(pkColumns).containsExactly("name");
    }

    @Test
    @DisplayName("Two independent lock providers prevent concurrent acquisition of the reconciliation lock")
    void twoIndependentLockProvidersShouldPreventConcurrentLockAcquisition() {
        Instant now = Instant.now();
        LockConfiguration lockConfig = new LockConfiguration(
                now,
                ReconciliationScheduler.LOCK_NAME,
                Duration.ofMinutes(10),
                Duration.ofMinutes(2)
        );

        // Instance 1 acquires lock
        Optional<SimpleLock> lock1 = lockProvider1.lock(lockConfig);
        assertThat(lock1).isPresent();

        // Verify DB row was created
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT name, lock_until, locked_at, locked_by FROM operations.shedlock WHERE name = ?",
                ReconciliationScheduler.LOCK_NAME
        );
        assertThat(row.get("name")).isEqualTo(ReconciliationScheduler.LOCK_NAME);
        assertThat(row.get("locked_by")).isNotNull();
        assertThat(row.get("lock_until")).isNotNull();
        assertThat(row.get("locked_at")).isNotNull();

        // Instance 2 attempts to acquire the same lock concurrently -> fails
        Optional<SimpleLock> lock2 = lockProvider2.lock(lockConfig);
        assertThat(lock2).isEmpty();

        // Instance 1 unlocks
        lock1.get().unlock();
    }

    @Test
    @DisplayName("Lock release allows subsequent acquisition when lockAtLeastFor is satisfied")
    void lockReleaseShouldAllowSubsequentAcquisition() {
        Instant now = Instant.now();
        // Zero lockAtLeastFor allows immediate subsequent acquisition upon unlock
        LockConfiguration lockConfigImmediate = new LockConfiguration(
                now,
                ReconciliationScheduler.LOCK_NAME,
                Duration.ofMinutes(10),
                Duration.ZERO
        );

        Optional<SimpleLock> lock1 = lockProvider1.lock(lockConfigImmediate);
        assertThat(lock1).isPresent();

        // Unlock by instance 1 immediately clears the lock window
        lock1.get().unlock();

        // Instance 2 can now acquire
        Optional<SimpleLock> lock2 = lockProvider2.lock(lockConfigImmediate);
        assertThat(lock2).isPresent();

        lock2.get().unlock();
    }

    @Test
    @DisplayName("Lock enforces lockAtLeastFor retention even when unlocked early")
    void lockShouldEnforceLockAtLeastForRetention() {
        Instant now = Instant.now();
        Duration lockAtLeastFor = Duration.ofMinutes(5);
        Duration lockAtMostFor = Duration.ofMinutes(30);

        LockConfiguration lockConfig = new LockConfiguration(
                now,
                ReconciliationScheduler.LOCK_NAME,
                lockAtMostFor,
                lockAtLeastFor
        );

        Optional<SimpleLock> lock1 = lockProvider1.lock(lockConfig);
        assertThat(lock1).isPresent();

        // Instance 1 unlocks early
        lock1.get().unlock();

        // Check DB row: lock_until must be held until at least locked_at + lockAtLeastFor
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT locked_at, lock_until FROM operations.shedlock WHERE name = ?",
                ReconciliationScheduler.LOCK_NAME
        );
        Timestamp lockedAt = (Timestamp) row.get("locked_at");
        Timestamp lockUntil = (Timestamp) row.get("lock_until");

        Duration retention = Duration.between(lockedAt.toInstant(), lockUntil.toInstant());
        assertThat(retention).isGreaterThanOrEqualTo(lockAtLeastFor.minusSeconds(1));

        // Instance 2 tries to acquire immediately and must be rejected because lockAtLeastFor is not yet passed
        Optional<SimpleLock> lock2 = lockProvider2.lock(lockConfig);
        assertThat(lock2).isEmpty();
    }
}
