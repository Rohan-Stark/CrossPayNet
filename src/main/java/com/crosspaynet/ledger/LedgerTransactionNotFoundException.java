package com.crosspaynet.ledger;

import java.util.Objects;

/**
 * Thrown when a referenced original transaction does not exist in the Ledger.
 */
public class LedgerTransactionNotFoundException extends LedgerValidationException {

    private final LedgerTransactionId transactionId;

    public LedgerTransactionNotFoundException(LedgerTransactionId transactionId, String message) {
        super(message);
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId cannot be null");
    }

    public LedgerTransactionNotFoundException(LedgerTransactionId transactionId) {
        this(transactionId, "Ledger transaction not found: " + transactionId);
    }

    public LedgerTransactionId getTransactionId() {
        return transactionId;
    }
}
