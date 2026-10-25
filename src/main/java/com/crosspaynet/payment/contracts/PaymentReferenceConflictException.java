package com.crosspaynet.payment.contracts;

/**
 * Thrown when a {@code (sourceAccountId, paymentReference)} pair is reused with conflicting
 * payment instruction parameters (e.g., different amount, currency, or beneficiary details).
 */
public class PaymentReferenceConflictException extends PaymentValidationException {

    public PaymentReferenceConflictException(String message) {
        super(message);
    }
}
