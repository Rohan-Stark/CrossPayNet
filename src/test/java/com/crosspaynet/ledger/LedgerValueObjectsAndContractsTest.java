package com.crosspaynet.ledger;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LedgerValueObjectsAndContractsTest {

    private final Currency usd = new Currency("USD");
    private final LedgerAccountRef customerAcc = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-CUST-001");
    private final LedgerAccountRef internalAcc = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "NOSTRO-USD-001");

    @Test
    void testLedgerTransactionIdCreationAndValidation() {
        LedgerTransactionId generated = LedgerTransactionId.generate();
        assertNotNull(generated.getValue());

        UUID rawUuid = UUID.randomUUID();
        LedgerTransactionId fromString = LedgerTransactionId.of(rawUuid.toString());
        assertEquals(new LedgerTransactionId(rawUuid), fromString);
        assertEquals(new LedgerTransactionId(rawUuid).hashCode(), fromString.hashCode());
        assertEquals(rawUuid.toString(), fromString.toString());

        assertThrows(LedgerValidationException.class, () -> new LedgerTransactionId(null));
        assertThrows(LedgerValidationException.class, () -> LedgerTransactionId.of(null));
        assertThrows(LedgerValidationException.class, () -> LedgerTransactionId.of("   "));
        assertThrows(LedgerValidationException.class, () -> LedgerTransactionId.of("not-a-uuid"));
    }

    @Test
    void testLedgerPostingKeyValidAndBoundaryLengths() {
        LedgerPostingKey minKey = LedgerPostingKey.of("A");
        assertEquals("A", minKey.getValue());
        assertFalse(minKey.isReversalKey());

        String max200 = "K".repeat(200);
        LedgerPostingKey maxKey = LedgerPostingKey.of(max200);
        assertEquals(max200, maxKey.getValue());

        LedgerPostingKey allowedChars = LedgerPostingKey.of("PAY:2026_10-20.001");
        assertEquals("PAY:2026_10-20.001", allowedChars.getValue());

        // Keys containing "REVERSAL" without the "REVERSAL:" prefix are permitted
        assertDoesNotThrow(() -> LedgerPostingKey.of("REVERSAL_001"));
        assertDoesNotThrow(() -> LedgerPostingKey.of("MY_REVERSAL:001"));
    }

    @Test
    void testLedgerPostingKeyRejectsInvalidCharactersLengthAndReservedPrefix() {
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of(null));
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of(""));
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of("   "));
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of("K".repeat(201)));
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of("KEY WITH SPACE"));
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of(" LEADING"));
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of("TRAILING "));
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of("KEY/SLASH"));
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of("KEY@AT"));

        // Caller-supplied standard posting keys must not start with REVERSAL: (case-insensitively)
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of("REVERSAL:123"));
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of("reversal:123"));
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.of("ReVeRsAl:abc"));

        // Internal deterministic reversal factory succeeds and marks isReversalKey()
        LedgerTransactionId origId = LedgerTransactionId.generate();
        LedgerPostingKey reversalKey = LedgerPostingKey.forReversal(origId);
        assertTrue(reversalKey.isReversalKey());
        assertEquals("REVERSAL:" + origId.getValue(), reversalKey.getValue());
        assertThrows(LedgerValidationException.class, () -> LedgerPostingKey.forReversal(null));
    }

    @Test
    void testLedgerAccountRefValidationRejectsUntrimmedControlCharsAndOutOfBounds() {
        LedgerAccountRef unicodeRef = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "NOSTRO-ZÜRICH-東京-01");
        assertEquals(LedgerAccountType.INTERNAL, unicodeRef.type());
        assertEquals("NOSTRO-ZÜRICH-東京-01", unicodeRef.value());

        // Exactly 100 supplementary Unicode code points (200 UTF-16 chars via surrogate pairs)
        String hundredSupplementaryCodePoints = "\uD83D\uDCB0".repeat(100);
        assertEquals(100, hundredSupplementaryCodePoints.codePointCount(0, hundredSupplementaryCodePoints.length()));
        LedgerAccountRef maxUnicodeRef = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, hundredSupplementaryCodePoints);
        assertEquals(hundredSupplementaryCodePoints, maxUnicodeRef.value());

        // 101 Unicode code points rejected
        String hundredOneCodePoints = "A".repeat(101);
        assertThrows(LedgerValidationException.class,
                () -> LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, hundredOneCodePoints));

        // Leading or trailing whitespace must be rejected, not silently trimmed
        assertThrows(LedgerValidationException.class,
                () -> LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, " ACC-001"));
        assertThrows(LedgerValidationException.class,
                () -> LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-001 "));
        assertThrows(LedgerValidationException.class,
                () -> LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "\u00A0ACC-001"));

        // Null, blank, and ISO control characters rejected
        assertThrows(LedgerValidationException.class, () -> LedgerAccountRef.of(null, "ACC-001"));
        assertThrows(LedgerValidationException.class, () -> LedgerAccountRef.of(LedgerAccountType.INTERNAL, null));
        assertThrows(LedgerValidationException.class, () -> LedgerAccountRef.of(LedgerAccountType.INTERNAL, ""));
        assertThrows(LedgerValidationException.class, () -> LedgerAccountRef.of(LedgerAccountType.INTERNAL, "   "));
        assertThrows(LedgerValidationException.class, () -> LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC\n001"));
        assertThrows(LedgerValidationException.class, () -> LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC\u0000001"));
        assertThrows(LedgerValidationException.class, () -> LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC\u007F001"));
        assertThrows(LedgerValidationException.class, () -> LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC\u0085001"));
    }

    @Test
    void testCommandsNormalizeMetadataAndCopyEntriesDefensively() {
        List<LedgerEntryLine> mutableLines = new ArrayList<>();
        mutableLines.add(new LedgerEntryLine(customerAcc, EntryDirection.DEBIT, Money.of("50.0000", "USD")));
        mutableLines.add(new LedgerEntryLine(internalAcc, EntryDirection.CREDIT, Money.of("50.0000", "USD")));

        PostLedgerTransactionCommand postCmd = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("POST-1"),
                usd,
                mutableLines,
                "  EXT-REF-001  ",
                "   "
        );
        assertEquals("EXT-REF-001", postCmd.externalReference());
        assertNull(postCmd.description());

        mutableLines.clear();
        assertEquals(2, postCmd.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> postCmd.entries().clear());

        ReverseLedgerTransactionCommand revCmd = new ReverseLedgerTransactionCommand(
                LedgerTransactionId.generate(),
                "  REV-REF-001  ",
                "  Duplicate settlement  "
        );
        assertEquals("REV-REF-001", revCmd.reversalReference());
        assertEquals("Duplicate settlement", revCmd.reason());

        ReverseLedgerTransactionCommand revBlankReason = new ReverseLedgerTransactionCommand(
                LedgerTransactionId.generate(),
                "REV-REF-002",
                "   "
        );
        assertNull(revBlankReason.reason());

        assertThrows(LedgerValidationException.class, () -> new ReverseLedgerTransactionCommand(
                LedgerTransactionId.generate(), "   ", "reason"
        ));
        assertThrows(LedgerValidationException.class, () -> new ReverseLedgerTransactionCommand(
                LedgerTransactionId.generate(), "R".repeat(256), "reason"
        ));
        assertThrows(LedgerValidationException.class, () -> new ReverseLedgerTransactionCommand(
                LedgerTransactionId.generate(), "REV-REF", "D".repeat(501)
        ));
    }

    @Test
    void testLedgerBalanceEnforcesDebitMinusCreditAndScaleFour() {
        Money debit = Money.of("250.5000", "USD");
        Money credit = Money.of("100.2500", "USD");

        LedgerBalance balance = LedgerBalance.of(customerAcc, usd, debit, credit);
        assertEquals(new BigDecimal("150.2500"), balance.netBalance());

        // Scale-safe normalization when constructing with BigDecimal("150.25")
        LedgerBalance fromUnscaledNet = new LedgerBalance(
                customerAcc, usd, debit, credit, new BigDecimal("150.25")
        );
        assertEquals(new BigDecimal("150.2500"), fromUnscaledNet.netBalance());

        // Negative net balance when credit > debit
        LedgerBalance creditHeavy = LedgerBalance.of(customerAcc, usd, credit, debit);
        assertEquals(new BigDecimal("-150.2500"), creditHeavy.netBalance());

        // Zero balance helper
        LedgerBalance zeroBalance = LedgerBalance.zero(customerAcc, usd);
        assertEquals(new BigDecimal("0.0000"), zeroBalance.netBalance());

        // Rejects wrong netBalance (e.g. creditTotal - debitTotal instead of debitTotal - creditTotal)
        assertThrows(LedgerValidationException.class, () -> new LedgerBalance(
                customerAcc, usd, debit, credit, new BigDecimal("-150.2500")
        ));

        // Rejects excess scale (> 4) without silent rounding
        assertThrows(LedgerValidationException.class, () -> new LedgerBalance(
                customerAcc, usd, debit, credit, new BigDecimal("150.25001")
        ));

        // Rejects negative debitTotal or creditTotal
        assertThrows(LedgerValidationException.class, () -> LedgerBalance.of(
                customerAcc, usd, Money.of("-1.0000", "USD"), credit
        ));
    }

    @Test
    void testExceptionHierarchyAndMetadata() {
        LedgerPostingKey key = LedgerPostingKey.of("KEY-1");
        LedgerTransactionId origId = LedgerTransactionId.generate();
        LedgerTransactionId revId = LedgerTransactionId.generate();

        LedgerPostingKeyConflictException keyConflict = new LedgerPostingKeyConflictException(key, origId);
        assertInstanceOf(LedgerValidationException.class, keyConflict);
        assertInstanceOf(LedgerException.class, keyConflict);
        assertEquals(key, keyConflict.getPostingKey());
        assertEquals(origId, keyConflict.getExistingTransactionId());

        LedgerReversalConflictException revConflict = new LedgerReversalConflictException(origId, revId);
        assertInstanceOf(LedgerValidationException.class, revConflict);
        assertEquals(origId, revConflict.getOriginalTransactionId());
        assertEquals(revId, revConflict.getExistingReversalTransactionId());

        LedgerTransactionNotFoundException notFound = new LedgerTransactionNotFoundException(origId);
        assertInstanceOf(LedgerValidationException.class, notFound);
        assertEquals(origId, notFound.getTransactionId());

        LedgerPostingResult result = new LedgerPostingResult(origId, PostingOutcome.POSTED, Instant.now());
        assertEquals(PostingOutcome.POSTED, result.outcome());
    }
}
