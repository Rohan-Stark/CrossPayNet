package com.crosspaynet.payment.domain;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.payment.contracts.PaymentValidationException;

import java.util.Set;

/**
 * Simulator policy enforcing the allow-list of ISO 4217 currencies supported by the Payment module.
 *
 * <p>This 8-currency policy ({@code USD, EUR, GBP, INR, JPY, CHF, SGD, AED}) is deliberately
 * synchronized with the simulator's Ledger currency policy without coupling Payment to internal
 * Ledger classes.
 */
public final class PaymentSupportedCurrencyPolicy {

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

    private PaymentSupportedCurrencyPolicy() {
        // Prevent instantiation
    }

    public static boolean isSupported(Currency currency) {
        return currency != null && SUPPORTED_CODES.contains(currency.getCode());
    }

    public static void requireSupported(Currency currency) {
        if (currency == null) {
            throw new PaymentValidationException("Currency cannot be null.");
        }
        if (!SUPPORTED_CODES.contains(currency.getCode())) {
            throw new PaymentValidationException("Unsupported payment currency: " + currency.getCode());
        }
    }

    public static Set<String> supportedCodes() {
        return SUPPORTED_CODES;
    }
}
