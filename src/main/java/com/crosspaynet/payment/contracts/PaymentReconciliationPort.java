package com.crosspaynet.payment.contracts;

import java.time.Instant;
import java.util.Set;

/**
 * Public read-only reconciliation query port exposed by the Payment module.
 *
 * <p>Enables bounded, deterministic keyset-paginated scans over payments matching specific
 * statuses and cutoff thresholds without exposing internal persistence entities or broad PII.
 */
public interface PaymentReconciliationPort {

    /**
     * Scans payments matching the provided statuses that have not been updated since {@code cutoffTime}.
     *
     * @param statuses   non-empty set of payment statuses to match
     * @param cutoffTime upper bound for {@code updatedAt} (inclusive)
     * @param cursor     optional continuation cursor; if {@code null}, scan starts from the beginning
     * @param limit      maximum number of records to return (bounded between 1 and a system maximum)
     * @return a {@link PaymentReconciliationPage} containing records and continuation information
     */
    PaymentReconciliationPage findPaymentsForReconciliation(
            Set<PaymentStatus> statuses,
            Instant cutoffTime,
            PaymentReconciliationCursor cursor,
            int limit
    );
}
