package com.crosspaynet.ledger.domain;

import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.EntryDirection;
import com.crosspaynet.ledger.LedgerAccountRef;
import com.crosspaynet.ledger.LedgerEntryLine;
import com.crosspaynet.ledger.LedgerEntryView;
import com.crosspaynet.ledger.LedgerValidationException;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable child entity belonging to a {@link LedgerTransaction} aggregate root (ADR-006).
 */
public final class LedgerEntry {

    private final UUID entryId;
    private final int lineNumber;
    private final LedgerAccountRef account;
    private final EntryDirection direction;
    private final Money amount;

    public LedgerEntry(
            UUID entryId,
            int lineNumber,
            LedgerAccountRef account,
            EntryDirection direction,
            Money amount) {
        if (entryId == null) {
            throw new LedgerValidationException("LedgerEntry entryId cannot be null.");
        }
        if (lineNumber < 1 || lineNumber > LedgerLimits.MAX_ENTRIES) {
            throw new LedgerValidationException(
                    "LedgerEntry lineNumber (" + lineNumber + ") must be between 1 and "
                            + LedgerLimits.MAX_ENTRIES + "."
            );
        }
        if (account == null) {
            throw new LedgerValidationException("LedgerEntry account cannot be null.");
        }
        if (direction == null) {
            throw new LedgerValidationException("LedgerEntry direction cannot be null.");
        }
        if (amount == null) {
            throw new LedgerValidationException("LedgerEntry amount cannot be null.");
        }
        SupportedCurrencyPolicy.requireSupported(amount.getCurrency());
        if (!amount.isPositive()) {
            throw new LedgerValidationException("LedgerEntry amount must be strictly positive.");
        }
        if (amount.getAmount().compareTo(LedgerLimits.MAX_AMOUNT) > 0) {
            throw new LedgerValidationException(
                    "LedgerEntry amount (" + amount.getAmount().toPlainString()
                            + ") exceeds NUMERIC(19,4) maximum (" + LedgerLimits.MAX_AMOUNT.toPlainString() + ")."
            );
        }

        this.entryId = entryId;
        this.lineNumber = lineNumber;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public LedgerAccountRef getAccount() {
        return account;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public Money getAmount() {
        return amount;
    }

    public LedgerEntryLine toEntryLine() {
        return new LedgerEntryLine(account, direction, amount);
    }

    public LedgerEntryView toView() {
        return new LedgerEntryView(entryId, lineNumber, account, direction, amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerEntry that = (LedgerEntry) o;
        return lineNumber == that.lineNumber
                && Objects.equals(entryId, that.entryId)
                && Objects.equals(account, that.account)
                && direction == that.direction
                && Objects.equals(amount, that.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entryId, lineNumber, account, direction, amount);
    }
}
