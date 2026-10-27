package com.crosspaynet.payment.contracts;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Keyset-paginated result containing minimal reconciliation records and continuation metadata.
 */
public record PaymentReconciliationPage(
        List<PaymentReconciliationRecord> items,
        boolean hasMore,
        Optional<PaymentReconciliationCursor> nextCursor
) {

    public PaymentReconciliationPage {
        items = items != null ? List.copyOf(items) : List.of();
        Objects.requireNonNull(nextCursor, "nextCursor cannot be null.");
    }

    public static PaymentReconciliationPage empty() {
        return new PaymentReconciliationPage(List.of(), false, Optional.empty());
    }
}
