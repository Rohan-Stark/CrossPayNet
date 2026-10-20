package com.crosspaynet.ledger;

import java.time.Instant;

/**
 * Result returned after posting or reversing a Ledger transaction.
 */
public record LedgerPostingResult(
        LedgerTransactionId transactionId,
        PostingOutcome outcome,
        Instant postedAt
) {

    public LedgerPostingResult {
        if (transactionId == null) {
            throw new LedgerValidationException("transactionId cannot be null.");
        }
        if (outcome == null) {
            throw new LedgerValidationException("outcome cannot be null.");
        }
        if (postedAt == null) {
            throw new LedgerValidationException("postedAt cannot be null.");
        }
    }
}
