package com.crosspaynet.payment.contracts;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identifier for a {@code Payment} aggregate.
 */
public final class PaymentId {

    private final UUID value;

    public PaymentId(UUID value) {
        if (value == null) {
            throw new PaymentValidationException("PaymentId value cannot be null.");
        }
        this.value = value;
    }

    public static PaymentId of(UUID value) {
        return new PaymentId(value);
    }

    public static PaymentId of(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            throw new PaymentValidationException("PaymentId string cannot be null or blank.");
        }
        try {
            return new PaymentId(UUID.fromString(uuid.trim()));
        } catch (IllegalArgumentException ex) {
            throw new PaymentValidationException("Invalid UUID format for PaymentId: " + uuid, ex);
        }
    }

    public static PaymentId generate() {
        return new PaymentId(UUID.randomUUID());
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaymentId paymentId = (PaymentId) o;
        return Objects.equals(value, paymentId.value);
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
