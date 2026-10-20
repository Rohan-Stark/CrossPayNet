package com.crosspaynet.ledger;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.domain.LedgerLimits;
import com.crosspaynet.ledger.domain.SupportedCurrencyPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Authoritative accounting position for an account and currency derived from immutable Ledger entries (ADR-006).
 *
 * <p>{@code netBalance = debitTotal - creditTotal} is standardized as a signed Phase 3 calculation convention
 * at scale 4 without silent rounding.
 */
public record LedgerBalance(
        LedgerAccountRef account,
        Currency currency,
        Money debitTotal,
        Money creditTotal,
        BigDecimal netBalance
) {

    public LedgerBalance {
        if (account == null) {
            throw new LedgerValidationException("LedgerBalance account cannot be null.");
        }
        SupportedCurrencyPolicy.requireSupported(currency);
        if (debitTotal == null) {
            throw new LedgerValidationException("LedgerBalance debitTotal cannot be null.");
        }
        if (creditTotal == null) {
            throw new LedgerValidationException("LedgerBalance creditTotal cannot be null.");
        }
        if (!currency.equals(debitTotal.getCurrency())) {
            throw new LedgerValidationException(
                    "debitTotal currency (" + debitTotal.getCurrency() + ") must match balance currency (" + currency + ")."
            );
        }
        if (!currency.equals(creditTotal.getCurrency())) {
            throw new LedgerValidationException(
                    "creditTotal currency (" + creditTotal.getCurrency() + ") must match balance currency (" + currency + ")."
            );
        }
        if (debitTotal.isNegative()) {
            throw new LedgerValidationException("debitTotal cannot be negative.");
        }
        if (creditTotal.isNegative()) {
            throw new LedgerValidationException("creditTotal cannot be negative.");
        }
        if (debitTotal.getAmount().compareTo(LedgerLimits.MAX_AMOUNT) > 0) {
            throw new LedgerValidationException(
                    "debitTotal (" + debitTotal.getAmount().toPlainString() + ") exceeds NUMERIC(19,4) maximum."
            );
        }
        if (creditTotal.getAmount().compareTo(LedgerLimits.MAX_AMOUNT) > 0) {
            throw new LedgerValidationException(
                    "creditTotal (" + creditTotal.getAmount().toPlainString() + ") exceeds NUMERIC(19,4) maximum."
            );
        }
        if (netBalance == null) {
            throw new LedgerValidationException("LedgerBalance netBalance cannot be null.");
        }

        BigDecimal normalizedNetBalance;
        try {
            normalizedNetBalance = netBalance.setScale(LedgerLimits.MAX_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new LedgerValidationException(
                    "netBalance precision cannot exceed " + LedgerLimits.MAX_SCALE + " fractional decimal places.",
                    ex
            );
        }

        BigDecimal expectedNetBalance = debitTotal.getAmount()
                .subtract(creditTotal.getAmount())
                .setScale(LedgerLimits.MAX_SCALE, RoundingMode.UNNECESSARY);

        if (normalizedNetBalance.compareTo(expectedNetBalance) != 0) {
            throw new LedgerValidationException(
                    "netBalance (" + normalizedNetBalance.toPlainString()
                            + ") does not equal debitTotal - creditTotal (" + expectedNetBalance.toPlainString() + ")."
            );
        }

        netBalance = normalizedNetBalance;
    }

    public static LedgerBalance of(
            LedgerAccountRef account,
            Currency currency,
            Money debitTotal,
            Money creditTotal) {
        if (debitTotal == null || creditTotal == null) {
            throw new LedgerValidationException("debitTotal and creditTotal cannot be null.");
        }
        BigDecimal computedNet = debitTotal.getAmount()
                .subtract(creditTotal.getAmount())
                .setScale(LedgerLimits.MAX_SCALE, RoundingMode.UNNECESSARY);
        return new LedgerBalance(account, currency, debitTotal, creditTotal, computedNet);
    }

    public static LedgerBalance zero(LedgerAccountRef account, Currency currency) {
        SupportedCurrencyPolicy.requireSupported(currency);
        Money zero = Money.zero(currency);
        return of(account, currency, zero, zero);
    }
}
