package com.crosspaynet.banking.domain;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.CurrencyMismatchException;
import com.crosspaynet.banking.contracts.InsufficientFundsException;
import com.crosspaynet.banking.contracts.InvalidAccountStateException;
import com.crosspaynet.banking.contracts.InvalidReservationStateException;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
     * Restores an existing Account from persistence and validates aggregate invariants.
     */
    public static Account restore(
            AccountId accountId,
            CustomerId customerId,
            Currency currency,
            AccountStatus status,
            Money availableBalance,
            Money reservedAmount,
            List<FundReservation> reservations) {
        if (accountId == null) {
            throw new PersistedBankingStateException("Restored account cannot have null accountId.");
        }
        if (customerId == null) {
            throw new PersistedBankingStateException("Restored account cannot have null customerId.");
        }
        if (currency == null) {
            throw new PersistedBankingStateException("Restored account cannot have null currency.");
        }
        if (status == null) {
            throw new PersistedBankingStateException("Restored account cannot have null status.");
        }
        if (availableBalance == null || availableBalance.isNegative()) {
            throw new PersistedBankingStateException("Restored account cannot have negative available balance.");
        }
        if (!currency.equals(availableBalance.getCurrency())) {
            throw new PersistedBankingStateException("Restored account availableBalance currency mismatch.");
        }
        if (reservedAmount == null || reservedAmount.isNegative()) {
            throw new PersistedBankingStateException("Restored account cannot have negative reserved amount.");
        }
        if (!currency.equals(reservedAmount.getCurrency())) {
            throw new PersistedBankingStateException("Restored account reservedAmount currency mismatch.");
        }

        Account account = new Account(accountId, customerId, currency);
        account.status = status;
        account.availableBalance = availableBalance;
        account.reservedAmount = reservedAmount;

        Money activeReservationsTotal = Money.zero(currency);
        if (reservations != null) {
            Set<ReservationToken> seenTokens = new HashSet<>();
            Set<PaymentReference> seenReferences = new HashSet<>();
            for (FundReservation res : reservations) {
                if (res == null) {
                    throw new PersistedBankingStateException("Restored account cannot contain a null reservation.");
                }
                if (!currency.equals(res.getAmount().getCurrency())) {
                    throw new PersistedBankingStateException(
                            "Restored reservation currency (" + res.getAmount().getCurrency()
                                    + ") does not match account currency (" + currency + ")."
                    );
                }
                if (!seenTokens.add(res.getToken())) {
                    throw new PersistedBankingStateException(
                            "Duplicate reservation token in restored account: " + res.getToken()
                    );
                }
                if (!seenReferences.add(res.getPaymentReference())) {
                    throw new PersistedBankingStateException(
                            "Duplicate payment reference in restored account: " + res.getPaymentReference()
                    );
                }
                if (res.isActive()) {
                    activeReservationsTotal = activeReservationsTotal.add(res.getAmount());
                }
                account.reservations.add(res);
            }
        }

        if (!activeReservationsTotal.equals(reservedAmount)) {
            throw new PersistedBankingStateException(
                    "Restored account reservation total (" + activeReservationsTotal
                            + ") does not match reservedAmount (" + reservedAmount + ")."
            );
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
        if (paymentReference == null) {
            throw new IllegalArgumentException("PaymentReference cannot be null.");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("Reservation amount must be strictly positive.");
        }
        if (this.status != AccountStatus.ACTIVE) {
            throw new InvalidAccountStateException("Cannot reserve funds on an inactive account.");
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
                    throw new InvalidReservationStateException(
                            "A conflicting ACTIVE reservation exists for this reference with a different amount/currency."
                    );
                }
            } else {
                throw new InvalidReservationStateException(
                        "A terminal reservation (" + res.getStatus() + ") already exists for this reference."
                );
            }
        }

        if (!this.availableBalance.isGreaterThanOrEqual(amount)) {
            throw new InsufficientFundsException(
                    "Insufficient funds. Available: " + this.availableBalance + ", Requested: " + amount
            );
        }

        this.availableBalance = this.availableBalance.subtract(amount);
        this.reservedAmount = this.reservedAmount.add(amount);

        ReservationToken token = ReservationToken.generate();
        FundReservation newReservation = new FundReservation(token, paymentReference, amount);
        this.reservations.add(newReservation);

        return token;
    }

    /**
     * Releases an operational fund reservation by token ({@code ACTIVE -> RELEASED}).
     *
     * <p>If the reservation is already {@code RELEASED}, this operation is an idempotent no-op.
     * If the reservation is {@code CONSUMED}, {@link InvalidReservationStateException} is thrown.
     * Releasing an active reservation is permitted even if the account has since been frozen or closed.
     */
    public void releaseReservation(ReservationToken reservationToken) {
        if (reservationToken == null) {
            throw new IllegalArgumentException("ReservationToken cannot be null.");
        }

        FundReservation reservation = getReservationByToken(reservationToken)
                .orElseThrow(() -> new InvalidReservationStateException(
                        "Reservation not found for token: " + reservationToken
                ));

        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return;
        }
        if (reservation.getStatus() == ReservationStatus.CONSUMED) {
            reservation.release();
            return;
        }

        reservation.release();
        this.reservedAmount = this.reservedAmount.subtract(reservation.getAmount());
        this.availableBalance = this.availableBalance.add(reservation.getAmount());
    }

    /**
     * Consumes an operational fund reservation by token ({@code ACTIVE -> CONSUMED}).
     *
     * <p>If the reservation is already {@code CONSUMED}, this operation is an idempotent no-op
     * regardless of current account status. If the reservation is {@code RELEASED},
     * {@link InvalidReservationStateException} is thrown. Consuming an {@code ACTIVE} reservation
     * requires the account to be {@link AccountStatus#ACTIVE}.
     */
    public void consumeReservation(ReservationToken reservationToken) {
        if (reservationToken == null) {
            throw new IllegalArgumentException("ReservationToken cannot be null.");
        }

        FundReservation reservation = getReservationByToken(reservationToken)
                .orElseThrow(() -> new InvalidReservationStateException(
                        "Reservation not found for token: " + reservationToken
                ));

        if (reservation.getStatus() == ReservationStatus.CONSUMED) {
            return;
        }
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            reservation.consume();
            return;
        }

        if (this.status != AccountStatus.ACTIVE) {
            throw new InvalidAccountStateException("Cannot consume reservation on an inactive account.");
        }

        reservation.consume();
        this.reservedAmount = this.reservedAmount.subtract(reservation.getAmount());
    }

    private void requireMatchingCurrency(Money amount) {
        if (!this.currency.equals(amount.getCurrency())) {
            throw new CurrencyMismatchException(
                    "Account currency is " + this.currency + " but request was " + amount.getCurrency()
            );
        }
    }

    private Optional<FundReservation> getReservationByReference(PaymentReference paymentReference) {
        return this.reservations.stream()
                .filter(r -> r.getPaymentReference().equals(paymentReference))
                .findFirst();
    }

    private Optional<FundReservation> getReservationByToken(ReservationToken reservationToken) {
        return this.reservations.stream()
                .filter(r -> r.getToken().equals(reservationToken))
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
        return List.copyOf(reservations);
    }
}
