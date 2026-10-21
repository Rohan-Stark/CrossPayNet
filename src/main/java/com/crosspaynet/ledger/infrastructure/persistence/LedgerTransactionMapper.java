package com.crosspaynet.ledger.infrastructure.persistence;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.LedgerAccountRef;
import com.crosspaynet.ledger.LedgerPostingKey;
import com.crosspaynet.ledger.LedgerTransactionId;
import com.crosspaynet.ledger.LedgerTransactionType;
import com.crosspaynet.ledger.LedgerValidationException;
import com.crosspaynet.ledger.domain.LedgerEntry;
import com.crosspaynet.ledger.domain.LedgerTransaction;
import com.crosspaynet.ledger.domain.PostingFingerprint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LedgerTransactionMapper {

    public LedgerTransactionEntity toEntity(LedgerTransaction domain) {
        if (domain == null) {
            throw new IllegalArgumentException("LedgerTransaction domain object cannot be null.");
        }

        LedgerTransactionEntity txEntity = new LedgerTransactionEntity(
                domain.getTransactionId().getValue(),
                domain.getPostingKey().getValue(),
                domain.getTransactionType(),
                domain.getCurrency().getCode(),
                domain.getTotalAmount().getAmount(),
                domain.getEntries().size(),
                domain.getExternalReference(),
                domain.getDescription(),
                domain.getReversedTransactionId() != null
                        ? domain.getReversedTransactionId().getValue()
                        : null,
                domain.getRequestFingerprint().getValue(),
                domain.getPostedAt()
        );

        for (LedgerEntry entry : domain.getEntries()) {
            LedgerEntryEntity entryEntity = new LedgerEntryEntity(
                    entry.getEntryId(),
                    entry.getLineNumber(),
                    entry.getAccount().type(),
                    entry.getAccount().value(),
                    entry.getDirection(),
                    entry.getAmount().getCurrency().getCode(),
                    entry.getAmount().getAmount()
            );
            txEntity.addEntry(entryEntity);
        }

        return txEntity;
    }

    public LedgerTransaction toDomain(LedgerTransactionEntity entity) {
        if (entity == null) {
            return null;
        }

        LedgerTransactionId transactionId = new LedgerTransactionId(entity.getTransactionId());
        LedgerTransactionId reversedTransactionId = entity.getReversedTransactionId() != null
                ? new LedgerTransactionId(entity.getReversedTransactionId())
                : null;

        LedgerPostingKey postingKey;
        if (entity.getTransactionType() == LedgerTransactionType.REVERSAL && reversedTransactionId != null) {
            postingKey = LedgerPostingKey.forReversal(reversedTransactionId);
            if (!postingKey.getValue().equals(entity.getPostingKey())) {
                throw new LedgerValidationException(
                        "Persisted REVERSAL postingKey (" + entity.getPostingKey()
                                + ") does not match expected key (" + postingKey.getValue() + ")."
                );
            }
        } else {
            postingKey = LedgerPostingKey.of(entity.getPostingKey());
        }

        Currency txCurrency = parseCurrency(entity.getCurrency());
        Money totalAmount = parseMoney(entity.getTotalAmount(), txCurrency);
        PostingFingerprint fingerprint = PostingFingerprint.ofHex(entity.getRequestFingerprint());

        List<LedgerEntry> domainEntries = new ArrayList<>(entity.getEntries().size());
        for (LedgerEntryEntity entryEntity : entity.getEntries()) {
            Currency entryCurrency = parseCurrency(entryEntity.getCurrency());
            LedgerEntry domainEntry = new LedgerEntry(
                    entryEntity.getEntryId(),
                    entryEntity.getLineNumber(),
                    LedgerAccountRef.of(entryEntity.getAccountType(), entryEntity.getAccountReference()),
                    entryEntity.getDirection(),
                    parseMoney(entryEntity.getAmount(), entryCurrency)
            );
            domainEntries.add(domainEntry);
        }

        if (entity.getEntryCount() != domainEntries.size()) {
            throw new LedgerValidationException(
                    "Persisted entryCount (" + entity.getEntryCount()
                            + ") does not match loaded entry list size (" + domainEntries.size() + ")."
            );
        }

        return LedgerTransaction.restore(
                transactionId,
                entity.getTransactionType(),
                postingKey,
                txCurrency,
                totalAmount,
                entity.getExternalReference(),
                entity.getDescription(),
                reversedTransactionId,
                fingerprint,
                entity.getPostedAt(),
                domainEntries
        );
    }

    private static Currency parseCurrency(String code) {
        try {
            return new Currency(code);
        } catch (IllegalArgumentException ex) {
            throw new LedgerValidationException("Invalid persisted currency code: " + code, ex);
        }
    }

    private static Money parseMoney( java.math.BigDecimal amount, Currency currency) {
        try {
            return new Money(amount, currency);
        } catch (IllegalArgumentException ex) {
            throw new LedgerValidationException("Invalid persisted monetary amount: " + amount, ex);
        }
    }
}
