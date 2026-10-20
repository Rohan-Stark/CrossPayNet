package com.crosspaynet.ledger;

/**
 * Indicates whether a Ledger posting or reversal created a new transaction or matched an idempotent replay.
 */
public enum PostingOutcome {
    POSTED,
    ALREADY_POSTED
}
