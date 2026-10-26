package com.crosspaynet.events.contracts;

/**
 * Thrown when an incoming distributed event or envelope fails schema/semantic validation.
 * Unretryable poison pill condition; routed directly to Dead Letter Topic.
 */
public class EventValidationException extends RuntimeException {

    public EventValidationException(String message) {
        super(message);
    }

    public EventValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
