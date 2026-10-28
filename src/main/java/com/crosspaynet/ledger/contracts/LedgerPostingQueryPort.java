package com.crosspaynet.ledger.contracts;

import java.util.Optional;

/**
 * Public read-only contract for looking up ledger postings by canonical posting key across module boundaries.
 */
public interface LedgerPostingQueryPort {

    /**
     * Looks up an authoritative ledger posting by its canonical posting key.
     *
     * <p>The input must represent the canonical posting key (e.g. {@code PAYMENT_SETTLEMENT:<paymentId>})
     * and is validated against Ledger domain invariants inside the Ledger module.
     *
     * @param canonicalPostingKey canonical posting key
     * @return minimal posting view if present, empty otherwise
     */
    Optional<LedgerPostingView> findPostingByKey(String canonicalPostingKey);
}
