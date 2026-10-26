package com.crosspaynet.compliance.contracts;

import java.util.Objects;
import java.util.UUID;

public final class ComplianceReviewId {

    private final UUID value;

    public ComplianceReviewId(UUID value) {
        this.value = Objects.requireNonNull(value, "ComplianceReviewId value cannot be null.");
    }

    public static ComplianceReviewId generate() {
        return new ComplianceReviewId(UUID.randomUUID());
    }

    public static ComplianceReviewId of(UUID value) {
        return new ComplianceReviewId(value);
    }

    public static ComplianceReviewId of(String value) {
        return new ComplianceReviewId(UUID.fromString(value));
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ComplianceReviewId that = (ComplianceReviewId) o;
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
