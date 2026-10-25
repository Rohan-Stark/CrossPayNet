package com.crosspaynet.routing.contracts;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.payment.contracts.BicCode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Command to register a correspondent banking route in {@link RouteStatus#ACTIVE} state.
 */
public record RegisterCorrespondentRouteCommand(
        String routeReference,
        BicCode destinationBic,
        BicCode correspondentBic,
        Currency currency,
        String clearingAccountReference,
        int priority
) {

    public static final int MAX_ROUTE_REFERENCE_LENGTH = 100;
    public static final int MAX_CLEARING_ACCOUNT_REFERENCE_LENGTH = 100;
    public static final int MIN_PRIORITY = 1;
    public static final int MAX_PRIORITY = 1000;

    private static final Pattern ROUTE_REFERENCE_PATTERN = Pattern.compile("^[A-Za-z0-9:_.-]{1,100}$");
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of(
            "USD", "EUR", "GBP", "INR", "JPY", "CHF", "SGD", "AED"
    );

    public RegisterCorrespondentRouteCommand {
        if (routeReference == null || routeReference.isBlank()) {
            throw new RoutingValidationException("routeReference cannot be null or blank.");
        }
        if (routeReference.codePoints().anyMatch(Character::isISOControl)) {
            throw new RoutingValidationException("routeReference cannot contain ISO control characters.");
        }
        routeReference = routeReference.trim();
        if (routeReference.length() > MAX_ROUTE_REFERENCE_LENGTH
                || !ROUTE_REFERENCE_PATTERN.matcher(routeReference).matches()) {
            throw new RoutingValidationException(
                    "routeReference must be 1..100 characters matching [A-Za-z0-9:_.-]."
            );
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
        if (!SUPPORTED_CURRENCIES.contains(currency.getCode())) {
            throw new RoutingValidationException("Unsupported routing currency: " + currency.getCode());
        }
        if (clearingAccountReference == null || clearingAccountReference.isBlank()) {
            throw new RoutingValidationException("clearingAccountReference cannot be null or blank.");
        }
        if (RoutingBics.hasLeadingOrTrailingWhitespace(clearingAccountReference)) {
            throw new RoutingValidationException(
                    "clearingAccountReference cannot begin or end with whitespace."
            );
        }
        if (clearingAccountReference.codePoints().anyMatch(Character::isISOControl)) {
            throw new RoutingValidationException(
                    "clearingAccountReference cannot contain ISO control characters."
            );
        }
        if (clearingAccountReference.length() > MAX_CLEARING_ACCOUNT_REFERENCE_LENGTH) {
            throw new RoutingValidationException(
                    "clearingAccountReference cannot exceed " + MAX_CLEARING_ACCOUNT_REFERENCE_LENGTH + " characters."
            );
        }
        destinationBic = RoutingBics.canonicalize(destinationBic);
        correspondentBic = RoutingBics.canonicalize(correspondentBic);
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw new RoutingValidationException(
                    "priority must be between " + MIN_PRIORITY + " and " + MAX_PRIORITY + "."
            );
        }
    }
}
