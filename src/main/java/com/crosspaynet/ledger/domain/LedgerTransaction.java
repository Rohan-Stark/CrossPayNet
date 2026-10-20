package com.crosspaynet.ledger.domain;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.EntryDirection;
import com.crosspaynet.ledger.LedgerEntryLine;
import com.crosspaynet.ledger.LedgerEntryView;
import com.crosspaynet.ledger.LedgerPostingKey;
import com.crosspaynet.ledger.LedgerTransactionId;
import com.crosspaynet.ledger.LedgerTransactionType;
import com.crosspaynet.ledger.LedgerTransactionView;
import com.crosspaynet.ledger.LedgerValidationException;
import com.crosspaynet.ledger.PostLedgerTransactionCommand;
import com.crosspaynet.ledger.ReverseLedgerTransactionCommand;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate root representing an immutable, balanced double-entry accounting transaction (ADR-006).
 */
public final class LedgerTransaction {

    private final LedgerTransactionId transactionId;
    private final LedgerTransactionType transactionType;
    private final LedgerPostingKey postingKey;
    private final Currency currency;
    private final Money totalAmount;
    private final String externalReference;
    private final String description;
    private final LedgerTransactionId reversedTransactionId;
    private final PostingFingerprint requestFingerprint;
    private final Instant postedAt;
    private final List<LedgerEntry> entries;

    private LedgerTransaction(
            LedgerTransactionId transactionId,
            LedgerTransactionType transactionType,
            LedgerPostingKey postingKey,
            Currency currency,
            Money totalAmount,
            String externalReference,
            String description,
            LedgerTransactionId reversedTransactionId,
            PostingFingerprint requestFingerprint,
            Instant postedAt,
            List<LedgerEntry> entries) {

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
            throw new LedgerValidationException(
                    "totalAmount currency (" + totalAmount.getCurrency()
                            + ") does not match transaction currency (" + currency + ")."
            );
        }
        if (totalAmount.getAmount().compareTo(LedgerLimits.MAX_AMOUNT) > 0) {
            throw new LedgerValidationException(
                    "totalAmount (" + totalAmount.getAmount().toPlainString()
                            + ") exceeds NUMERIC(19,4) maximum (" + LedgerLimits.MAX_AMOUNT.toPlainString() + ")."
            );
        }

        if (externalReference == null || externalReference.trim().isEmpty()) {
            throw new LedgerValidationException("externalReference cannot be null or blank.");
        }
        String normalizedExternalRef = externalReference.trim();
        if (normalizedExternalRef.length() > LedgerLimits.MAX_EXTERNAL_REFERENCE_LENGTH) {
            throw new LedgerValidationException(
                    "externalReference length (" + normalizedExternalRef.length() + ") exceeds maximum of "
                            + LedgerLimits.MAX_EXTERNAL_REFERENCE_LENGTH + " characters."
            );
        }

        String normalizedDescription = null;
        if (description != null) {
            String trimmedDesc = description.trim();
            if (!trimmedDesc.isEmpty()) {
                if (trimmedDesc.length() > LedgerLimits.MAX_DESCRIPTION_LENGTH) {
                    throw new LedgerValidationException(
                            "description length (" + trimmedDesc.length() + ") exceeds maximum of "
                                    + LedgerLimits.MAX_DESCRIPTION_LENGTH + " characters."
                    );
                }
                normalizedDescription = trimmedDesc;
            }
        }

        if (transactionType == LedgerTransactionType.STANDARD) {
            if (reversedTransactionId != null) {
                throw new LedgerValidationException("STANDARD transaction cannot have a reversedTransactionId.");
            }
            if (postingKey.isReversalKey()) {
                throw new LedgerValidationException("STANDARD transaction cannot use a reversal postingKey.");
            }
        } else if (transactionType == LedgerTransactionType.REVERSAL) {
            if (reversedTransactionId == null) {
                throw new LedgerValidationException("REVERSAL transaction must reference a reversedTransactionId.");
            }
            if (transactionId.equals(reversedTransactionId)) {
                throw new LedgerValidationException("REVERSAL transaction cannot reference itself.");
            }
            LedgerPostingKey expectedReversalKey = LedgerPostingKey.forReversal(reversedTransactionId);
            if (!postingKey.equals(expectedReversalKey)) {
                throw new LedgerValidationException(
                        "REVERSAL transaction postingKey (" + postingKey
                                + ") does not match expected deterministic key (" + expectedReversalKey + ")."
                );
            }
        }

        if (postedAt == null) {
            throw new LedgerValidationException("postedAt cannot be null.");
        }
        if (entries == null || entries.size() < LedgerLimits.MIN_ENTRIES || entries.size() > LedgerLimits.MAX_ENTRIES) {
            throw new LedgerValidationException(
                    "LedgerTransaction must contain between " + LedgerLimits.MIN_ENTRIES
                            + " and " + LedgerLimits.MAX_ENTRIES + " entries."
            );
        }

        Money debitTotal = Money.zero(currency);
        Money creditTotal = Money.zero(currency);
        Set<UUID> seenEntryIds = new HashSet<>(entries.size());
        List<LedgerEntryLine> entryLines = new ArrayList<>(entries.size());

        for (int i = 0; i < entries.size(); i++) {
            LedgerEntry entry = entries.get(i);
            if (entry == null) {
                throw new LedgerValidationException("entries cannot contain null elements.");
            }
            int expectedLineNumber = i + 1;
            if (entry.getLineNumber() != expectedLineNumber) {
                throw new LedgerValidationException(
                        "Entry at index " + i + " has lineNumber " + entry.getLineNumber()
                                + " instead of expected contiguous lineNumber " + expectedLineNumber + "."
                );
            }
            if (!seenEntryIds.add(entry.getEntryId())) {
                throw new LedgerValidationException("Duplicate entryId in transaction: " + entry.getEntryId());
            }
            if (!currency.equals(entry.getAmount().getCurrency())) {
                throw new LedgerValidationException(
                        "Entry currency (" + entry.getAmount().getCurrency()
                                + ") does not match transaction currency (" + currency + ")."
                );
            }
            if (entry.getDirection() == EntryDirection.DEBIT) {
                debitTotal = debitTotal.add(entry.getAmount());
            } else {
                creditTotal = creditTotal.add(entry.getAmount());
            }
            entryLines.add(entry.toEntryLine());
        }

        if (debitTotal.getAmount().compareTo(LedgerLimits.MAX_AMOUNT) > 0) {
            throw new LedgerValidationException(
                    "Total debit amount (" + debitTotal.getAmount().toPlainString()
                            + ") exceeds NUMERIC(19,4) maximum (" + LedgerLimits.MAX_AMOUNT.toPlainString() + ")."
            );
        }
        if (creditTotal.getAmount().compareTo(LedgerLimits.MAX_AMOUNT) > 0) {
            throw new LedgerValidationException(
                    "Total credit amount (" + creditTotal.getAmount().toPlainString()
                            + ") exceeds NUMERIC(19,4) maximum (" + LedgerLimits.MAX_AMOUNT.toPlainString() + ")."
            );
        }
        if (!debitTotal.equals(creditTotal)) {
            throw new LedgerValidationException(
                    "Unbalanced LedgerTransaction: total debits (" + debitTotal
                            + ") do not equal total credits (" + creditTotal + ")."
            );
        }
        if (!debitTotal.equals(totalAmount)) {
            throw new LedgerValidationException(
                    "Declared totalAmount (" + totalAmount
                            + ") does not equal balanced debit/credit total (" + debitTotal + ")."
            );
        }

        PostingFingerprint computedFingerprint = PostingFingerprint.forTransaction(
                transactionType,
                currency,
                totalAmount,
                normalizedExternalRef,
                reversedTransactionId,
                entryLines
        );
        if (requestFingerprint == null) {
            throw new LedgerValidationException("requestFingerprint cannot be null.");
        }
        if (!requestFingerprint.equals(computedFingerprint)) {
            throw new LedgerValidationException(
                    "requestFingerprint (" + requestFingerprint
                            + ") does not match computed transaction fingerprint (" + computedFingerprint + ")."
            );
        }

        this.transactionId = transactionId;
        this.transactionType = transactionType;
        this.postingKey = postingKey;
        this.currency = currency;
        this.totalAmount = totalAmount;
        this.externalReference = normalizedExternalRef;
        this.description = normalizedDescription;
        this.reversedTransactionId = reversedTransactionId;
        this.requestFingerprint = requestFingerprint;
        this.postedAt = postedAt;
        this.entries = List.copyOf(entries);
    }

    /**
     * Constructs and validates a new {@link LedgerTransactionType#STANDARD} transaction with a generated ID and timestamp.
     */
    public static LedgerTransaction post(PostLedgerTransactionCommand command) {
        return post(LedgerTransactionId.generate(), command, Instant.now());
    }

    /**
     * Constructs and validates a new {@link LedgerTransactionType#STANDARD} transaction with explicit ID and timestamp.
     */
    public static LedgerTransaction post(
            LedgerTransactionId transactionId,
            PostLedgerTransactionCommand command,
            Instant postedAt) {
        if (command == null) {
            throw new LedgerValidationException("PostLedgerTransactionCommand cannot be null.");
        }
        Currency currency = command.currency();
        List<LedgerEntryLine> lines = command.entries();
        List<LedgerEntry> domainEntries = new ArrayList<>(lines.size());
        Money debitTotal = Money.zero(currency);

        for (int i = 0; i < lines.size(); i++) {
            LedgerEntryLine line = lines.get(i);
            LedgerEntry entry = new LedgerEntry(
                    UUID.randomUUID(),
                    i + 1,
                    line.account(),
                    line.direction(),
                    line.amount()
            );
            domainEntries.add(entry);
            if (line.direction() == EntryDirection.DEBIT) {
                debitTotal = debitTotal.add(line.amount());
            }
        }

        if (!debitTotal.isPositive()) {
            throw new LedgerValidationException(
                    "Unbalanced LedgerTransaction: transaction must have positive debit and credit totals."
            );
        }
        if (debitTotal.getAmount().compareTo(LedgerLimits.MAX_AMOUNT) > 0) {
            throw new LedgerValidationException(
                    "Total debit amount (" + debitTotal.getAmount().toPlainString()
                            + ") exceeds NUMERIC(19,4) maximum (" + LedgerLimits.MAX_AMOUNT.toPlainString() + ")."
            );
        }

        PostingFingerprint fingerprint = PostingFingerprint.forTransaction(
                LedgerTransactionType.STANDARD,
                currency,
                debitTotal,
                command.externalReference(),
                null,
                lines
        );

        return new LedgerTransaction(
                transactionId,
                LedgerTransactionType.STANDARD,
                command.postingKey(),
                currency,
                debitTotal,
                command.externalReference(),
                command.description(),
                null,
                fingerprint,
                postedAt,
                domainEntries
        );
    }

    /**
     * Creates a full compensating {@link LedgerTransactionType#REVERSAL} transaction from this {@code STANDARD} transaction.
     */
    public LedgerTransaction reverse(ReverseLedgerTransactionCommand command) {
        return reverse(LedgerTransactionId.generate(), command, Instant.now());
    }

    /**
     * Creates a full compensating {@link LedgerTransactionType#REVERSAL} transaction with an explicit ID and timestamp.
     */
    public LedgerTransaction reverse(
            LedgerTransactionId reversalTransactionId,
            ReverseLedgerTransactionCommand command,
            Instant postedAt) {
        if (command == null) {
            throw new LedgerValidationException("ReverseLedgerTransactionCommand cannot be null.");
        }
        if (this.transactionType != LedgerTransactionType.STANDARD || this.reversedTransactionId != null) {
            throw new LedgerValidationException(
                    "Cannot reverse transaction '" + this.transactionId + "' because it is already a REVERSAL."
            );
        }
        if (!this.transactionId.equals(command.originalTransactionId())) {
            throw new LedgerValidationException(
                    "ReverseLedgerTransactionCommand originalTransactionId (" + command.originalTransactionId()
                            + ") does not match target transactionId (" + this.transactionId + ")."
            );
        }

        List<LedgerEntry> flippedEntries = new ArrayList<>(this.entries.size());
        List<LedgerEntryLine> flippedLines = new ArrayList<>(this.entries.size());

        for (int i = 0; i < this.entries.size(); i++) {
            LedgerEntry origEntry = this.entries.get(i);
            EntryDirection flippedDirection = origEntry.getDirection().opposite();
            LedgerEntry revEntry = new LedgerEntry(
                    UUID.randomUUID(),
                    i + 1,
                    origEntry.getAccount(),
                    flippedDirection,
                    origEntry.getAmount()
            );
            flippedEntries.add(revEntry);
            flippedLines.add(revEntry.toEntryLine());
        }

        LedgerPostingKey reversalKey = LedgerPostingKey.forReversal(this.transactionId);
        PostingFingerprint fingerprint = PostingFingerprint.forTransaction(
                LedgerTransactionType.REVERSAL,
                this.currency,
                this.totalAmount,
                command.reversalReference(),
                this.transactionId,
                flippedLines
        );

        return new LedgerTransaction(
                reversalTransactionId,
                LedgerTransactionType.REVERSAL,
                reversalKey,
                this.currency,
                this.totalAmount,
                command.reversalReference(),
                command.reason(),
                this.transactionId,
                fingerprint,
                postedAt,
                flippedEntries
        );
    }

    /**
     * Restores a persisted {@link LedgerTransaction} and validates all domain invariants.
     */
    public static LedgerTransaction restore(
            LedgerTransactionId transactionId,
            LedgerTransactionType transactionType,
            LedgerPostingKey postingKey,
            Currency currency,
            Money totalAmount,
            String externalReference,
            String description,
            LedgerTransactionId reversedTransactionId,
            PostingFingerprint requestFingerprint,
            Instant postedAt,
            List<LedgerEntry> entries) {
        return new LedgerTransaction(
                transactionId,
                transactionType,
                postingKey,
                currency,
                totalAmount,
                externalReference,
                description,
                reversedTransactionId,
                requestFingerprint,
                postedAt,
                entries
        );
    }

    public LedgerTransactionView toView() {
        List<LedgerEntryView> entryViews = new ArrayList<>(entries.size());
        for (LedgerEntry entry : entries) {
            entryViews.add(entry.toView());
        }
        return new LedgerTransactionView(
                transactionId,
                transactionType,
                postingKey,
                currency,
                totalAmount,
                externalReference,
                description,
                reversedTransactionId,
                postedAt,
                entryViews
        );
    }

    public LedgerTransactionId getTransactionId() {
        return transactionId;
    }

    public LedgerTransactionType getTransactionType() {
        return transactionType;
    }

    public LedgerPostingKey getPostingKey() {
        return postingKey;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Money getTotalAmount() {
        return totalAmount;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public String getDescription() {
        return description;
    }

    public LedgerTransactionId getReversedTransactionId() {
        return reversedTransactionId;
    }

    public PostingFingerprint getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public List<LedgerEntry> getEntries() {
        return entries;
    }
}
