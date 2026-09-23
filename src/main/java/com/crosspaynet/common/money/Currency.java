package com.crosspaynet.common.money;

import java.util.Objects;

public final class Currency {
    private final String code;

    public Currency(String code) {
        if (code == null || code.trim().length() != 3) {
            throw new IllegalArgumentException("Currency code must be a 3-letter ISO 4217 code.");
        }
        this.code = code.trim().toUpperCase();
    }

    public String getCode() {
        return code;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Currency currency = (Currency) o;
        return Objects.equals(code, currency.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
