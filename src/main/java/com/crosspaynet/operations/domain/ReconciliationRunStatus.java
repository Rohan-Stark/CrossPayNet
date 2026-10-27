package com.crosspaynet.operations.domain;

public enum ReconciliationRunStatus {
    IN_PROGRESS,
    COMPLETED,
    PARTIAL_FAILURE,
    FAILED;

    public boolean isTerminal() {
        return this != IN_PROGRESS;
    }
}
