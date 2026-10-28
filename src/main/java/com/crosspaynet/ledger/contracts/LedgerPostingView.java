package com.crosspaynet.ledger.contracts;

import com.crosspaynet.common.money.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * Minimal read-only view of a ledger transaction exposed across module boundaries.
 *
 * @param transactionId authoritative ledger transaction identifier
 * @param totalAmount   total monetary amount and currency of the transaction
 */
public record LedgerPostingView(
        UUID transactionId,
        Money totalAmount
) {
    public LedgerPostingView {
        Objects.requireNonNull(transactionId, "transactionId cannot be null.");
        Objects.requireNonNull(totalAmount, "totalAmount cannot be null.");
    }
}
