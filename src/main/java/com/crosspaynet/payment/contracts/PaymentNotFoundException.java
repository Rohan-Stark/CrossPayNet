package com.crosspaynet.payment.contracts;

/**
 * Thrown when a requested {@link PaymentId} cannot be found.
 */
public class PaymentNotFoundException extends PaymentValidationException {

    public PaymentNotFoundException(String message) {
        super(message);
    }

    public PaymentNotFoundException(PaymentId paymentId) {
        super("Payment not found for id: " + paymentId);
    }
}
