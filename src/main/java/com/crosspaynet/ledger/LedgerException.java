package com.crosspaynet.ledger;

/**
 * Root runtime exception for the Ledger module.
 */
public class LedgerException extends RuntimeException {

    public LedgerException(String message) {
        super(message);
    }

    public LedgerException(String message, Throwable cause) {
        super(message, cause);
    }
}
