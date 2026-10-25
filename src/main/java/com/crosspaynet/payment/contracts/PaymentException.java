package com.crosspaynet.payment.contracts;

/**
 * Root runtime exception for all errors originating from the Payment module.
 */
public class PaymentException extends RuntimeException {

    public PaymentException(String message) {
        super(message);
    }

    public PaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
