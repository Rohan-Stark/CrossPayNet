package com.crosspaynet.banking.domain;

import com.crosspaynet.banking.contracts.InvalidReservationStateException;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Money;

public class FundReservation {
    private final ReservationToken token;
    private final PaymentReference paymentReference;
    private final Money amount;
    private ReservationStatus status;

    public FundReservation(ReservationToken token, PaymentReference paymentReference, Money amount) {
        if (token == null) {
            throw new IllegalArgumentException("ReservationToken cannot be null.");
        }
        if (paymentReference == null) {
            throw new IllegalArgumentException("PaymentReference cannot be null.");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("Amount must be non-null and strictly positive.");
        }
        this.token = token;
        this.paymentReference = paymentReference;
        this.amount = amount;
        this.status = ReservationStatus.ACTIVE;
    }

    private FundReservation(
            ReservationToken token,
            PaymentReference paymentReference,
            Money amount,
            ReservationStatus status) {
        this.token = token;
        this.paymentReference = paymentReference;
        this.amount = amount;
        this.status = status;
    }

    /**
     * Restores a persisted {@link FundReservation} and validates stored attributes.
     */
    public static FundReservation restore(
            ReservationToken token,
            PaymentReference paymentReference,
            Money amount,
            ReservationStatus status) {
        if (token == null) {
            throw new PersistedBankingStateException("Restored FundReservation token cannot be null.");
        }
        if (paymentReference == null) {
            throw new PersistedBankingStateException("Restored FundReservation paymentReference cannot be null.");
        }
        if (amount == null || !amount.isPositive()) {
            throw new PersistedBankingStateException("Restored FundReservation amount must be strictly positive.");
        }
        if (status == null) {
            throw new PersistedBankingStateException("Restored FundReservation status cannot be null.");
        }
        return new FundReservation(token, paymentReference, amount, status);
    }

    void release() {
        if (this.status == ReservationStatus.CONSUMED) {
            throw new InvalidReservationStateException("Cannot release a CONSUMED reservation.");
        }
        this.status = ReservationStatus.RELEASED;
    }

    void consume() {
        if (this.status == ReservationStatus.RELEASED) {
            throw new InvalidReservationStateException("Cannot consume a RELEASED reservation.");
        }
        this.status = ReservationStatus.CONSUMED;
    }

    public ReservationToken getToken() {
        return token;
    }

    public PaymentReference getPaymentReference() {
        return paymentReference;
    }

    public Money getAmount() {
        return amount;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return this.status == ReservationStatus.ACTIVE;
    }
}
