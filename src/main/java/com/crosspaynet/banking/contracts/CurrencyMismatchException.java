package com.crosspaynet.banking.contracts;

public class CurrencyMismatchException extends BankingException {
    public CurrencyMismatchException(String message) {
        super(message);
    }
}
