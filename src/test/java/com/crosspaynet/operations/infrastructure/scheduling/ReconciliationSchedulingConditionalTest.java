package com.crosspaynet.operations.infrastructure.scheduling;

import com.crosspaynet.operations.contracts.ReconciliationService;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconciliationSchedulingConditionalTest {

    @Configuration
    @EnableConfigurationProperties(OperationsSchedulingProperties.class)
    static class TestMockConfiguration {
        @Bean
        ReconciliationService reconciliationService() {
            return mock(ReconciliationService.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
            DataSource mockDs = mock(DataSource.class);
            when(mockJdbc.getDataSource()).thenReturn(mockDs);
            return mockJdbc;
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    TestMockConfiguration.class,
                    OperationsSchedulingConfiguration.class,
                    ReconciliationScheduler.class
            );

    @Test
    @DisplayName("Scheduling beans are NOT registered by default when enabled property is absent")
    void schedulingBeansShouldBeAbsentByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(OperationsSchedulingConfiguration.class);
            assertThat(context).doesNotHaveBean(ReconciliationScheduler.class);
            assertThat(context).doesNotHaveBean(LockProvider.class);

            OperationsSchedulingProperties properties = context.getBean(OperationsSchedulingProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getCron()).isEqualTo("0 */15 * * * *");
            assertThat(properties.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(30));
            assertThat(properties.getLockAtLeastFor()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    @DisplayName("Scheduling beans are NOT registered when enabled property is explicitly false")
    void schedulingBeansShouldBeAbsentWhenExplicitlyDisabled() {
        contextRunner
                .withPropertyValues("crosspaynet.operations.reconciliation.scheduling.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OperationsSchedulingConfiguration.class);
                    assertThat(context).doesNotHaveBean(ReconciliationScheduler.class);
                    assertThat(context).doesNotHaveBean(LockProvider.class);

                    OperationsSchedulingProperties properties = context.getBean(OperationsSchedulingProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("Scheduling beans and LockProvider ARE registered when enabled property is true")
    void schedulingBeansShouldBePresentWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "crosspaynet.operations.reconciliation.scheduling.enabled=true",
                        "crosspaynet.operations.reconciliation.scheduling.cron=0 0 * * * *",
                        "crosspaynet.operations.reconciliation.scheduling.lock-at-most-for=45m",
                        "crosspaynet.operations.reconciliation.scheduling.lock-at-least-for=10m"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(OperationsSchedulingConfiguration.class);
                    assertThat(context).hasSingleBean(ReconciliationScheduler.class);
                    assertThat(context).hasSingleBean(LockProvider.class);

                    OperationsSchedulingProperties properties = context.getBean(OperationsSchedulingProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getCron()).isEqualTo("0 0 * * * *");
                    assertThat(properties.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(45));
                    assertThat(properties.getLockAtLeastFor()).isEqualTo(Duration.ofMinutes(10));
                });
    }
}
