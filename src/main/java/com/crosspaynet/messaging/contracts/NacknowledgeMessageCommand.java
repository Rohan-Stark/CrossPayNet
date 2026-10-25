package com.crosspaynet.messaging.contracts;

import java.util.regex.Pattern;

/**
 * Command to record a negative peer acknowledgment ({@code SENT -> NACKNOWLEDGED}) on a
 * dispatched {@code PaymentMessage} and coordinate atomic Payment failure
 * ({@code EXTERNAL_NETWORK_REJECTED}).
 */
public record NacknowledgeMessageCommand(
        MessageId messageId,
        String peerRejectionCode,
        String peerRejectionReason
) {

    public static final int MAX_PEER_REJECTION_CODE_LENGTH = 35;
    public static final int MAX_PEER_REJECTION_REASON_LENGTH = 500;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,35}$");

    public NacknowledgeMessageCommand {
        if (messageId == null) {
            throw new MessagingValidationException("messageId cannot be null.");
        }
        if (peerRejectionCode == null || peerRejectionCode.isBlank()) {
            throw new MessagingValidationException("peerRejectionCode cannot be null or blank.");
        }
        peerRejectionCode = peerRejectionCode.trim();
        if (peerRejectionCode.length() > MAX_PEER_REJECTION_CODE_LENGTH
                || !CODE_PATTERN.matcher(peerRejectionCode).matches()) {
            throw new MessagingValidationException(
                    "peerRejectionCode must be 1..35 characters matching [A-Za-z0-9_.-]."
            );
        }
        if (peerRejectionReason != null && !peerRejectionReason.isBlank()) {
            if (peerRejectionReason.codePoints().anyMatch(Character::isISOControl)) {
                throw new MessagingValidationException(
                        "peerRejectionReason must not contain ISO control characters."
                );
            }
            peerRejectionReason = peerRejectionReason.trim();
            if (peerRejectionReason.length() > MAX_PEER_REJECTION_REASON_LENGTH) {
                throw new MessagingValidationException(
                        "peerRejectionReason cannot exceed " + MAX_PEER_REJECTION_REASON_LENGTH + " characters."
                );
            }
        } else if (peerRejectionReason != null && peerRejectionReason.codePoints().anyMatch(Character::isISOControl)) {
            throw new MessagingValidationException(
                    "peerRejectionReason must not contain ISO control characters."
            );
        } else {
            peerRejectionReason = null;
        }
    }
}
