package com.crosspaynet.banking.contracts;

public class InvalidMoneyException extends BankingException {
    public InvalidMoneyException(String message) {
        super(message);
    }
}
