package com.crosspaynet.messaging.contracts;

/**
 * Thrown when a messaging request conflicts with an existing persisted message attempt or
 * payload digest.
 */
public class MessageConflictException extends MessagingValidationException {

    public MessageConflictException(String message) {
        super(message);
    }

    public MessageConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
