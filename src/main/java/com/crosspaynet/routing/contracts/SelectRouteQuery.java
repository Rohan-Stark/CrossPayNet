package com.crosspaynet.routing.contracts;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.payment.contracts.BicCode;

import java.util.Set;

/**
 * Query to deterministically select the highest-priority active correspondent route
 * for a given destination BIC and currency.
 */
public record SelectRouteQuery(
        BicCode destinationBic,
        Currency currency
) {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of(
            "USD", "EUR", "GBP", "INR", "JPY", "CHF", "SGD", "AED"
    );

    public SelectRouteQuery {
        if (destinationBic == null) {
            throw new RoutingValidationException("destinationBic cannot be null.");
        }
        if (currency == null) {
            throw new RoutingValidationException("currency cannot be null.");
        }
        if (!SUPPORTED_CURRENCIES.contains(currency.getCode())) {
            throw new RoutingValidationException("Unsupported routing currency: " + currency.getCode());
        }
    }
}
