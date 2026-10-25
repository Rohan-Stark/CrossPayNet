package com.crosspaynet.payment.domain;

/**
 * Internal unchecked exception thrown when persisted Payment state violates domain or
 * state-machine invariants during aggregate restoration.
 *
 * <p>This exception represents data corruption or schema-constraint bypass and must never
 * be treated as a recoverable business validation exception or included in {@code noRollbackFor}.
 */
public class PersistedPaymentStateException extends IllegalStateException {

    public PersistedPaymentStateException(String message) {
        super(message);
    }

    public PersistedPaymentStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
