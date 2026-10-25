package com.crosspaynet.banking.domain;

/**
 * Unchecked exception thrown when persisted Banking state violates aggregate or entity invariants
 * during domain reconstruction or persistence synchronization.
 *
 * <p>This exception indicates structural or data-integrity corruption and must always cause the
 * active transaction to roll back.
 */
public class PersistedBankingStateException extends IllegalStateException {
    public PersistedBankingStateException(String message) {
        super(message);
    }

    public PersistedBankingStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
