package com.crosspaynet.banking.domain;

import com.crosspaynet.banking.domain.exception.CurrencyMismatchException;
import com.crosspaynet.banking.domain.exception.InsufficientFundsException;
import com.crosspaynet.banking.domain.exception.InvalidAccountStateException;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Account {
    private final AccountId accountId;
    private final CustomerId customerId;
    private final Currency currency;
    private AccountStatus status;
    private Money availableBalance;
    private Money reservedAmount;
    private final List<FundReservation> reservations;

    public Account(AccountId accountId, CustomerId customerId, Currency currency) {
        if (accountId == null) throw new IllegalArgumentException("AccountId cannot be null.");
        if (customerId == null) throw new IllegalArgumentException("CustomerId cannot be null.");
        if (currency == null) throw new IllegalArgumentException("Currency cannot be null.");
        
        this.accountId = accountId;
        this.customerId = customerId;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.availableBalance = Money.zero(currency);
        this.reservedAmount = Money.zero(currency);
        this.reservations = new ArrayList<>();
    }

    /**
     * Restores an existing Account from persistence and validates invariants.
     */
    public static Account restore(AccountId accountId, CustomerId customerId, Currency currency, 
                                  AccountStatus status, Money availableBalance, Money reservedAmount,
                                  List<FundReservation> reservations) {
        if (availableBalance == null || availableBalance.isNegative()) {
            throw new InvalidAccountStateException("Restored account cannot have negative available balance.");
        }
        if (reservedAmount == null || reservedAmount.isNegative()) {
            throw new InvalidAccountStateException("Restored account cannot have negative reserved amount.");
        }

        Account account = new Account(accountId, customerId, currency);
        account.status = status;
        account.availableBalance = availableBalance;
        account.reservedAmount = reservedAmount;
        
        Money activeReservationsTotal = Money.zero(currency);
        if (reservations != null) {
            for (FundReservation res : reservations) {
                if (res.isActive()) {
                    activeReservationsTotal = activeReservationsTotal.add(res.getAmount());
                }
                account.reservations.add(res);
            }
        }
        
        if (!activeReservationsTotal.equals(reservedAmount)) {
            throw new InvalidAccountStateException("Restored account reservation total (" + activeReservationsTotal + ") does not match reservedAmount (" + reservedAmount + ").");
        }
        
        return account;
    }

    public void fundSimulator(Money amount) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new InvalidAccountStateException("Cannot fund an account that is not ACTIVE.");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("Funding amount must be strictly positive.");
        }
        requireMatchingCurrency(amount);

        this.availableBalance = this.availableBalance.add(amount);
    }

    public ReservationToken reserveFunds(Money amount, PaymentReference paymentReference) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new InvalidAccountStateException("Cannot reserve funds on an inactive account.");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("Reservation amount must be strictly positive.");
        }
        requireMatchingCurrency(amount);

        // Idempotency check:
        Optional<FundReservation> existing = getReservationByReference(paymentReference);
        if (existing.isPresent()) {
            FundReservation res = existing.get();
            if (res.isActive()) {
                if (res.getAmount().equals(amount)) {
                    return res.getToken();
                } else {
                    throw new InvalidReservationStateException("A conflicting ACTIVE reservation exists for this reference with a different amount/currency.");
                }
            } else {
                throw new InvalidReservationStateException("A terminal reservation (" + res.getStatus() + ") already exists for this reference.");
            }
        }

        if (!this.availableBalance.isGreaterThanOrEqual(amount)) {
            throw new InsufficientFundsException("Insufficient funds. Available: " + this.availableBalance + ", Requested: " + amount);
        }

        this.availableBalance = this.availableBalance.subtract(amount);
        this.reservedAmount = this.reservedAmount.add(amount);

        ReservationToken token = ReservationToken.generate();
        FundReservation newReservation = new FundReservation(token, paymentReference, amount);
        this.reservations.add(newReservation);

        return token;
    }

    private void requireMatchingCurrency(Money amount) {
        if (!this.currency.equals(amount.getCurrency())) {
            throw new CurrencyMismatchException("Account currency is " + this.currency + " but request was " + amount.getCurrency());
        }
    }

    private Optional<FundReservation> getReservationByReference(PaymentReference paymentReference) {
        return this.reservations.stream()
                .filter(r -> r.getPaymentReference().equals(paymentReference))
                .findFirst();
    }

    public AccountId getAccountId() {
        return accountId;
    }

    public CustomerId getCustomerId() {
        return customerId;
    }

    public Currency getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Money getAvailableBalance() {
        return availableBalance;
    }

    public Money getReservedAmount() {
        return reservedAmount;
    }
    
    public List<FundReservation> getReservations() {
        return new ArrayList<>(reservations);
    }
}
