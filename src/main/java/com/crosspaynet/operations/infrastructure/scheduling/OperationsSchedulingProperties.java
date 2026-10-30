package com.crosspaynet.operations.infrastructure.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for scheduled operational reconciliation runs.
 */
@Component
@ConfigurationProperties(prefix = "crosspaynet.operations.reconciliation.scheduling")
public class OperationsSchedulingProperties {

    private boolean enabled = false;
    private String cron = "0 */15 * * * *";
    private Duration lockAtMostFor = Duration.ofMinutes(30);
    private Duration lockAtLeastFor = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        if (cron != null && !cron.isBlank()) {
            this.cron = cron.trim();
        }
    }

    public Duration getLockAtMostFor() {
        return lockAtMostFor;
    }

    public void setLockAtMostFor(Duration lockAtMostFor) {
        if (lockAtMostFor != null && !lockAtMostFor.isNegative() && !lockAtMostFor.isZero()) {
            this.lockAtMostFor = lockAtMostFor;
        }
    }

    public Duration getLockAtLeastFor() {
        return lockAtLeastFor;
    }

    public void setLockAtLeastFor(Duration lockAtLeastFor) {
        if (lockAtLeastFor != null && !lockAtLeastFor.isNegative()) {
            this.lockAtLeastFor = lockAtLeastFor;
        }
    }
}
