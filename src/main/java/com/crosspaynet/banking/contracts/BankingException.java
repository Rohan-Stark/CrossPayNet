package com.crosspaynet.banking.contracts;

/**
 * Base runtime exception for Banking module business rule rejections.
 */
public class BankingException extends RuntimeException {
    public BankingException(String message) {
        super(message);
    }

    public BankingException(String message, Throwable cause) {
        super(message, cause);
    }
}
