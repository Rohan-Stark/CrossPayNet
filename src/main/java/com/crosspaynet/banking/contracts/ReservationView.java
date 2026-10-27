package com.crosspaynet.banking.contracts;

import com.crosspaynet.common.money.Money;

import java.util.Objects;

/**
 * Immutable read-only view of a fund reservation exposed across module boundaries for reconciliation.
 */
public record ReservationView(
        ReservationToken reservationToken,
        AccountId accountId,
        ReservationStatus status,
        Money amount,
        PaymentReference paymentReference
) {

    public ReservationView {
        Objects.requireNonNull(reservationToken, "reservationToken cannot be null.");
        Objects.requireNonNull(accountId, "accountId cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(amount, "amount cannot be null.");
        Objects.requireNonNull(paymentReference, "paymentReference cannot be null.");
    }
}
