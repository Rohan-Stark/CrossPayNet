package com.crosspaynet.messaging.domain;

import com.crosspaynet.messaging.contracts.MessagingException;

/**
 * Thrown when a persisted {@code PaymentMessage} row fails domain restoration or
 * payload digest integrity verification.
 */
public class PersistedMessageStateException extends MessagingException {

    public PersistedMessageStateException(String message) {
        super(message);
    }

    public PersistedMessageStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
