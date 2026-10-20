package com.crosspaynet.ledger;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.ledger.domain.LedgerLimits;
import com.crosspaynet.ledger.domain.SupportedCurrencyPolicy;

import java.util.List;

/**
 * Command to post a balanced, single-currency {@link LedgerTransactionType#STANDARD} Ledger transaction.
 */
public record PostLedgerTransactionCommand(
        LedgerPostingKey postingKey,
        Currency currency,
        List<LedgerEntryLine> entries,
        String externalReference,
        String description
) {

    public PostLedgerTransactionCommand {
        if (postingKey == null) {
            throw new LedgerValidationException("postingKey cannot be null.");
        }
        if (postingKey.isReversalKey()) {
            throw new LedgerValidationException(
                    "Standard PostLedgerTransactionCommand cannot use a reserved reversal posting key."
            );
        }
        SupportedCurrencyPolicy.requireSupported(currency);

        if (entries == null) {
            throw new LedgerValidationException("entries cannot be null.");
        }
        if (entries.size() < LedgerLimits.MIN_ENTRIES || entries.size() > LedgerLimits.MAX_ENTRIES) {
            throw new LedgerValidationException(
                    "Ledger transaction must contain between " + LedgerLimits.MIN_ENTRIES
                            + " and " + LedgerLimits.MAX_ENTRIES + " entries; got " + entries.size() + "."
            );
        }
        for (LedgerEntryLine entry : entries) {
            if (entry == null) {
                throw new LedgerValidationException("entries cannot contain null elements.");
            }
            if (!currency.equals(entry.amount().getCurrency())) {
                throw new LedgerValidationException(
                        "Entry currency (" + entry.amount().getCurrency()
                                + ") does not match transaction currency (" + currency + ")."
                );
            }
        }
        entries = List.copyOf(entries);

        if (externalReference == null || externalReference.trim().isEmpty()) {
            throw new LedgerValidationException("externalReference cannot be null or blank.");
        }
        externalReference = externalReference.trim();
        if (externalReference.length() > LedgerLimits.MAX_EXTERNAL_REFERENCE_LENGTH) {
            throw new LedgerValidationException(
                    "externalReference length (" + externalReference.length() + ") exceeds maximum of "
                            + LedgerLimits.MAX_EXTERNAL_REFERENCE_LENGTH + " characters."
            );
        }

        if (description != null) {
            String trimmedDescription = description.trim();
            if (trimmedDescription.isEmpty()) {
                description = null;
            } else {
                if (trimmedDescription.length() > LedgerLimits.MAX_DESCRIPTION_LENGTH) {
                    throw new LedgerValidationException(
                            "description length (" + trimmedDescription.length() + ") exceeds maximum of "
                                    + LedgerLimits.MAX_DESCRIPTION_LENGTH + " characters."
                    );
                }
                description = trimmedDescription;
            }
        }
    }
}
