package com.crosspaynet.ledger;

import com.crosspaynet.ledger.domain.LedgerLimits;

/**
 * Immutable reference to an account in the Ledger module.
 *
 * <p>The {@code value} must already be trimmed (leading or trailing whitespace is rejected),
 * non-blank, contain between 1 and {@link LedgerLimits#MAX_ACCOUNT_REFERENCE_LENGTH} Unicode
 * code points, and contain no ISO control characters.
 */
public record LedgerAccountRef(LedgerAccountType type, String value) {

    public LedgerAccountRef {
        if (type == null) {
            throw new LedgerValidationException("LedgerAccountRef type cannot be null.");
        }
        if (value == null) {
            throw new LedgerValidationException("LedgerAccountRef value cannot be null.");
        }
        if (value.isBlank()) {
            throw new LedgerValidationException("LedgerAccountRef value cannot be empty or blank.");
        }
        if (!value.equals(value.trim())
                || !value.equals(value.strip())
                || Character.isWhitespace(value.codePointAt(0))
                || Character.isSpaceChar(value.codePointAt(0))
                || Character.isWhitespace(value.codePointBefore(value.length()))
                || Character.isSpaceChar(value.codePointBefore(value.length()))) {
            throw new LedgerValidationException(
                    "LedgerAccountRef value must already be trimmed and cannot have leading or trailing whitespace."
            );
        }
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount < 1 || codePointCount > LedgerLimits.MAX_ACCOUNT_REFERENCE_LENGTH) {
            throw new LedgerValidationException(
                    "LedgerAccountRef value length (" + codePointCount + " code points) must be between 1 and "
                            + LedgerLimits.MAX_ACCOUNT_REFERENCE_LENGTH + "."
            );
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new LedgerValidationException("LedgerAccountRef value cannot contain ISO control characters.");
        }
    }

    public static LedgerAccountRef of(LedgerAccountType type, String value) {
        return new LedgerAccountRef(type, value);
    }
}
