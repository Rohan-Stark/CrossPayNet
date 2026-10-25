package com.crosspaynet.routing.contracts;

import com.crosspaynet.payment.contracts.BicCode;

/**
 * Routing-local BIC canonicalization and clearing-account whitespace helpers.
 *
 * <p>An 8-character BIC identifies the institution's primary office and is equivalent to the
 * 11-character form with branch code {@code XXX} (ISO 9362). Different 11-character branch BICs
 * are never equated.
 */
public final class RoutingBics {

    private static final String PRIMARY_OFFICE_BRANCH = "XXX";

    private RoutingBics() {
    }

    /**
     * Returns the canonical 11-character form of {@code bic} (appending {@code XXX} to an
     * 8-character BIC), or {@code null} if {@code bic} is {@code null}.
     */
    public static BicCode canonicalize(BicCode bic) {
        if (bic == null) {
            return null;
        }
        if (bic.getValue().length() == BicCode.MIN_LENGTH) {
            return new BicCode(bic.getValue() + PRIMARY_OFFICE_BRANCH);
        }
        return bic;
    }

    /**
     * Returns {@code true} if the value begins or ends with a code point for which
     * {@link Character#isWhitespace(int)} or {@link Character#isSpaceChar(int)} is true,
     * matching the Ledger account-reference rule.
     */
    public static boolean hasLeadingOrTrailingWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int first = value.codePointAt(0);
        int last = value.codePointBefore(value.length());
        return Character.isWhitespace(first) || Character.isSpaceChar(first)
                || Character.isWhitespace(last) || Character.isSpaceChar(last);
    }
}
