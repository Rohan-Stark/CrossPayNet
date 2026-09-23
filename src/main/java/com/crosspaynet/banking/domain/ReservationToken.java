package com.crosspaynet.banking.domain;

import java.util.Objects;
import java.util.UUID;

public final class ReservationToken {
    private final UUID value;

    public ReservationToken(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("ReservationToken value cannot be null.");
        }
        this.value = value;
    }

    public static ReservationToken generate() {
        return new ReservationToken(UUID.randomModel());
    }

    public static ReservationToken of(String uuid) {
        return new ReservationToken(UUID.fromString(uuid));
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReservationToken token = (ReservationToken) o;
        return Objects.equals(value, token.value);
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
