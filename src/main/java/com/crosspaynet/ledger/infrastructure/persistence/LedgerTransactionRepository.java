package com.crosspaynet.ledger.infrastructure.persistence;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.LedgerAccountRef;
import com.crosspaynet.ledger.LedgerAccountType;
import com.crosspaynet.ledger.LedgerBalance;
import com.crosspaynet.ledger.LedgerValidationException;
import com.crosspaynet.ledger.domain.SupportedCurrencyPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {

    Optional<LedgerTransactionEntity> findByPostingKey(String postingKey);

    Optional<LedgerTransactionEntity> findByReversedTransactionId(UUID reversedTransactionId);

    interface AccountBalanceTotalsProjection {
        BigDecimal getDebitTotal();

        BigDecimal getCreditTotal();
    }

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN e.direction = com.crosspaynet.ledger.EntryDirection.DEBIT THEN e.amount ELSE 0 END), 0) AS debitTotal,
               COALESCE(SUM(CASE WHEN e.direction = com.crosspaynet.ledger.EntryDirection.CREDIT THEN e.amount ELSE 0 END), 0) AS creditTotal
          FROM LedgerEntryEntity e
         WHERE e.accountType = :accountType
           AND e.accountReference = :accountReference
           AND e.currency = :currencyCode
        """)
    AccountBalanceTotalsProjection aggregateBalanceTotals(
            @Param("accountType") LedgerAccountType accountType,
            @Param("accountReference") String accountReference,
            @Param("currencyCode") String currencyCode
    );

    default LedgerBalance calculateBalance(LedgerAccountRef account, Currency currency) {
        if (account == null) {
            throw new LedgerValidationException("LedgerBalance account cannot be null.");
        }
        SupportedCurrencyPolicy.requireSupported(currency);

        AccountBalanceTotalsProjection totals = aggregateBalanceTotals(
                account.type(),
                account.value(),
                currency.getCode()
        );

        Money debitTotal = new Money(totals.getDebitTotal(), currency);
        Money creditTotal = new Money(totals.getCreditTotal(), currency);
        return LedgerBalance.of(account, currency, debitTotal, creditTotal);
    }
}
