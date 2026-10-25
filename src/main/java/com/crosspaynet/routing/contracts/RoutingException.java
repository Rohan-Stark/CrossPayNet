package com.crosspaynet.routing.contracts;

/**
 * Root runtime exception for all Routing & Correspondent Banking module errors.
 */
public class RoutingException extends RuntimeException {

    public RoutingException(String message) {
        super(message);
    }

    public RoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
