package com.crosspaynet.routing.contracts;

/**
 * Thrown when a route registration conflicts with an existing {@code routeReference}
 * or {@code (destinationBic, currency, priority)} combination, or when an already-committed
 * payment route reference cannot be resolved consistently.
 */
public class RouteConflictException extends RoutingValidationException {

    public RouteConflictException(String message) {
        super(message);
    }

    public RouteConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
