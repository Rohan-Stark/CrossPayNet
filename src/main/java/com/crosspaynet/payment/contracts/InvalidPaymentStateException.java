package com.crosspaynet.payment.contracts;

/**
 * Thrown when a requested state transition or operation is invalid for the Payment's current state.
 */
public class InvalidPaymentStateException extends PaymentValidationException {

    public InvalidPaymentStateException(String message) {
        super(message);
    }
}
