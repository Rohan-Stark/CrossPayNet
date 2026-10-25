package com.crosspaynet.messaging.contracts;

/**
 * Command to record a positive peer acknowledgment ({@code SENT -> ACKNOWLEDGED}) on a
 * dispatched {@code PaymentMessage}.
 */
public record AcknowledgeMessageCommand(
        MessageId messageId,
        String acknowledgmentReference
) {

    public static final int MAX_ACKNOWLEDGMENT_REFERENCE_LENGTH = 100;

    public AcknowledgeMessageCommand {
        if (messageId == null) {
            throw new MessagingValidationException("messageId cannot be null.");
        }
        if (acknowledgmentReference == null || acknowledgmentReference.isBlank()) {
            throw new MessagingValidationException("acknowledgmentReference cannot be null or blank.");
        }
        if (acknowledgmentReference.codePoints().anyMatch(Character::isISOControl)) {
            throw new MessagingValidationException(
                    "acknowledgmentReference must not contain ISO control characters."
            );
        }
        acknowledgmentReference = acknowledgmentReference.trim();
        if (acknowledgmentReference.length() > MAX_ACKNOWLEDGMENT_REFERENCE_LENGTH) {
            throw new MessagingValidationException(
                    "acknowledgmentReference cannot exceed " + MAX_ACKNOWLEDGMENT_REFERENCE_LENGTH + " characters."
            );
        }
    }
}
