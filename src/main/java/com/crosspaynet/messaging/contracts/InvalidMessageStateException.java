package com.crosspaynet.messaging.contracts;

/**
 * Thrown when a messaging operation is not permitted in the current {@link PaymentMessageStatus}
 * or payment state.
 */
public class InvalidMessageStateException extends MessagingException {

    public InvalidMessageStateException(String message) {
        super(message);
    }
}
