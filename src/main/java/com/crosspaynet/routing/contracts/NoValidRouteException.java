package com.crosspaynet.routing.contracts;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.payment.contracts.BicCode;

/**
 * Thrown when no active correspondent route exists for a given destination BIC and currency.
 */
public class NoValidRouteException extends RoutingException {

    public NoValidRouteException(BicCode destinationBic, Currency currency) {
        super("No active correspondent route available for destinationBic '"
                + (destinationBic != null ? destinationBic.getValue() : "null")
                + "' and currency '"
                + (currency != null ? currency.getCode() : "null") + "'.");
    }

    public NoValidRouteException(String message) {
        super(message);
    }
}
