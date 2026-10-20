package com.crosspaynet.ledger;

/**
 * Double-entry accounting direction for a Ledger entry line.
 */
public enum EntryDirection {
    DEBIT,
    CREDIT;

    /**
     * Returns the opposite accounting direction ({@code DEBIT <-> CREDIT}).
     */
    public EntryDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
