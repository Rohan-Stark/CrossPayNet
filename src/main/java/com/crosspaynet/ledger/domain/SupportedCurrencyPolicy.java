package com.crosspaynet.ledger.domain;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.ledger.LedgerValidationException;

import java.util.Set;

/**
 * Simulator policy enforcing the initial allow-list of ISO 4217 currencies supported by the Ledger module (ADR-006).
 */
public final class SupportedCurrencyPolicy {

    private static final Set<String> SUPPORTED_CODES = Set.of(
            "USD",
            "EUR",
            "GBP",
            "INR",
            "JPY",
            "CHF",
            "SGD",
            "AED"
    );

    private SupportedCurrencyPolicy() {
        // Prevent instantiation
    }

    public static boolean isSupported(Currency currency) {
        return currency != null && SUPPORTED_CODES.contains(currency.getCode());
    }

    public static void requireSupported(Currency currency) {
        if (currency == null) {
            throw new LedgerValidationException("Currency cannot be null.");
        }
        if (!SUPPORTED_CODES.contains(currency.getCode())) {
            throw new LedgerValidationException("Unsupported ledger currency: " + currency.getCode());
        }
    }

    public static Set<String> supportedCodes() {
        return SUPPORTED_CODES;
    }
}
