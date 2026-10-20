package com.crosspaynet.ledger;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.domain.LedgerLimits;
import com.crosspaynet.ledger.domain.SupportedCurrencyPolicy;

import java.time.Instant;
import java.util.List;

/**
 * Immutable read-model view of a posted Ledger transaction and its entry lines.
 */
public record LedgerTransactionView(
        LedgerTransactionId transactionId,
        LedgerTransactionType transactionType,
        LedgerPostingKey postingKey,
        Currency currency,
        Money totalAmount,
        String externalReference,
        String description,
        LedgerTransactionId reversedTransactionId,
        Instant postedAt,
        List<LedgerEntryView> entries
) {

    public LedgerTransactionView {
        if (transactionId == null) {
            throw new LedgerValidationException("transactionId cannot be null.");
        }
        if (transactionType == null) {
            throw new LedgerValidationException("transactionType cannot be null.");
        }
        if (postingKey == null) {
            throw new LedgerValidationException("postingKey cannot be null.");
        }
        SupportedCurrencyPolicy.requireSupported(currency);
        if (totalAmount == null || !totalAmount.isPositive()) {
            throw new LedgerValidationException("totalAmount must be non-null and strictly positive.");
        }
        if (!currency.equals(totalAmount.getCurrency())) {
            throw new LedgerValidationException("totalAmount currency must match transaction currency.");
        }
        if (totalAmount.getAmount().compareTo(LedgerLimits.MAX_AMOUNT) > 0) {
            throw new LedgerValidationException("totalAmount exceeds NUMERIC(19,4) maximum.");
        }
        if (externalReference == null || externalReference.isBlank()
                || externalReference.length() > LedgerLimits.MAX_EXTERNAL_REFERENCE_LENGTH) {
            throw new LedgerValidationException("externalReference must be non-blank and at most 255 characters.");
        }
        if (description != null && (description.isBlank() || description.length() > LedgerLimits.MAX_DESCRIPTION_LENGTH)) {
            throw new LedgerValidationException("description must be null or non-blank with at most 500 characters.");
        }
        if (transactionType == LedgerTransactionType.STANDARD && reversedTransactionId != null) {
            throw new LedgerValidationException("STANDARD transaction cannot have a reversedTransactionId.");
        }
        if (transactionType == LedgerTransactionType.REVERSAL && reversedTransactionId == null) {
            throw new LedgerValidationException("REVERSAL transaction must have a reversedTransactionId.");
        }
        if (postedAt == null) {
            throw new LedgerValidationException("postedAt cannot be null.");
        }
        if (entries == null || entries.size() < LedgerLimits.MIN_ENTRIES || entries.size() > LedgerLimits.MAX_ENTRIES) {
            throw new LedgerValidationException("entries must contain between 2 and 100 items.");
        }
        for (LedgerEntryView entry : entries) {
            if (entry == null) {
                throw new LedgerValidationException("entries cannot contain null elements.");
            }
            if (!currency.equals(entry.amount().getCurrency())) {
                throw new LedgerValidationException("Entry view currency must match transaction currency.");
            }
        }
        entries = List.copyOf(entries);
    }
}
