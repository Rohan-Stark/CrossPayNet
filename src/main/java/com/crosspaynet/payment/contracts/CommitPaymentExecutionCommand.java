package com.crosspaynet.payment.contracts;

/**
 * Command to commit a payment in {@link PaymentStatus#ROUTING} to execution ({@link PaymentStatus#EXECUTING})
 * with a selected correspondent route reference.
 */
public record CommitPaymentExecutionCommand(
        PaymentId paymentId,
        String routeReference
) {

    public static final int MAX_ROUTE_REFERENCE_LENGTH = 100;

    public CommitPaymentExecutionCommand {
        if (paymentId == null) {
            throw new PaymentValidationException("paymentId cannot be null.");
        }
        routeReference = validateRequiredReference(
                routeReference,
                "routeReference",
                MAX_ROUTE_REFERENCE_LENGTH
        );
    }

    static String validateRequiredReference(String raw, String fieldName, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw new PaymentValidationException(fieldName + " cannot be null or blank.");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new PaymentValidationException(
                    fieldName + " length (" + trimmed.length() + ") exceeds maximum of " + maxLength + " characters."
            );
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new PaymentValidationException(fieldName + " cannot contain ISO control characters.");
        }
        return trimmed;
    }
}
