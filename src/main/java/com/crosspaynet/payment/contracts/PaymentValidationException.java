package com.crosspaynet.payment.contracts;

/**
 * Base exception for Payment domain, invariant, and business validation errors.
 */
public class PaymentValidationException extends PaymentException {

    public PaymentValidationException(String message) {
        super(message);
    }

    public PaymentValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
