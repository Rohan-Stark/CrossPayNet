package com.crosspaynet.operations.contracts;

import java.util.Objects;

/**
 * Command to trigger an operational reconciliation scan.
 */
public record TriggerReconciliationRunCommand(String triggeredBy) {

    public TriggerReconciliationRunCommand {
        Objects.requireNonNull(triggeredBy, "triggeredBy cannot be null.");
        if (triggeredBy.isBlank()) {
            throw new IllegalArgumentException("triggeredBy cannot be blank.");
        }
    }
}
