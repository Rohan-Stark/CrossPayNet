package com.crosspaynet.routing.domain;

import java.util.Set;

/**
 * Domain boundary constants and supported-currency policy for the Routing module.
 */
public final class RoutingLimits {

    public static final int MAX_ROUTE_REFERENCE_LENGTH = 100;
    public static final int MAX_CLEARING_ACCOUNT_REFERENCE_LENGTH = 100;
    public static final int MIN_PRIORITY = 1;
    public static final int MAX_PRIORITY = 1000;

    public static final Set<String> SUPPORTED_CURRENCIES = Set.of(
            "USD", "EUR", "GBP", "INR", "JPY", "CHF", "SGD", "AED"
    );

    private RoutingLimits() {
    }

    public static boolean isSupportedCurrencyCode(String currencyCode) {
        return currencyCode != null && SUPPORTED_CURRENCIES.contains(currencyCode);
    }
}
