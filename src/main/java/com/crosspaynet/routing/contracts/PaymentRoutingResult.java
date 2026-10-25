package com.crosspaynet.routing.contracts;

import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentView;

import java.util.Optional;

/**
 * Result of executing {@code RoutingService.routePayment(PaymentId)}.
 */
public record PaymentRoutingResult(
        PaymentId paymentId,
        PaymentRoutingOutcome outcome,
        Optional<RouteContext> selectedRoute,
        PaymentView payment
) {

    public PaymentRoutingResult {
        if (paymentId == null) {
            throw new RoutingValidationException("paymentId cannot be null.");
        }
        if (outcome == null) {
            throw new RoutingValidationException("outcome cannot be null.");
        }
        if (selectedRoute == null) {
            throw new RoutingValidationException("selectedRoute Optional cannot be null.");
        }
        if (payment == null) {
            throw new RoutingValidationException("payment cannot be null.");
        }
        if (outcome == PaymentRoutingOutcome.ROUTE_COMMITTED && selectedRoute.isEmpty()) {
            throw new RoutingValidationException(
                    "ROUTE_COMMITTED outcome requires a non-empty selectedRoute."
            );
        }
        if (outcome == PaymentRoutingOutcome.REJECTED_NO_ROUTE) {
            if (selectedRoute.isPresent()) {
                throw new RoutingValidationException(
                        "REJECTED_NO_ROUTE outcome must have an empty selectedRoute."
                );
            }
            if (payment.status() != PaymentStatus.REJECTED) {
                throw new RoutingValidationException(
                        "REJECTED_NO_ROUTE outcome requires payment status REJECTED."
                );
            }
        }
    }
}
