package com.crosspaynet.ledger;

import java.util.Objects;

/**
 * Thrown when an original transaction has already been reversed with a conflicting reversal reference.
 */
public class LedgerReversalConflictException extends LedgerValidationException {

    private final LedgerTransactionId originalTransactionId;
    private final LedgerTransactionId existingReversalTransactionId;

    public LedgerReversalConflictException(
            LedgerTransactionId originalTransactionId,
            LedgerTransactionId existingReversalTransactionId,
            String message) {
        super(message);
        this.originalTransactionId = Objects.requireNonNull(originalTransactionId, "originalTransactionId cannot be null");
        this.existingReversalTransactionId = Objects.requireNonNull(
                existingReversalTransactionId,
                "existingReversalTransactionId cannot be null"
        );
    }

    public LedgerReversalConflictException(
            LedgerTransactionId originalTransactionId,
            LedgerTransactionId existingReversalTransactionId) {
        this(
                originalTransactionId,
                existingReversalTransactionId,
                "Transaction '" + originalTransactionId + "' has already been reversed by transaction '"
                        + existingReversalTransactionId + "' with a different reversal reference."
        );
    }

    public LedgerTransactionId getOriginalTransactionId() {
        return originalTransactionId;
    }

    public LedgerTransactionId getExistingReversalTransactionId() {
        return existingReversalTransactionId;
    }
}
