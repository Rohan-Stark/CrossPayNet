package com.crosspaynet.payment.contracts;

/**
 * Command to record a confirmed post-commitment pre-settlement failure
 * ({@link PaymentStatus#EXECUTING} or {@link PaymentStatus#PENDING_EXTERNAL} -> {@link PaymentStatus#FAILED}).
 */
public record FailPaymentCommand(
        PaymentId paymentId,
        PaymentReasonCode reasonCode,
        String reasonDetail
) {

    public FailPaymentCommand {
        if (paymentId == null) {
            throw new PaymentValidationException("paymentId cannot be null.");
        }
        if (reasonCode == null) {
            throw new PaymentValidationException("reasonCode cannot be null.");
        }
        if (!reasonCode.isPostCommitmentFailureReason()) {
            throw new PaymentValidationException(
                    "FailPaymentCommand requires a post-commitment failure reasonCode "
                            + "(EXTERNAL_NETWORK_REJECTED or OPERATIONAL_FAILURE), but got: " + reasonCode
            );
        }
        reasonDetail = CancelPaymentCommand.normalizeOptionalReasonDetail(reasonDetail);
    }
}
