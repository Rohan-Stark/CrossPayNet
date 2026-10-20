package com.crosspaynet.ledger;

import java.util.Objects;

/**
 * Thrown when a caller reuses an existing {@link LedgerPostingKey} with a different financial payload fingerprint.
 */
public class LedgerPostingKeyConflictException extends LedgerValidationException {

    private final LedgerPostingKey postingKey;
    private final LedgerTransactionId existingTransactionId;

    public LedgerPostingKeyConflictException(
            LedgerPostingKey postingKey,
            LedgerTransactionId existingTransactionId,
            String message) {
        super(message);
        this.postingKey = Objects.requireNonNull(postingKey, "postingKey cannot be null");
        this.existingTransactionId = Objects.requireNonNull(existingTransactionId, "existingTransactionId cannot be null");
    }

    public LedgerPostingKeyConflictException(
            LedgerPostingKey postingKey,
            LedgerTransactionId existingTransactionId) {
        this(
                postingKey,
                existingTransactionId,
                "Posting key '" + postingKey + "' is already associated with transaction '"
                        + existingTransactionId + "' with a different financial fingerprint."
        );
    }

    public LedgerPostingKey getPostingKey() {
        return postingKey;
    }

    public LedgerTransactionId getExistingTransactionId() {
        return existingTransactionId;
    }
}
