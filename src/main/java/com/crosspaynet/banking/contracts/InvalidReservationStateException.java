package com.crosspaynet.banking.contracts;

public class InvalidReservationStateException extends BankingException {
    public InvalidReservationStateException(String message) {
        super(message);
    }
}
