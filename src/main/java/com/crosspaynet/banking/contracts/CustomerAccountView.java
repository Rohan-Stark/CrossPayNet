package com.crosspaynet.banking.contracts;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;

import java.util.Objects;

/**
 * Read-only contract representation of a customer account.
 */
public record CustomerAccountView(
        AccountId accountId,
        String maskedAccountNumber,
        Currency currency,
        String status,
        Money availableBalance,
        Money reservedAmount,
        Money totalBalance
) {
    public CustomerAccountView {
        Objects.requireNonNull(accountId, "accountId cannot be null.");
        Objects.requireNonNull(maskedAccountNumber, "maskedAccountNumber cannot be null.");
        Objects.requireNonNull(currency, "currency cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(availableBalance, "availableBalance cannot be null.");
        Objects.requireNonNull(reservedAmount, "reservedAmount cannot be null.");
        Objects.requireNonNull(totalBalance, "totalBalance cannot be null.");
    }
}
