package com.crosspaynet.messaging.domain;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Domain boundary constants and currency policy for the Messaging module.
 */
public final class MessagingLimits {

    public static final int MIN_ATTEMPT_NUMBER = 1;
    public static final int MAX_ATTEMPT_NUMBER = 100;

    public static final int MAX_SCALE = 4;
    public static final int MAX_NUMERIC_PRECISION = 19;
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.9999");

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;
    public static final int MAX_ROUTE_REFERENCE_LENGTH = 100;
    public static final int MAX_BENEFICIARY_NAME_LENGTH = 140;
    public static final int MAX_BENEFICIARY_ACCOUNT_LENGTH = 34;
    public static final int MAX_ACKNOWLEDGMENT_REFERENCE_LENGTH = 100;
    public static final int MAX_PEER_REJECTION_CODE_LENGTH = 35;
    public static final int MAX_PEER_REJECTION_REASON_LENGTH = 500;
    public static final int MAX_TIMEOUT_REASON_LENGTH = 500;

    private static final Set<String> SUPPORTED_CURRENCY_CODES = Set.of(
            "USD",
            "EUR",
            "GBP",
            "INR",
            "JPY",
            "CHF",
            "SGD",
            "AED"
    );

    private MessagingLimits() {
    }

    public static Set<String> supportedCurrencyCodes() {
        return SUPPORTED_CURRENCY_CODES;
    }

    public static boolean isSupportedCurrencyCode(String code) {
        return code != null && SUPPORTED_CURRENCY_CODES.contains(code);
    }
}
