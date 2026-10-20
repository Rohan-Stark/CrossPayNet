package com.crosspaynet.ledger;

import com.crosspaynet.ledger.domain.LedgerLimits;

/**
 * Command to create an immutable compensating {@link LedgerTransactionType#REVERSAL} transaction
 * mirroring an existing posted {@link LedgerTransactionType#STANDARD} transaction.
 */
public record ReverseLedgerTransactionCommand(
        LedgerTransactionId originalTransactionId,
        String reversalReference,
        String reason
) {

    public ReverseLedgerTransactionCommand {
        if (originalTransactionId == null) {
            throw new LedgerValidationException("originalTransactionId cannot be null.");
        }
        if (reversalReference == null || reversalReference.trim().isEmpty()) {
            throw new LedgerValidationException("reversalReference cannot be null or blank.");
        }
        reversalReference = reversalReference.trim();
        if (reversalReference.length() > LedgerLimits.MAX_EXTERNAL_REFERENCE_LENGTH) {
            throw new LedgerValidationException(
                    "reversalReference length (" + reversalReference.length() + ") exceeds maximum of "
                            + LedgerLimits.MAX_EXTERNAL_REFERENCE_LENGTH + " characters."
            );
        }

        if (reason != null) {
            String trimmedReason = reason.trim();
            if (trimmedReason.isEmpty()) {
                reason = null;
            } else {
                if (trimmedReason.length() > LedgerLimits.MAX_DESCRIPTION_LENGTH) {
                    throw new LedgerValidationException(
                            "reason length (" + trimmedReason.length() + ") exceeds maximum of "
                                    + LedgerLimits.MAX_DESCRIPTION_LENGTH + " characters."
                    );
                }
                reason = trimmedReason;
            }
        }
    }
}
