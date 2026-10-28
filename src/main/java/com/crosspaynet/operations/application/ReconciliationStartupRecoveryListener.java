package com.crosspaynet.operations.application;

import com.crosspaynet.operations.contracts.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Application startup listener that recovers any abandoned reconciliation runs left
 * in an {@code IN_PROGRESS} state by a prior terminated or crashed application process.
 *
 * <p>Under the modular monolith single-instance deployment model, any run persisted as
 * {@code IN_PROGRESS} prior to application startup cannot be actively executing and is
 * safely transitioned to {@code FAILED} with audit reason preservation.
 */
@Component
public class ReconciliationStartupRecoveryListener {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationStartupRecoveryListener.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationStartupRecoveryListener(ReconciliationService reconciliationService) {
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService cannot be null.");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            int recovered = reconciliationService.recoverAbandonedRuns();
            if (recovered > 0) {
                log.warn("Recovered {} abandoned reconciliation run(s) left by a prior application process.", recovered);
            }
        } catch (Exception ex) {
            log.error("Failed to recover abandoned reconciliation runs during startup: {}", ex.getMessage(), ex);
        }
    }
}
