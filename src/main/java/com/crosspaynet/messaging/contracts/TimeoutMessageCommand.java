package com.crosspaynet.messaging.contracts;

/**
 * Command to record a network transmission timeout ({@code SENT -> TIMEOUT}) on a dispatched
 * {@code PaymentMessage}, preserving the underlying {@code Payment} in {@code PENDING_EXTERNAL}
 * so a subsequent message attempt can be dispatched.
 */
public record TimeoutMessageCommand(
        MessageId messageId,
        String timeoutReason
) {

    public static final int MAX_TIMEOUT_REASON_LENGTH = 500;

    public TimeoutMessageCommand {
        if (messageId == null) {
            throw new MessagingValidationException("messageId cannot be null.");
        }
        if (timeoutReason != null && !timeoutReason.isBlank()) {
            if (timeoutReason.codePoints().anyMatch(Character::isISOControl)) {
                throw new MessagingValidationException(
                        "timeoutReason must not contain ISO control characters."
                );
            }
            timeoutReason = timeoutReason.trim();
            if (timeoutReason.length() > MAX_TIMEOUT_REASON_LENGTH) {
                throw new MessagingValidationException(
                        "timeoutReason cannot exceed " + MAX_TIMEOUT_REASON_LENGTH + " characters."
                );
            }
        } else if (timeoutReason != null && timeoutReason.codePoints().anyMatch(Character::isISOControl)) {
            throw new MessagingValidationException(
                    "timeoutReason must not contain ISO control characters."
            );
        } else {
            timeoutReason = null;
        }
    }
}
