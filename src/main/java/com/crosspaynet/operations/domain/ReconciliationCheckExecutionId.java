package com.crosspaynet.operations.domain;

import java.util.Objects;
import java.util.UUID;

public final class ReconciliationCheckExecutionId {

    private final UUID value;

    public ReconciliationCheckExecutionId(UUID value) {
        this.value = Objects.requireNonNull(value, "ReconciliationCheckExecutionId value cannot be null.");
    }

    public static ReconciliationCheckExecutionId generate() {
        return new ReconciliationCheckExecutionId(UUID.randomUUID());
    }

    public static ReconciliationCheckExecutionId of(UUID value) {
        return new ReconciliationCheckExecutionId(value);
    }

    public static ReconciliationCheckExecutionId of(String value) {
        if (value == null || value.isBlank()) {
            throw new OperationsValidationException("ReconciliationCheckExecutionId string representation cannot be blank.");
        }
        try {
            return new ReconciliationCheckExecutionId(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException ex) {
            throw new OperationsValidationException("Invalid UUID format for ReconciliationCheckExecutionId: " + value);
        }
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReconciliationCheckExecutionId that = (ReconciliationCheckExecutionId) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
