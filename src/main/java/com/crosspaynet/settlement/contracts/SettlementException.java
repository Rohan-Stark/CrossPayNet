package com.crosspaynet.settlement.contracts;

/**
 * Base runtime exception for all Settlement module failures.
 */
public class SettlementException extends RuntimeException {

    public SettlementException(String message) {
        super(message);
    }

    public SettlementException(String message, Throwable cause) {
        super(message, cause);
    }
}
