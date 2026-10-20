package com.crosspaynet.ledger;

import com.crosspaynet.ledger.domain.LedgerLimits;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Idempotency key for posting a Ledger transaction (ADR-006).
 *
 * <p>Standard caller-supplied keys must match {@code [A-Za-z0-9:_.-]{1,200}} and must not start
 * with {@code REVERSAL:} (case-insensitively). Reversal keys are derived deterministically by
 * the Ledger domain via {@link #forReversal(LedgerTransactionId)}.
 */
public final class LedgerPostingKey {

    private static final Pattern ALLOWED_PATTERN = Pattern.compile("^[A-Za-z0-9:_.-]{1,200}$");

    private final String value;

    public LedgerPostingKey(String value) {
        this(value, false);
    }

    private LedgerPostingKey(String value, boolean allowReversalPrefix) {
        if (value == null || value.isEmpty()) {
            throw new LedgerValidationException("LedgerPostingKey cannot be null or empty.");
        }
        if (value.length() > LedgerLimits.MAX_POSTING_KEY_LENGTH) {
            throw new LedgerValidationException(
                    "LedgerPostingKey length (" + value.length() + ") exceeds maximum of "
                            + LedgerLimits.MAX_POSTING_KEY_LENGTH + " characters."
            );
        }
        if (!ALLOWED_PATTERN.matcher(value).matches()) {
            throw new LedgerValidationException(
                    "LedgerPostingKey contains disallowed characters or whitespace; must match [A-Za-z0-9:_.-]{1,200}."
            );
        }
        if (!allowReversalPrefix && startsWithReversalPrefixIgnoreCase(value)) {
            throw new LedgerValidationException(
                    "Caller-supplied LedgerPostingKey cannot start with reserved prefix '"
                            + LedgerLimits.REVERSAL_POSTING_KEY_PREFIX + "'."
            );
        }
        this.value = value;
    }

    public static LedgerPostingKey of(String value) {
        return new LedgerPostingKey(value, false);
    }

    /**
     * Deterministically derives the exact uppercase reversal posting key {@code REVERSAL:<originalTransactionId>}.
     */
    public static LedgerPostingKey forReversal(LedgerTransactionId originalTransactionId) {
        if (originalTransactionId == null) {
            throw new LedgerValidationException("originalTransactionId cannot be null when deriving reversal posting key.");
        }
        return new LedgerPostingKey(
                LedgerLimits.REVERSAL_POSTING_KEY_PREFIX + originalTransactionId.getValue().toString(),
                true
        );
    }

    public String getValue() {
        return value;
    }

    public boolean isReversalKey() {
        return value.startsWith(LedgerLimits.REVERSAL_POSTING_KEY_PREFIX);
    }

    private static boolean startsWithReversalPrefixIgnoreCase(String input) {
        String prefix = LedgerLimits.REVERSAL_POSTING_KEY_PREFIX;
        return input.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerPostingKey that = (LedgerPostingKey) o;
        return Objects.equals(value, that.value);
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
