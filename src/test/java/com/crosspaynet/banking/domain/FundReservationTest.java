package com.crosspaynet.banking.domain;

import com.crosspaynet.banking.contracts.InvalidReservationStateException;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FundReservationTest {

    @Test
    void testActiveCreation() {
        FundReservation res = new FundReservation(
                ReservationToken.generate(),
                PaymentReference.of("REF1"),
                Money.of("100", "USD")
        );
        assertTrue(res.isActive());
        assertEquals(ReservationStatus.ACTIVE, res.getStatus());
    }

    @Test
    void testReleasedTransition() {
        FundReservation res = new FundReservation(
                ReservationToken.generate(),
                PaymentReference.of("REF1"),
                Money.of("100", "USD")
        );
        res.release();
        assertEquals(ReservationStatus.RELEASED, res.getStatus());
        assertFalse(res.isActive());

        // Idempotent release
        res.release();
        assertEquals(ReservationStatus.RELEASED, res.getStatus());
    }

    @Test
    void testConsumedTransition() {
        FundReservation res = new FundReservation(
                ReservationToken.generate(),
                PaymentReference.of("REF1"),
                Money.of("100", "USD")
        );
        res.consume();
        assertEquals(ReservationStatus.CONSUMED, res.getStatus());
        assertFalse(res.isActive());

        // Idempotent consume
        res.consume();
        assertEquals(ReservationStatus.CONSUMED, res.getStatus());
    }

    @Test
    void testInvalidTransitions() {
        FundReservation res1 = new FundReservation(
                ReservationToken.generate(),
                PaymentReference.of("REF1"),
                Money.of("100", "USD")
        );
        res1.release();
        assertThrows(InvalidReservationStateException.class, res1::consume);

        FundReservation res2 = new FundReservation(
                ReservationToken.generate(),
                PaymentReference.of("REF2"),
                Money.of("100", "USD")
        );
        res2.consume();
        assertThrows(InvalidReservationStateException.class, res2::release);
    }

    @Test
    void testRestoreValidAndCorruptedState() {
        ReservationToken token = ReservationToken.generate();
        PaymentReference ref = PaymentReference.of("REF-RESTORE");
        Money amount = Money.of("50.0000", "USD");

        FundReservation restoredReleased = FundReservation.restore(token, ref, amount, ReservationStatus.RELEASED);
        assertEquals(ReservationStatus.RELEASED, restoredReleased.getStatus());

        FundReservation restoredConsumed = FundReservation.restore(token, ref, amount, ReservationStatus.CONSUMED);
        assertEquals(ReservationStatus.CONSUMED, restoredConsumed.getStatus());

        assertThrows(PersistedBankingStateException.class,
                () -> FundReservation.restore(null, ref, amount, ReservationStatus.ACTIVE));
        assertThrows(PersistedBankingStateException.class,
                () -> FundReservation.restore(token, null, amount, ReservationStatus.ACTIVE));
        assertThrows(PersistedBankingStateException.class,
                () -> FundReservation.restore(token, ref, Money.of("0", "USD"), ReservationStatus.ACTIVE));
        assertThrows(PersistedBankingStateException.class,
                () -> FundReservation.restore(token, ref, amount, null));
    }
}
