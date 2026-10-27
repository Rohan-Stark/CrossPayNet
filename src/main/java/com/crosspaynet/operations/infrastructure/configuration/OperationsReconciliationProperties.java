package com.crosspaynet.operations.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Educational configuration properties for operational reconciliation scanning thresholds.
 */
@Component
@ConfigurationProperties(prefix = "crosspaynet.operations.reconciliation")
public class OperationsReconciliationProperties {

    private Duration staleWorkflowThreshold = Duration.ofMinutes(30);
    private Duration graceWindow = Duration.ofMinutes(5);
    private Duration staleEventPublicationThreshold = Duration.ofMinutes(15);
    private int pageSize = 100;

    public Duration getStaleWorkflowThreshold() {
        return staleWorkflowThreshold;
    }

    public void setStaleWorkflowThreshold(Duration staleWorkflowThreshold) {
        if (staleWorkflowThreshold != null && !staleWorkflowThreshold.isNegative()) {
            this.staleWorkflowThreshold = staleWorkflowThreshold;
        }
    }

    public Duration getGraceWindow() {
        return graceWindow;
    }

    public void setGraceWindow(Duration graceWindow) {
        if (graceWindow != null && !graceWindow.isNegative()) {
            this.graceWindow = graceWindow;
        }
    }

    public Duration getStaleEventPublicationThreshold() {
        return staleEventPublicationThreshold;
    }

    public void setStaleEventPublicationThreshold(Duration staleEventPublicationThreshold) {
        if (staleEventPublicationThreshold != null && !staleEventPublicationThreshold.isNegative()) {
            this.staleEventPublicationThreshold = staleEventPublicationThreshold;
        }
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        if (pageSize >= 1 && pageSize <= 500) {
            this.pageSize = pageSize;
        }
    }
}
