package com.crosspaynet.settlement.contracts;

/**
 * Thrown when a settlement request conflicts with an existing settled Payment or Ledger record.
 */
public class SettlementConflictException extends SettlementValidationException {

    public SettlementConflictException(String message) {
        super(message);
    }

    public SettlementConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
