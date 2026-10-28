package com.crosspaynet.operations.domain;

import java.util.Objects;
import java.util.UUID;

public final class DiscrepancyObservationId {

    private final UUID value;

    public DiscrepancyObservationId(UUID value) {
        this.value = Objects.requireNonNull(value, "DiscrepancyObservationId value cannot be null.");
    }

    public static DiscrepancyObservationId generate() {
        return new DiscrepancyObservationId(UUID.randomUUID());
    }

    public static DiscrepancyObservationId of(UUID value) {
        return new DiscrepancyObservationId(value);
    }

    public static DiscrepancyObservationId of(String value) {
        if (value == null || value.isBlank()) {
            throw new OperationsValidationException("DiscrepancyObservationId string representation cannot be blank.");
        }
        try {
            return new DiscrepancyObservationId(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException ex) {
            throw new OperationsValidationException("Invalid UUID format for DiscrepancyObservationId: " + value);
        }
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DiscrepancyObservationId that = (DiscrepancyObservationId) o;
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
