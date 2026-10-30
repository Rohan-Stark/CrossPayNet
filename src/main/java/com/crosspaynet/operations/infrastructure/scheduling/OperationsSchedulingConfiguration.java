package com.crosspaynet.operations.infrastructure.scheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Objects;

/**
 * Scheduling and distributed locking configuration for operational reconciliation.
 *
 * <p>Activated only when {@code crosspaynet.operations.reconciliation.scheduling.enabled=true}.
 * When disabled (the default), neither scheduling nor ShedLock distributed locks are registered.</p>
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "30m")
@ConditionalOnProperty(
        prefix = "crosspaynet.operations.reconciliation.scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class OperationsSchedulingConfiguration {

    public static final String SHEDLOCK_TABLE_NAME = "operations.shedlock";

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null.");
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .withTableName(SHEDLOCK_TABLE_NAME)
                        .usingDbTime()
                        .build()
        );
    }
}
