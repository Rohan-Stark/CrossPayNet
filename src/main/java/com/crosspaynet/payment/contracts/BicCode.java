package com.crosspaynet.payment.contracts;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable ISO 9362 Bank Identifier Code (BIC / SWIFT code), 8 or 11 characters.
 */
public final class BicCode {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 11;
    private static final Pattern BIC_PATTERN = Pattern.compile("^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$");

    private final String value;

    public BicCode(String value) {
        if (value == null || value.isBlank()) {
            throw new PaymentValidationException("BicCode cannot be null or blank.");
        }
        String trimmed = value.trim();
        if (!BIC_PATTERN.matcher(trimmed).matches()) {
            throw new PaymentValidationException(
                    "Invalid BicCode format: '" + trimmed + "'. Must be an 8 or 11-character uppercase ISO 9362 BIC."
            );
        }
        this.value = trimmed;
    }

    public static BicCode of(String value) {
        return new BicCode(value);
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BicCode bicCode = (BicCode) o;
        return Objects.equals(value, bicCode.value);
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
