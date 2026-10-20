package com.crosspaynet.ledger;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identifier for a {@code LedgerTransaction}.
 */
public final class LedgerTransactionId {

    private final UUID value;

    public LedgerTransactionId(UUID value) {
        if (value == null) {
            throw new LedgerValidationException("LedgerTransactionId value cannot be null.");
        }
        this.value = value;
    }

    public static LedgerTransactionId generate() {
        return new LedgerTransactionId(UUID.randomUUID());
    }

    public static LedgerTransactionId of(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            throw new LedgerValidationException("LedgerTransactionId string cannot be null or blank.");
        }
        try {
            return new LedgerTransactionId(UUID.fromString(uuid.trim()));
        } catch (IllegalArgumentException ex) {
            throw new LedgerValidationException("Invalid UUID format for LedgerTransactionId: " + uuid, ex);
        }
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerTransactionId that = (LedgerTransactionId) o;
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
