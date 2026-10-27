package com.crosspaynet.operations.domain;

import java.util.Objects;
import java.util.UUID;

public final class ReconciliationRunId {

    private final UUID value;

    public ReconciliationRunId(UUID value) {
        this.value = Objects.requireNonNull(value, "ReconciliationRunId value cannot be null.");
    }

    public static ReconciliationRunId generate() {
        return new ReconciliationRunId(UUID.randomUUID());
    }

    public static ReconciliationRunId of(UUID value) {
        return new ReconciliationRunId(value);
    }

    public static ReconciliationRunId of(String value) {
        if (value == null || value.isBlank()) {
            throw new OperationsValidationException("ReconciliationRunId string representation cannot be blank.");
        }
        try {
            return new ReconciliationRunId(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException ex) {
            throw new OperationsValidationException("Invalid UUID format for ReconciliationRunId: " + value);
        }
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReconciliationRunId that = (ReconciliationRunId) o;
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
