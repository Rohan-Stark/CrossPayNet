package com.crosspaynet.payment.contracts;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal read-only projection for reconciliation scanning across module boundaries.
 *
 * <p>Intentionally omits customer PII (e.g. beneficiary name, account number, BIC)
 * while providing necessary correlation identifiers, monetary values, and lifecycle timestamps.
 */
public record PaymentReconciliationRecord(
        PaymentId paymentId,
        PaymentStatus status,
        AccountId sourceAccountId,
        Money amount,
        Optional<ReservationToken> reservationToken,
        Optional<String> routeReference,
        Optional<String> externalDispatchReference,
        Optional<UUID> ledgerTransactionId,
        Optional<PaymentReasonCode> statusReasonCode,
        Instant createdAt,
        Instant updatedAt
) {

    public PaymentReconciliationRecord {
        Objects.requireNonNull(paymentId, "paymentId cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId cannot be null.");
        Objects.requireNonNull(amount, "amount cannot be null.");
        Objects.requireNonNull(reservationToken, "reservationToken Optional cannot be null.");
        Objects.requireNonNull(routeReference, "routeReference Optional cannot be null.");
        Objects.requireNonNull(externalDispatchReference, "externalDispatchReference Optional cannot be null.");
        Objects.requireNonNull(ledgerTransactionId, "ledgerTransactionId Optional cannot be null.");
        Objects.requireNonNull(statusReasonCode, "statusReasonCode Optional cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");
        if (updatedAt.isBefore(createdAt)) {
            throw new PaymentValidationException("updatedAt cannot be before createdAt.");
        }
    }
}
