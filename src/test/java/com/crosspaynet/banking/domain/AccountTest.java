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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {
    private Account account;
    private final Currency usd = new Currency("USD");

    @BeforeEach
    void setUp() {
        account = new Account(AccountId.generate(), CustomerId.generate(), usd);
    }

    @Test
    void testValidFunding() {
        account.fundSimulator(Money.of("1000", "USD"));
        assertEquals(Money.of("1000", "USD"), account.getAvailableBalance());
        assertEquals(Money.zero(usd), account.getReservedAmount());
    }

    @Test
    void testReserveFundsInsufficientFundsAndInactiveAccountAndCurrencyMismatch() {
        account.fundSimulator(Money.of("100", "USD"));

        assertThrows(InsufficientFundsException.class,
                () -> account.reserveFunds(Money.of("200", "USD"), PaymentReference.of("PAY-INSUFF")));
        assertEquals(Money.of("100", "USD"), account.getAvailableBalance());
        assertEquals(Money.zero(usd), account.getReservedAmount());
        assertTrue(account.getReservations().isEmpty());

        assertThrows(CurrencyMismatchException.class,
                () -> account.reserveFunds(Money.of("50", "EUR"), PaymentReference.of("PAY-EUR")));

        Account frozenAccount = Account.restore(
                AccountId.generate(),
                CustomerId.generate(),
                usd,
                AccountStatus.FROZEN,
                Money.of("500", "USD"),
                Money.zero(usd),
                List.of()
        );
        assertThrows(InvalidAccountStateException.class,
                () -> frozenAccount.reserveFunds(Money.of("100", "USD"), PaymentReference.of("PAY-FROZEN")));
    }

    @Test
    void testReservationIdempotencyActiveSameRequest() {
        account.fundSimulator(Money.of("1000", "USD"));
        PaymentReference ref = PaymentReference.of("PAY-IDEMP");

        ReservationToken token1 = account.reserveFunds(Money.of("300", "USD"), ref);
        ReservationToken token2 = account.reserveFunds(Money.of("300", "USD"), ref);

        assertEquals(token1, token2);
        assertEquals(Money.of("700", "USD"), account.getAvailableBalance());
        assertEquals(Money.of("300", "USD"), account.getReservedAmount());
        assertEquals(1, account.getReservations().size());
    }

    @Test
    void testReservationIdempotencyActiveDifferentAmountRejected() {
        account.fundSimulator(Money.of("1000", "USD"));
        PaymentReference ref = PaymentReference.of("PAY-IDEMP");

        account.reserveFunds(Money.of("300", "USD"), ref);
        assertThrows(InvalidReservationStateException.class,
                () -> account.reserveFunds(Money.of("400", "USD"), ref));
    }

    @Test
    void testReservationIdempotencyTerminalRejected() {
        account.fundSimulator(Money.of("1000", "USD"));
        PaymentReference ref = PaymentReference.of("PAY-IDEMP");

        ReservationToken token1 = account.reserveFunds(Money.of("300", "USD"), ref);
        account.releaseReservation(token1);

        assertThrows(InvalidReservationStateException.class,
                () -> account.reserveFunds(Money.of("300", "USD"), ref));

        PaymentReference ref2 = PaymentReference.of("PAY-CONS");
        ReservationToken token2 = account.reserveFunds(Money.of("200", "USD"), ref2);
        account.consumeReservation(token2);

        assertThrows(InvalidReservationStateException.class,
                () -> account.reserveFunds(Money.of("200", "USD"), ref2));
    }

    @Test
    void testReleaseReservationTransitionsActiveToReleasedAndIsIdempotent() {
        account.fundSimulator(Money.of("1000", "USD"));
        ReservationToken token = account.reserveFunds(Money.of("300", "USD"), PaymentReference.of("PAY-REL"));
        assertEquals(Money.of("700", "USD"), account.getAvailableBalance());
        assertEquals(Money.of("300", "USD"), account.getReservedAmount());

        account.releaseReservation(token);
        assertEquals(Money.of("1000", "USD"), account.getAvailableBalance());
        assertEquals(Money.zero(usd), account.getReservedAmount());
        assertEquals(ReservationStatus.RELEASED, account.getReservations().get(0).getStatus());

        // Idempotent second release does not double-credit availableBalance
        account.releaseReservation(token);
        assertEquals(Money.of("1000", "USD"), account.getAvailableBalance());
        assertEquals(Money.zero(usd), account.getReservedAmount());
    }

    @Test
    void testReleaseActiveReservationPermittedWhenAccountIsFrozenOrClosed() {
        ReservationToken token = ReservationToken.generate();
        FundReservation activeRes = FundReservation.restore(
                token,
                PaymentReference.of("PAY-FROZEN-REL"),
                Money.of("250", "USD"),
                ReservationStatus.ACTIVE
        );
        Account frozenAccount = Account.restore(
                AccountId.generate(),
                CustomerId.generate(),
                usd,
                AccountStatus.FROZEN,
                Money.of("750", "USD"),
                Money.of("250", "USD"),
                List.of(activeRes)
        );

        frozenAccount.releaseReservation(token);
        assertEquals(Money.of("1000", "USD"), frozenAccount.getAvailableBalance());
        assertEquals(Money.zero(usd), frozenAccount.getReservedAmount());
        assertEquals(ReservationStatus.RELEASED, frozenAccount.getReservations().get(0).getStatus());
    }

    @Test
    void testConsumeReservationTransitionsActiveToConsumedAndIsIdempotentEvenWhenLaterFrozen() {
        account.fundSimulator(Money.of("1000", "USD"));
        ReservationToken token = account.reserveFunds(Money.of("300", "USD"), PaymentReference.of("PAY-CONS"));

        account.consumeReservation(token);
        assertEquals(Money.of("700", "USD"), account.getAvailableBalance());
        assertEquals(Money.zero(usd), account.getReservedAmount());
        assertEquals(ReservationStatus.CONSUMED, account.getReservations().get(0).getStatus());

        // Idempotent second consume on ACTIVE account
        account.consumeReservation(token);
        assertEquals(Money.of("700", "USD"), account.getAvailableBalance());
        assertEquals(Money.zero(usd), account.getReservedAmount());

        // Idempotent consume on FROZEN account when reservation is already CONSUMED
        Account frozenWithConsumed = Account.restore(
                account.getAccountId(),
                account.getCustomerId(),
                usd,
                AccountStatus.FROZEN,
                account.getAvailableBalance(),
                account.getReservedAmount(),
                account.getReservations()
        );
        assertDoesNotThrow(() -> frozenWithConsumed.consumeReservation(token));
    }

    @Test
    void testConsumeActiveReservationRejectedWhenAccountIsFrozenOrClosed() {
        ReservationToken token = ReservationToken.generate();
        FundReservation activeRes = FundReservation.restore(
                token,
                PaymentReference.of("PAY-FROZEN-CONS"),
                Money.of("250", "USD"),
                ReservationStatus.ACTIVE
        );
        Account frozenAccount = Account.restore(
                AccountId.generate(),
                CustomerId.generate(),
                usd,
                AccountStatus.FROZEN,
                Money.of("750", "USD"),
                Money.of("250", "USD"),
                List.of(activeRes)
        );

        assertThrows(InvalidAccountStateException.class, () -> frozenAccount.consumeReservation(token));
        assertEquals(Money.of("750", "USD"), frozenAccount.getAvailableBalance());
        assertEquals(Money.of("250", "USD"), frozenAccount.getReservedAmount());
        assertEquals(ReservationStatus.ACTIVE, frozenAccount.getReservations().get(0).getStatus());
    }

    @Test
    void testIncompatibleReservationTransitionsAndUnknownTokensRejected() {
        account.fundSimulator(Money.of("1000", "USD"));
        ReservationToken releasedToken = account.reserveFunds(Money.of("200", "USD"), PaymentReference.of("PAY-R1"));
        ReservationToken consumedToken = account.reserveFunds(Money.of("300", "USD"), PaymentReference.of("PAY-C1"));

        account.releaseReservation(releasedToken);
        account.consumeReservation(consumedToken);

        assertThrows(InvalidReservationStateException.class, () -> account.consumeReservation(releasedToken));
        assertThrows(InvalidReservationStateException.class, () -> account.releaseReservation(consumedToken));
        assertThrows(InvalidReservationStateException.class, () -> account.releaseReservation(ReservationToken.generate()));
        assertThrows(InvalidReservationStateException.class, () -> account.consumeReservation(ReservationToken.generate()));
        assertThrows(IllegalArgumentException.class, () -> account.releaseReservation(null));
        assertThrows(IllegalArgumentException.class, () -> account.consumeReservation(null));

        // Even if the account is later FROZEN, RELEASED -> CONSUMED still throws InvalidReservationStateException
        Account frozenAccount = Account.restore(
                account.getAccountId(),
                account.getCustomerId(),
                usd,
                AccountStatus.FROZEN,
                account.getAvailableBalance(),
                account.getReservedAmount(),
                account.getReservations()
        );
        assertThrows(InvalidReservationStateException.class, () -> frozenAccount.consumeReservation(releasedToken));

        // Unmodifiable snapshot check
        assertThrows(UnsupportedOperationException.class, () -> account.getReservations().clear());
    }

    @Test
    void testAccountRestoreValidState() {
        FundReservation res = new FundReservation(
                ReservationToken.generate(),
                PaymentReference.of("R1"),
                Money.of("100", "USD")
        );
        List<FundReservation> list = new ArrayList<>();
        list.add(res);

        Account restored = Account.restore(
                AccountId.generate(),
                CustomerId.generate(),
                usd,
                AccountStatus.ACTIVE,
                Money.of("900", "USD"),
                Money.of("100", "USD"),
                list
        );
        assertEquals(Money.of("900", "USD"), restored.getAvailableBalance());
        assertEquals(Money.of("100", "USD"), restored.getReservedAmount());
    }

    @Test
    void testAccountRestoreInvalidBalanceRejected() {
        List<FundReservation> empty = Collections.emptyList();
        assertThrows(PersistedBankingStateException.class, () ->
                Account.restore(AccountId.generate(), CustomerId.generate(), usd, AccountStatus.ACTIVE, Money.of("-100", "USD"), Money.zero(usd), empty)
        );
        assertThrows(PersistedBankingStateException.class, () ->
                Account.restore(AccountId.generate(), CustomerId.generate(), usd, AccountStatus.ACTIVE, Money.zero(usd), Money.of("-10", "USD"), empty)
        );
    }

    @Test
    void testAccountRestoreInconsistentReservationTotalRejected() {
        FundReservation res = new FundReservation(
                ReservationToken.generate(),
                PaymentReference.of("R1"),
                Money.of("200", "USD")
        );
        List<FundReservation> list = new ArrayList<>();
        list.add(res);

        // reservedAmount says 100, but active reservations sum to 200
        assertThrows(PersistedBankingStateException.class, () ->
                Account.restore(AccountId.generate(), CustomerId.generate(), usd, AccountStatus.ACTIVE, Money.of("800", "USD"), Money.of("100", "USD"), list)
        );
    }
}
