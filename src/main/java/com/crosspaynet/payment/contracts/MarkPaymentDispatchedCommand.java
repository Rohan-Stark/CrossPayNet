package com.crosspaynet.payment.contracts;

/**
 * Command to record that a committed payment in {@link PaymentStatus#EXECUTING} has been
 * dispatched to the external messaging network ({@link PaymentStatus#PENDING_EXTERNAL}).
 */
public record MarkPaymentDispatchedCommand(
        PaymentId paymentId,
        String externalDispatchReference
) {

    public static final int MAX_EXTERNAL_DISPATCH_REFERENCE_LENGTH = 100;

    public MarkPaymentDispatchedCommand {
        if (paymentId == null) {
            throw new PaymentValidationException("paymentId cannot be null.");
        }
        externalDispatchReference = CommitPaymentExecutionCommand.validateRequiredReference(
                externalDispatchReference,
                "externalDispatchReference",
                MAX_EXTERNAL_DISPATCH_REFERENCE_LENGTH
        );
    }
}
