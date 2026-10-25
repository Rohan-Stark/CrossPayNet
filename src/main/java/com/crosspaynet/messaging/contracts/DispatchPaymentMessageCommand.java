package com.crosspaynet.messaging.contracts;

import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentId;

/**
 * Command to generate, integrity-sign, and dispatch a {@code pacs.008}-inspired
 * {@code PaymentMessage} transmission attempt ({@code GENERATED -> SIGNED -> SENT}).
 */
public record DispatchPaymentMessageCommand(
        PaymentId paymentId,
        int attemptNumber,
        BicCode senderBic
) {

    public DispatchPaymentMessageCommand {
        if (paymentId == null) {
            throw new MessagingValidationException("paymentId cannot be null.");
        }
        if (attemptNumber < 1 || attemptNumber > 100) {
            throw new MessagingValidationException("attemptNumber must be between 1 and 100.");
        }
        if (senderBic == null) {
            throw new MessagingValidationException("senderBic cannot be null.");
        }
    }
}
