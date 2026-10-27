package com.crosspaynet.operations.domain;

import java.util.Objects;
import java.util.UUID;

public final class DiscrepancyId {

    private final UUID value;

    public DiscrepancyId(UUID value) {
        this.value = Objects.requireNonNull(value, "DiscrepancyId value cannot be null.");
    }

    public static DiscrepancyId generate() {
        return new DiscrepancyId(UUID.randomUUID());
    }

    public static DiscrepancyId of(UUID value) {
        return new DiscrepancyId(value);
    }

    public static DiscrepancyId of(String value) {
        if (value == null || value.isBlank()) {
            throw new OperationsValidationException("DiscrepancyId string representation cannot be blank.");
        }
        try {
            return new DiscrepancyId(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException ex) {
            throw new OperationsValidationException("Invalid UUID format for DiscrepancyId: " + value);
        }
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DiscrepancyId that = (DiscrepancyId) o;
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
