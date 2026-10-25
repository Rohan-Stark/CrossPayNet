package com.crosspaynet.routing.contracts;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.payment.contracts.BicCode;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Immutable read-only projection of a correspondent banking route.
 */
public record RouteContext(
        RouteId routeId,
        String routeReference,
        BicCode destinationBic,
        BicCode correspondentBic,
        Currency currency,
        String clearingAccountReference,
        int priority,
        RouteStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    private static final Pattern ROUTE_REFERENCE_PATTERN = Pattern.compile("^[A-Za-z0-9:_.-]{1,100}$");

    public RouteContext {
        if (routeId == null) {
            throw new RoutingValidationException("routeId cannot be null.");
        }
        if (routeReference == null
                || routeReference.isBlank()
                || !routeReference.equals(routeReference.trim())
                || routeReference.length() > 100
                || !ROUTE_REFERENCE_PATTERN.matcher(routeReference).matches()) {
            throw new RoutingValidationException("routeReference must be a trimmed 1..100 char identifier.");
        }
        if (destinationBic == null) {
            throw new RoutingValidationException("destinationBic cannot be null.");
        }
        if (correspondentBic == null) {
            throw new RoutingValidationException("correspondentBic cannot be null.");
        }
        if (currency == null) {
            throw new RoutingValidationException("currency cannot be null.");
        }
        if (clearingAccountReference == null
                || clearingAccountReference.isBlank()
                || RoutingBics.hasLeadingOrTrailingWhitespace(clearingAccountReference)
                || clearingAccountReference.length() > 100
                || clearingAccountReference.codePoints().anyMatch(Character::isISOControl)) {
            throw new RoutingValidationException(
                    "clearingAccountReference must be a trimmed 1..100 char string without ISO control characters."
            );
        }
        if (priority < 1 || priority > 1000) {
            throw new RoutingValidationException("priority must be between 1 and 1000.");
        }
        if (status == null) {
            throw new RoutingValidationException("status cannot be null.");
        }
        if (createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) {
            throw new RoutingValidationException("createdAt and updatedAt must be non-null with updatedAt >= createdAt.");
        }
        destinationBic = RoutingBics.canonicalize(destinationBic);
        correspondentBic = RoutingBics.canonicalize(correspondentBic);
    }
}
