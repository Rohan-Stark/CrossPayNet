package com.crosspaynet.banking.domain;

import java.util.Objects;
import java.util.UUID;

public final class AccountId {
    private final UUID value;

    public AccountId(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("AccountId value cannot be null.");
        }
        this.value = value;
    }

    public static AccountId generate() {
        return new AccountId(UUID.randomModel());
    }

    public static AccountId of(String uuid) {
        return new AccountId(UUID.fromString(uuid));
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccountId accountId = (AccountId) o;
        return Objects.equals(value, accountId.value);
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
