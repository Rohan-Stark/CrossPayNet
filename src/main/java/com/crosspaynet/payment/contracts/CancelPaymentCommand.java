package com.crosspaynet.payment.contracts;

/**
 * Command to cancel a payment prior to the Financial Commitment Point.
 */
public record CancelPaymentCommand(
        PaymentId paymentId,
        String reasonDetail
) {

    public static final int MAX_REASON_DETAIL_LENGTH = 500;

    public CancelPaymentCommand {
        if (paymentId == null) {
            throw new PaymentValidationException("paymentId cannot be null.");
        }
        reasonDetail = normalizeOptionalReasonDetail(reasonDetail);
    }

    static String normalizeOptionalReasonDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String trimmed = detail.trim();
        if (trimmed.length() > MAX_REASON_DETAIL_LENGTH) {
            throw new PaymentValidationException(
                    "reasonDetail length (" + trimmed.length() + ") exceeds maximum of "
                            + MAX_REASON_DETAIL_LENGTH + " characters."
            );
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new PaymentValidationException("reasonDetail cannot contain ISO control characters.");
        }
        return trimmed;
    }
}
