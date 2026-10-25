package com.crosspaynet.banking.contracts;

public class InvalidAccountStateException extends BankingException {
    public InvalidAccountStateException(String message) {
        super(message);
    }
}
