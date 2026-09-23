package com.crosspaynet.banking.domain;

import com.crosspaynet.banking.domain.exception.CurrencyMismatchException;
import com.crosspaynet.banking.domain.exception.InsufficientFundsException;
import com.crosspaynet.banking.domain.exception.InvalidAccountStateException;
import com.crosspaynet.banking.domain.exception.InvalidReservationStateException;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

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
    void testReservationIdempotencyActiveSameRequest() {
        account.fundSimulator(Money.of("1000", "USD"));
        PaymentReference ref = PaymentReference.of("PAY-IDEMP");
        
        ReservationToken token1 = account.reserveFunds(Money.of("300", "USD"), ref);
        ReservationToken token2 = account.reserveFunds(Money.of("300", "USD"), ref);
        
        assertEquals(token1, token2);
        assertEquals(Money.of("700", "USD"), account.getAvailableBalance());
        assertEquals(1, account.getReservations().size());
    }

    @Test
    void testReservationIdempotencyActiveDifferentAmountRejected() {
        account.fundSimulator(Money.of("1000", "USD"));
        PaymentReference ref = PaymentReference.of("PAY-IDEMP");
        
        account.reserveFunds(Money.of("300", "USD"), ref);
        assertThrows(InvalidReservationStateException.class, () -> account.reserveFunds(Money.of("400", "USD"), ref));
    }

    @Test
    void testReservationIdempotencyTerminalRejected() {
        account.fundSimulator(Money.of("1000", "USD"));
        PaymentReference ref = PaymentReference.of("PAY-IDEMP");
        
        account.reserveFunds(Money.of("300", "USD"), ref);
        account.getReservations().get(0).release();
        
        assertThrows(InvalidReservationStateException.class, () -> account.reserveFunds(Money.of("300", "USD"), ref));
        
        PaymentReference ref2 = PaymentReference.of("PAY-CONS");
        account.reserveFunds(Money.of("200", "USD"), ref2);
        account.getReservations().get(1).consume();

        assertThrows(InvalidReservationStateException.class, () -> account.reserveFunds(Money.of("200", "USD"), ref2));
    }

    @Test
    void testAccountRestoreValidState() {
        FundReservation res = new FundReservation(ReservationToken.generate(), PaymentReference.of("R1"), Money.of("100", "USD"));
        List<FundReservation> list = new ArrayList<>();
        list.add(res);

        Account restored = Account.restore(AccountId.generate(), CustomerId.generate(), usd, AccountStatus.ACTIVE, Money.of("900", "USD"), Money.of("100", "USD"), list);
        assertEquals(Money.of("900", "USD"), restored.getAvailableBalance());
        assertEquals(Money.of("100", "USD"), restored.getReservedAmount());
    }

    @Test
    void testAccountRestoreInvalidBalanceRejected() {
        List<FundReservation> empty = Collections.emptyList();
        assertThrows(InvalidAccountStateException.class, () -> 
            Account.restore(AccountId.generate(), CustomerId.generate(), usd, AccountStatus.ACTIVE, Money.of("-100", "USD"), Money.zero(usd), empty)
        );
        assertThrows(InvalidAccountStateException.class, () -> 
            Account.restore(AccountId.generate(), CustomerId.generate(), usd, AccountStatus.ACTIVE, Money.zero(usd), Money.of("-10", "USD"), empty)
        );
    }

    @Test
    void testAccountRestoreInconsistentReservationTotalRejected() {
        FundReservation res = new FundReservation(ReservationToken.generate(), PaymentReference.of("R1"), Money.of("200", "USD"));
        List<FundReservation> list = new ArrayList<>();
        list.add(res);

        // reservedAmount says 100, but active reservations sum to 200
        assertThrows(InvalidAccountStateException.class, () -> 
            Account.restore(AccountId.generate(), CustomerId.generate(), usd, AccountStatus.ACTIVE, Money.of("800", "USD"), Money.of("100", "USD"), list)
        );
    }
}
