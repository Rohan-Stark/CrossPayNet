package com.crosspaynet.ledger;

/**
 * Classifies a posted Ledger transaction as a standard posting or a compensating reversal.
 */
public enum LedgerTransactionType {
    STANDARD,
    REVERSAL
}
