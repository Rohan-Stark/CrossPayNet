package com.crosspaynet.ledger.domain;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.LedgerEntryLine;
import com.crosspaynet.ledger.LedgerTransactionId;
import com.crosspaynet.ledger.LedgerTransactionType;
import com.crosspaynet.ledger.LedgerValidationException;
import com.crosspaynet.ledger.PostLedgerTransactionCommand;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic SHA-256 financial fingerprint over a versioned, UTF-8 byte-length-prefixed
 * canonical representation of a Ledger transaction request (ADR-006).
 */
public final class PostingFingerprint {

    public static final String VERSION_HEADER = "crosspaynet-ledger-fingerprint-v1";
    private static final String NULL_TOKEN = "NULL";
    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final String value;

    private PostingFingerprint(String value) {
        this.value = value;
    }

    public static PostingFingerprint ofHex(String hexDigest) {
        if (hexDigest == null || !HEX_64_PATTERN.matcher(hexDigest).matches()) {
            throw new LedgerValidationException(
                    "PostingFingerprint must be a 64-character lowercase hexadecimal SHA-256 string."
            );
        }
        return new PostingFingerprint(hexDigest);
    }

    public static PostingFingerprint forStandard(PostLedgerTransactionCommand command) {
        if (command == null) {
            throw new LedgerValidationException("command cannot be null when computing PostingFingerprint.");
        }
        Money totalDebit = computeDebitTotal(command.currency(), command.entries());
        return forTransaction(
                LedgerTransactionType.STANDARD,
                command.currency(),
                totalDebit,
                command.externalReference(),
                null,
                command.entries()
        );
    }

    public static PostingFingerprint forTransaction(
            LedgerTransactionType transactionType,
            Currency currency,
            Money totalAmount,
            String externalReference,
            LedgerTransactionId reversedTransactionId,
            List<LedgerEntryLine> entries) {
        String canonical = toCanonicalString(
                transactionType,
                currency,
                totalAmount,
                externalReference,
                reversedTransactionId,
                entries
        );
        return new PostingFingerprint(sha256Hex(canonical));
    }

    public static String toCanonicalString(
            LedgerTransactionType transactionType,
            Currency currency,
            Money totalAmount,
            String externalReference,
            LedgerTransactionId reversedTransactionId,
            List<LedgerEntryLine> entries) {
        if (transactionType == null) {
            throw new LedgerValidationException("transactionType cannot be null for fingerprint canonicalization.");
        }
        SupportedCurrencyPolicy.requireSupported(currency);
        if (totalAmount == null || !totalAmount.isPositive()) {
            throw new LedgerValidationException("totalAmount must be non-null and strictly positive for fingerprint.");
        }
        if (!currency.equals(totalAmount.getCurrency())) {
            throw new LedgerValidationException("totalAmount currency must match transaction currency for fingerprint.");
        }
        if (totalAmount.getAmount().compareTo(LedgerLimits.MAX_AMOUNT) > 0) {
            throw new LedgerValidationException("totalAmount exceeds NUMERIC(19,4) maximum for fingerprint.");
        }
        if (externalReference == null || externalReference.trim().isEmpty()) {
            throw new LedgerValidationException("externalReference cannot be null or blank for fingerprint.");
        }
        String normalizedExternalRef = externalReference.trim();
        if (normalizedExternalRef.length() > LedgerLimits.MAX_EXTERNAL_REFERENCE_LENGTH) {
            throw new LedgerValidationException("externalReference exceeds maximum length for fingerprint.");
        }
        if (transactionType == LedgerTransactionType.STANDARD && reversedTransactionId != null) {
            throw new LedgerValidationException("STANDARD transaction cannot have reversedTransactionId in fingerprint.");
        }
        if (transactionType == LedgerTransactionType.REVERSAL && reversedTransactionId == null) {
            throw new LedgerValidationException("REVERSAL transaction must have reversedTransactionId in fingerprint.");
        }
        if (entries == null || entries.size() < LedgerLimits.MIN_ENTRIES || entries.size() > LedgerLimits.MAX_ENTRIES) {
            throw new LedgerValidationException(
                    "entries must contain between " + LedgerLimits.MIN_ENTRIES + " and "
                            + LedgerLimits.MAX_ENTRIES + " items for fingerprint."
            );
        }

        List<String> canonicalEntries = new ArrayList<>(entries.size());
        for (LedgerEntryLine entry : entries) {
            if (entry == null) {
                throw new LedgerValidationException("entries cannot contain null elements for fingerprint.");
            }
            if (!currency.equals(entry.amount().getCurrency())) {
                throw new LedgerValidationException("Entry currency must match transaction currency for fingerprint.");
            }
            String tuple = encodeToken(entry.account().type().name())
                    + "|" + encodeToken(entry.account().value())
                    + "|" + encodeToken(entry.direction().name())
                    + "|" + encodeToken(formatCanonicalAmount(entry.amount()));
            canonicalEntries.add(tuple);
        }
        canonicalEntries.sort(Comparator.naturalOrder());

        StringBuilder sb = new StringBuilder(256 + canonicalEntries.size() * 64);
        sb.append(VERSION_HEADER).append('\n');
        sb.append(encodeToken(transactionType.name())).append('\n');
        sb.append(encodeToken(currency.getCode())).append('\n');
        sb.append(encodeToken(formatCanonicalAmount(totalAmount))).append('\n');
        sb.append(encodeToken(normalizedExternalRef)).append('\n');
        sb.append(reversedTransactionId == null
                ? NULL_TOKEN
                : encodeToken(reversedTransactionId.getValue().toString())).append('\n');
        sb.append(encodeToken(Integer.toString(canonicalEntries.size()))).append('\n');
        for (String canonicalEntry : canonicalEntries) {
            sb.append(canonicalEntry).append('\n');
        }
        return sb.toString();
    }

    public String getValue() {
        return value;
    }

    private static Money computeDebitTotal(Currency currency, List<LedgerEntryLine> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new LedgerValidationException("entries cannot be null or empty.");
        }
        Money totalDebit = Money.zero(currency);
        for (LedgerEntryLine entry : entries) {
            if (entry == null) {
                throw new LedgerValidationException("entries cannot contain null elements.");
            }
            if (!currency.equals(entry.amount().getCurrency())) {
                throw new LedgerValidationException("Entry currency must match transaction currency.");
            }
            if (entry.direction() == com.crosspaynet.ledger.EntryDirection.DEBIT) {
                totalDebit = totalDebit.add(entry.amount());
            }
        }
        return totalDebit;
    }

    private static String formatCanonicalAmount(Money money) {
        return money.getAmount()
                .setScale(LedgerLimits.MAX_SCALE, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    static String encodeToken(String value) {
        if (value == null) {
            return NULL_TOKEN;
        }
        byte[] utf8Bytes = value.getBytes(StandardCharsets.UTF_8);
        return utf8Bytes.length + ":" + value;
    }

    private static String sha256Hex(String canonicalInput) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalInput.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available in JDK", ex);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PostingFingerprint that = (PostingFingerprint) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
