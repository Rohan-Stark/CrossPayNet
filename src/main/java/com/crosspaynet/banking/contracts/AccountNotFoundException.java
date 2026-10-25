package com.crosspaynet.banking.contracts;

public class AccountNotFoundException extends BankingException {
    private final AccountId accountId;

    public AccountNotFoundException(AccountId accountId) {
        super("Account not found: " + accountId);
        this.accountId = accountId;
    }

    public AccountNotFoundException(String message) {
        super(message);
        this.accountId = null;
    }

    public AccountId getAccountId() {
        return accountId;
    }
}
