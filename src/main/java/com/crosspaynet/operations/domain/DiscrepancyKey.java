package com.crosspaynet.operations.domain;

import java.util.Objects;

public final class DiscrepancyKey {

    private static final int MAX_LENGTH = 150;
    private final String value;

    public DiscrepancyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new OperationsValidationException("DiscrepancyKey cannot be blank.");
        }
        String trimmed = value.trim();
        if (codePointLength(trimmed) > MAX_LENGTH) {
            throw new OperationsValidationException("DiscrepancyKey exceeds maximum length of " + MAX_LENGTH + " characters.");
        }
        this.value = trimmed;
    }

    public static DiscrepancyKey of(String value) {
        return new DiscrepancyKey(value);
    }

    public static DiscrepancyKey of(DiscrepancyType type, String operationalEntityId) {
        Objects.requireNonNull(type, "DiscrepancyType cannot be null.");
        if (operationalEntityId == null || operationalEntityId.isBlank()) {
            throw new OperationsValidationException("Operational entity ID cannot be blank.");
        }
        return new DiscrepancyKey(type.name() + ":" + operationalEntityId.trim());
    }

    private static int codePointLength(String str) {
        return str.codePointCount(0, str.length());
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DiscrepancyKey that = (DiscrepancyKey) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
