package com.crosspaynet.ledger.domain;

import java.math.BigDecimal;

/**
 * Single source of truth for Ledger domain bounds and structural limits (ADR-006).
 */
public final class LedgerLimits {

    public static final int MIN_ENTRIES = 2;
    public static final int MAX_ENTRIES = 100;
    public static final int MAX_SCALE = 4;
    public static final int MAX_NUMERIC_PRECISION = 19;
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.9999");
    public static final int MAX_POSTING_KEY_LENGTH = 200;
    public static final String REVERSAL_POSTING_KEY_PREFIX = "REVERSAL:";
    public static final int MAX_EXTERNAL_REFERENCE_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_ACCOUNT_REFERENCE_LENGTH = 100;

    private LedgerLimits() {
        // Prevent instantiation
    }
}
