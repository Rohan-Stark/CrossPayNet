package com.crosspaynet.messaging.contracts;

/**
 * Thrown when a messaging command or value-object invariant fails validation.
 */
public class MessagingValidationException extends MessagingException {

    public MessagingValidationException(String message) {
        super(message);
    }

    public MessagingValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
