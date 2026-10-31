package com.crosspaynet.security.portal.web;

import com.crosspaynet.banking.contracts.CustomerAccountView;
import com.crosspaynet.security.DataMasker;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Customer-facing response DTO for account overview.
 */
public record CustomerAccountResponse(
        UUID accountId,
        String maskedAccountNumber,
        String currency,
        String status,
        BigDecimal availableBalance,
        BigDecimal reservedAmount,
        BigDecimal totalBalance
) {
    public CustomerAccountResponse {
        Objects.requireNonNull(accountId, "accountId cannot be null.");
        Objects.requireNonNull(maskedAccountNumber, "maskedAccountNumber cannot be null.");
        Objects.requireNonNull(currency, "currency cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(availableBalance, "availableBalance cannot be null.");
        Objects.requireNonNull(reservedAmount, "reservedAmount cannot be null.");
        Objects.requireNonNull(totalBalance, "totalBalance cannot be null.");
    }

    public static CustomerAccountResponse from(CustomerAccountView view) {
        if (view == null) return null;
        return new CustomerAccountResponse(
                view.accountId().getValue(),
                view.maskedAccountNumber() != null
                        ? view.maskedAccountNumber()
                        : DataMasker.maskAccountNumber(view.accountId().getValue().toString()),
                view.currency().getCode(),
                view.status(),
                view.availableBalance().getAmount(),
                view.reservedAmount().getAmount(),
                view.totalBalance().getAmount()
        );
    }
}
