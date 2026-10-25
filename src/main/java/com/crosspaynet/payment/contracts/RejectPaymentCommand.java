package com.crosspaynet.payment.contracts;

/**
 * Command to reject a payment prior to the Financial Commitment Point
 * (e.g., due to compliance rejection or lack of an available route).
 */
public record RejectPaymentCommand(
        PaymentId paymentId,
        PaymentReasonCode reasonCode,
        String reasonDetail
) {

    public RejectPaymentCommand {
        if (paymentId == null) {
            throw new PaymentValidationException("paymentId cannot be null.");
        }
        if (reasonCode == null) {
            throw new PaymentValidationException("reasonCode cannot be null.");
        }
        if (!reasonCode.isPreCommitmentRejectionReason()) {
            throw new PaymentValidationException(
                    "RejectPaymentCommand requires a pre-commitment rejection reasonCode "
                            + "(COMPLIANCE_REJECTED or NO_ROUTE_AVAILABLE), but got: " + reasonCode
            );
        }
        reasonDetail = CancelPaymentCommand.normalizeOptionalReasonDetail(reasonDetail);
    }
}
