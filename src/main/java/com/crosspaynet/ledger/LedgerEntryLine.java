package com.crosspaynet.ledger;

import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.domain.LedgerLimits;

/**
 * Caller-supplied debit or credit line item in a {@link PostLedgerTransactionCommand}.
 */
public record LedgerEntryLine(
        LedgerAccountRef account,
        EntryDirection direction,
        Money amount
) {

    public LedgerEntryLine {
        if (account == null) {
            throw new LedgerValidationException("LedgerEntryLine account cannot be null.");
        }
        if (direction == null) {
            throw new LedgerValidationException("LedgerEntryLine direction cannot be null.");
        }
        if (amount == null) {
            throw new LedgerValidationException("LedgerEntryLine amount cannot be null.");
        }
        if (!amount.isPositive()) {
            throw new LedgerValidationException("LedgerEntryLine amount must be strictly positive.");
        }
        if (amount.getAmount().compareTo(LedgerLimits.MAX_AMOUNT) > 0) {
            throw new LedgerValidationException(
                    "LedgerEntryLine amount (" + amount.getAmount().toPlainString()
                            + ") exceeds NUMERIC(19,4) maximum (" + LedgerLimits.MAX_AMOUNT.toPlainString() + ")."
            );
        }
    }
}
