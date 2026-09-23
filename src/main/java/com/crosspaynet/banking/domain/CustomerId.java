package com.crosspaynet.banking.domain;

import java.util.Objects;
import java.util.UUID;

public final class CustomerId {
    private final UUID value;

    public CustomerId(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("CustomerId value cannot be null.");
        }
        this.value = value;
    }

    public static CustomerId generate() {
        return new CustomerId(UUID.randomModel());
    }

    public static CustomerId of(String uuid) {
        return new CustomerId(UUID.fromString(uuid));
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CustomerId customerId = (CustomerId) o;
        return Objects.equals(value, customerId.value);
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
