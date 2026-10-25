package com.crosspaynet.settlement.contracts;

/**
 * Thrown when a settlement command or invariant check fails validation.
 */
public class SettlementValidationException extends SettlementException {

    public SettlementValidationException(String message) {
        super(message);
    }

    public SettlementValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
