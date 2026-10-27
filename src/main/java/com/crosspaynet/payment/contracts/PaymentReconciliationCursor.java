package com.crosspaynet.payment.contracts;

import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic keyset cursor for keyset pagination of payments during reconciliation.
 */
public record PaymentReconciliationCursor(
        Instant updatedAt,
        PaymentId paymentId
) {

    public PaymentReconciliationCursor {
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null in cursor.");
        Objects.requireNonNull(paymentId, "paymentId cannot be null in cursor.");
    }
}
