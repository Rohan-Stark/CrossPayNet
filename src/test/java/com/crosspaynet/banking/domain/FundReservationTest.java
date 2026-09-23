package com.crosspaynet.banking.domain;

import com.crosspaynet.banking.domain.exception.InvalidReservationStateException;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FundReservationTest {

    @Test
    void testActiveCreation() {
        FundReservation res = new FundReservation(ReservationToken.generate(), PaymentReference.of("REF1"), Money.of("100", "USD"));
        assertTrue(res.isActive());
        assertEquals(ReservationStatus.ACTIVE, res.getStatus());
    }

    @Test
    void testReleasedTransition() {
        FundReservation res = new FundReservation(ReservationToken.generate(), PaymentReference.of("REF1"), Money.of("100", "USD"));
        res.release();
        assertEquals(ReservationStatus.RELEASED, res.getStatus());
    }

    @Test
    void testConsumedTransition() {
        FundReservation res = new FundReservation(ReservationToken.generate(), PaymentReference.of("REF1"), Money.of("100", "USD"));
        res.consume();
        assertEquals(ReservationStatus.CONSUMED, res.getStatus());
    }

    @Test
    void testInvalidTransitions() {
        FundReservation res1 = new FundReservation(ReservationToken.generate(), PaymentReference.of("REF1"), Money.of("100", "USD"));
        res1.release();
        assertThrows(InvalidReservationStateException.class, res1::consume);

        FundReservation res2 = new FundReservation(ReservationToken.generate(), PaymentReference.of("REF2"), Money.of("100", "USD"));
        res2.consume();
        assertThrows(InvalidReservationStateException.class, res2::release);
    }
}
