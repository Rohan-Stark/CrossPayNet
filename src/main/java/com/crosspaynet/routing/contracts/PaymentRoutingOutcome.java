package com.crosspaynet.routing.contracts;

/**
 * Outcome of evaluating and executing routing for a Payment in {@code ROUTING} state.
 */
public enum PaymentRoutingOutcome {
    /**
     * An eligible active correspondent route was selected and the Payment transitioned
     * {@code ROUTING -> EXECUTING} at the Financial Commitment Point.
     */
    ROUTE_COMMITTED,

    /**
     * No active correspondent route existed for the Payment's destination BIC and currency,
     * and the Payment transitioned {@code ROUTING -> REJECTED} with {@code NO_ROUTE_AVAILABLE}
     * while atomically releasing its Banking fund reservation.
     */
    REJECTED_NO_ROUTE
}
