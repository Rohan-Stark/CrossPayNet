package com.crosspaynet.operations.infrastructure.scheduling;

import com.crosspaynet.operations.contracts.ReconciliationRunView;
import com.crosspaynet.operations.contracts.ReconciliationService;
import com.crosspaynet.operations.contracts.TriggerReconciliationRunCommand;
import com.crosspaynet.operations.domain.OperationsConflictException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Scheduled executor for periodic operational reconciliation runs.
 *
 * <p>Guarded by ShedLock distributed lock across multiple application instances.
 * When activated via configuration, triggers runs automatically using the {@code SYSTEM_SCHEDULER}
 * operator identity.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "crosspaynet.operations.reconciliation.scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    public static final String TRIGGER_SOURCE = "SYSTEM_SCHEDULER";
    public static final String LOCK_NAME = "operations_reconciliation_scheduler";

    private final ReconciliationService reconciliationService;

    public ReconciliationScheduler(ReconciliationService reconciliationService) {
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService cannot be null.");
    }

    /**
     * Executes scheduled reconciliation scan.
     *
     * <p>Acquires distributed lock named {@code operations_reconciliation_scheduler}. If another instance
     * holds the lock, execution is skipped. If a local or concurrent reconciliation run is already in progress,
     * the resulting {@link OperationsConflictException} is caught and logged at INFO level without failing.</p>
     */
    @Scheduled(cron = "${crosspaynet.operations.reconciliation.scheduling.cron:0 */15 * * * *}")
    @SchedulerLock(
            name = LOCK_NAME,
            lockAtMostFor = "${crosspaynet.operations.reconciliation.scheduling.lock-at-most-for:30m}",
            lockAtLeastFor = "${crosspaynet.operations.reconciliation.scheduling.lock-at-least-for:5m}"
    )
    public void scheduledReconciliationRun() {
        log.info("Starting scheduled reconciliation run triggered by {}", TRIGGER_SOURCE);
        try {
            ReconciliationRunView runView = reconciliationService.triggerRun(
                    new TriggerReconciliationRunCommand(TRIGGER_SOURCE)
            );
            log.info("Scheduled reconciliation run completed successfully with runId={}", runView.runId().getValue());
        } catch (OperationsConflictException e) {
            log.info("Scheduled reconciliation run skipped due to concurrent active run: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Scheduled reconciliation run failed unexpectedly: {}", e.getMessage(), e);
        }
    }
}
