package com.crosspaynet.messaging.contracts;

/**
 * Base runtime exception for all Messaging module failures.
 */
public class MessagingException extends RuntimeException {

    public MessagingException(String message) {
        super(message);
    }

    public MessagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
