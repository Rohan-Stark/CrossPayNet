package com.crosspaynet.banking.domain;

import com.crosspaynet.banking.domain.exception.InvalidReservationStateException;
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
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null.");
        }
        this.token = token;
        this.paymentReference = paymentReference;
        this.amount = amount;
        this.status = ReservationStatus.ACTIVE;
    }

    public void release() {
        if (this.status == ReservationStatus.CONSUMED) {
            throw new InvalidReservationStateException("Cannot release a CONSUMED reservation.");
        }
        this.status = ReservationStatus.RELEASED;
    }

    public void consume() {
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
