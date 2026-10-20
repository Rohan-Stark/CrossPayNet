package com.crosspaynet.ledger;

/**
 * Base exception for domain, invariant, and business validation errors in the Ledger module.
 */
public class LedgerValidationException extends LedgerException {

    public LedgerValidationException(String message) {
        super(message);
    }

    public LedgerValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
