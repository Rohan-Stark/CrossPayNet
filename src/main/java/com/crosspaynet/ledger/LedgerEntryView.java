package com.crosspaynet.ledger;

import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.domain.LedgerLimits;

import java.util.UUID;

/**
 * Immutable read-model view of a posted Ledger entry line.
 */
public record LedgerEntryView(
        UUID entryId,
        int lineNumber,
        LedgerAccountRef account,
        EntryDirection direction,
        Money amount
) {

    public LedgerEntryView {
        if (entryId == null) {
            throw new LedgerValidationException("entryId cannot be null.");
        }
        if (lineNumber < 1 || lineNumber > LedgerLimits.MAX_ENTRIES) {
            throw new LedgerValidationException(
                    "lineNumber (" + lineNumber + ") must be between 1 and " + LedgerLimits.MAX_ENTRIES + "."
            );
        }
        if (account == null) {
            throw new LedgerValidationException("account cannot be null.");
        }
        if (direction == null) {
            throw new LedgerValidationException("direction cannot be null.");
        }
        if (amount == null || !amount.isPositive()) {
            throw new LedgerValidationException("amount must be non-null and strictly positive.");
        }
        if (amount.getAmount().compareTo(LedgerLimits.MAX_AMOUNT) > 0) {
            throw new LedgerValidationException(
                    "amount (" + amount.getAmount().toPlainString() + ") exceeds NUMERIC(19,4) maximum."
            );
        }
    }
}
