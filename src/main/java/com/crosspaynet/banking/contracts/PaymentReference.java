package com.crosspaynet.banking.contracts;

import java.util.Objects;

public final class PaymentReference {
    public static final int MAX_LENGTH = 255;

    private final String value;

    public PaymentReference(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("PaymentReference cannot be null or empty.");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("PaymentReference cannot exceed " + MAX_LENGTH + " characters.");
        }
        this.value = trimmed;
    }

    public static PaymentReference of(String value) {
        return new PaymentReference(value);
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaymentReference reference = (PaymentReference) o;
        return Objects.equals(value, reference.value);
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
