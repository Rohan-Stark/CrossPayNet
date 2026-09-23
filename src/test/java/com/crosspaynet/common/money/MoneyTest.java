package com.crosspaynet.common.money;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
    
    private final Currency usd = new Currency("USD");
    private final Currency eur = new Currency("EUR");

    @Test
    void testValidConstruction() {
        Money m = Money.of("100.50", "USD");
        assertEquals(new BigDecimal("100.5000"), m.getAmount());
        assertEquals(usd, m.getCurrency());
    }

    @Test
    void testFourDecimalAccepted() {
        Money m = Money.of("10.1234", "USD");
        assertEquals(new BigDecimal("10.1234"), m.getAmount());
    }

    @Test
    void testPrecisionGreaterThanFourRejected() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("10.12345", "USD"));
    }

    @Test
    void testZeroAndNegativeHandling() {
        Money zero = Money.zero(usd);
        assertTrue(zero.isZero());
        assertFalse(zero.isPositive());
        assertFalse(zero.isNegative());

        Money neg = Money.of("-50", "USD");
        assertTrue(neg.isNegative());
        assertFalse(neg.isPositive());
    }

    @Test
    void testCurrencyMismatchOnAdd() {
        Money m1 = Money.of("100", "USD");
        Money m2 = Money.of("50", "EUR");
        assertThrows(IllegalArgumentException.class, () -> m1.add(m2));
    }

    @Test
    void testArithmetic() {
        Money m1 = Money.of("100.25", "USD");
        Money m2 = Money.of("50.25", "USD");
        
        Money sum = m1.add(m2);
        assertEquals(new BigDecimal("150.5000"), sum.getAmount());
        
        Money diff = m1.subtract(m2);
        assertEquals(new BigDecimal("50.0000"), diff.getAmount());
    }

    @Test
    void testEquality() {
        Money m1 = Money.of("100", "USD");
        Money m2 = Money.of("100.00", "USD");
        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
    }
}
