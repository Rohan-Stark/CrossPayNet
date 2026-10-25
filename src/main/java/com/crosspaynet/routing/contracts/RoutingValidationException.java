package com.crosspaynet.routing.contracts;

/**
 * Thrown when a routing command, query, or value object violates domain validation rules.
 */
public class RoutingValidationException extends RoutingException {

    public RoutingValidationException(String message) {
        super(message);
    }

    public RoutingValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
