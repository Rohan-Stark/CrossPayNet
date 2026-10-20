package com.crosspaynet.ledger.domain;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.EntryDirection;
import com.crosspaynet.ledger.LedgerAccountRef;
import com.crosspaynet.ledger.LedgerAccountType;
import com.crosspaynet.ledger.LedgerEntryLine;
import com.crosspaynet.ledger.LedgerPostingKey;
import com.crosspaynet.ledger.LedgerTransactionId;
import com.crosspaynet.ledger.LedgerTransactionType;
import com.crosspaynet.ledger.LedgerValidationException;
import com.crosspaynet.ledger.PostLedgerTransactionCommand;
import com.crosspaynet.ledger.ReverseLedgerTransactionCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PostingFingerprintTest {

    private final Currency usd = new Currency("USD");
    private final LedgerAccountRef accA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-A");
    private final LedgerAccountRef accB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-B");
    private final LedgerAccountRef accC = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-C");

    @Test
    void testDeterminismEntryOrderIndependenceAndDescriptionExclusion() {
        LedgerEntryLine debitA = new LedgerEntryLine(accA, EntryDirection.DEBIT, Money.of("100.5", "USD"));
        LedgerEntryLine creditB = new LedgerEntryLine(accB, EntryDirection.CREDIT, Money.of("60.2500", "USD"));
        LedgerEntryLine creditC = new LedgerEntryLine(accC, EntryDirection.CREDIT, Money.of("40.25", "USD"));

        PostLedgerTransactionCommand cmdOrder1 = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("KEY-ORDER-1"),
                usd,
                List.of(debitA, creditB, creditC),
                "EXT-REF-100",
                "First description"
        );
        PostLedgerTransactionCommand cmdOrder2DifferentDesc = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("KEY-ORDER-2"),
                usd,
                List.of(creditC, debitA, creditB),
                "EXT-REF-100",
                "Completely different description"
        );
        PostLedgerTransactionCommand cmdOrder3NullDesc = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("KEY-ORDER-3"),
                usd,
                List.of(creditB, creditC, debitA),
                "EXT-REF-100",
                null
        );

        PostingFingerprint fp1 = PostingFingerprint.forStandard(cmdOrder1);
        PostingFingerprint fp2 = PostingFingerprint.forStandard(cmdOrder2DifferentDesc);
        PostingFingerprint fp3 = PostingFingerprint.forStandard(cmdOrder3NullDesc);

        assertEquals(64, fp1.getValue().length());
        assertTrue(fp1.getValue().matches("^[0-9a-f]{64}$"));
        assertEquals(fp1, fp2);
        assertEquals(fp1, fp3);
        assertEquals(fp1.hashCode(), fp2.hashCode());

        // Reversal reason also excluded from reversal fingerprint
        LedgerTransaction tx = LedgerTransaction.post(cmdOrder1);
        LedgerTransaction rev1 = tx.reverse(new ReverseLedgerTransactionCommand(
                tx.getTransactionId(), "REV-REF-1", "Reason A"
        ));
        LedgerTransaction rev2 = tx.reverse(new ReverseLedgerTransactionCommand(
                tx.getTransactionId(), "REV-REF-1", null
        ));
        assertEquals(rev1.getRequestFingerprint(), rev2.getRequestFingerprint());
    }

    @Test
    void testSensitivityToEveryFinancialField() {
        List<LedgerEntryLine> baseEntries = List.of(
                new LedgerEntryLine(accA, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                new LedgerEntryLine(accB, EntryDirection.CREDIT, Money.of("100.0000", "USD"))
        );
        PostingFingerprint baseFp = PostingFingerprint.forTransaction(
                LedgerTransactionType.STANDARD,
                usd,
                Money.of("100.0000", "USD"),
                "EXT-REF-1",
                null,
                baseEntries
        );

        // 1. Different externalReference
        PostingFingerprint diffExtRef = PostingFingerprint.forTransaction(
                LedgerTransactionType.STANDARD,
                usd,
                Money.of("100.0000", "USD"),
                "EXT-REF-2",
                null,
                baseEntries
        );
        assertNotEquals(baseFp, diffExtRef);

        // 2. Different transactionType / reversedTransactionId
        PostingFingerprint reversalFp = PostingFingerprint.forTransaction(
                LedgerTransactionType.REVERSAL,
                usd,
                Money.of("100.0000", "USD"),
                "EXT-REF-1",
                LedgerTransactionId.generate(),
                baseEntries
        );
        assertNotEquals(baseFp, reversalFp);

        // 3. Different currency
        Currency eur = new Currency("EUR");
        PostingFingerprint diffCurrency = PostingFingerprint.forTransaction(
                LedgerTransactionType.STANDARD,
                eur,
                Money.of("100.0000", "EUR"),
                "EXT-REF-1",
                null,
                List.of(
                        new LedgerEntryLine(accA, EntryDirection.DEBIT, Money.of("100.0000", "EUR")),
                        new LedgerEntryLine(accB, EntryDirection.CREDIT, Money.of("100.0000", "EUR"))
                )
        );
        assertNotEquals(baseFp, diffCurrency);

        // 4. Different amount
        PostingFingerprint diffAmount = PostingFingerprint.forTransaction(
                LedgerTransactionType.STANDARD,
                usd,
                Money.of("200.0000", "USD"),
                "EXT-REF-1",
                null,
                List.of(
                        new LedgerEntryLine(accA, EntryDirection.DEBIT, Money.of("200.0000", "USD")),
                        new LedgerEntryLine(accB, EntryDirection.CREDIT, Money.of("200.0000", "USD"))
                )
        );
        assertNotEquals(baseFp, diffAmount);

        // 5. Swapped entry directions
        PostingFingerprint swappedDirections = PostingFingerprint.forTransaction(
                LedgerTransactionType.STANDARD,
                usd,
                Money.of("100.0000", "USD"),
                "EXT-REF-1",
                null,
                List.of(
                        new LedgerEntryLine(accA, EntryDirection.CREDIT, Money.of("100.0000", "USD")),
                        new LedgerEntryLine(accB, EntryDirection.DEBIT, Money.of("100.0000", "USD"))
                )
        );
        assertNotEquals(baseFp, swappedDirections);

        // 6. Different accountType with same accountReference value
        LedgerAccountRef accAInternal = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-A");
        PostingFingerprint diffAccountType = PostingFingerprint.forTransaction(
                LedgerTransactionType.STANDARD,
                usd,
                Money.of("100.0000", "USD"),
                "EXT-REF-1",
                null,
                List.of(
                        new LedgerEntryLine(accAInternal, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                        new LedgerEntryLine(accB, EntryDirection.CREDIT, Money.of("100.0000", "USD"))
                )
        );
        assertNotEquals(baseFp, diffAccountType);
    }

    @Test
    void testCanonicalFormatUtf8ByteLengthsAndDelimiterCollisionResistance() {
        // Multi-byte UTF-8 account reference: "口座-€" -> '口'(3) + '座'(3) + '-'(1) + '€'(3) = 10 UTF-8 bytes (4 chars)
        LedgerAccountRef unicodeAcc = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "口座-€");
        String canonical = PostingFingerprint.toCanonicalString(
                LedgerTransactionType.STANDARD,
                usd,
                new Money(new BigDecimal("10.5"), usd),
                "REF|WITH:DELIMS",
                null,
                List.of(
                        new LedgerEntryLine(accA, EntryDirection.DEBIT, Money.of("10.5000", "USD")),
                        new LedgerEntryLine(unicodeAcc, EntryDirection.CREDIT, Money.of("10.50", "USD"))
                )
        );

        assertTrue(canonical.startsWith("crosspaynet-ledger-fingerprint-v1\n"));
        assertTrue(canonical.contains("\n8:STANDARD\n3:USD\n7:10.5000\n15:REF|WITH:DELIMS\nNULL\n1:2\n"));
        // Verify UTF-8 byte length (10) is used rather than UTF-16 char length (4)
        assertTrue(canonical.contains("8:INTERNAL|10:口座-€|6:CREDIT|7:10.5000"));

        // Verify delimiter injection attempt cannot collide across accountReference and direction
        LedgerAccountRef craftedAcc1 = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC|6:DEBIT");
        LedgerAccountRef craftedAcc2 = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC");
        PostingFingerprint fpCrafted1 = PostingFingerprint.forTransaction(
                LedgerTransactionType.STANDARD,
                usd,
                Money.of("10.0000", "USD"),
                "REF-1",
                null,
                List.of(
                        new LedgerEntryLine(craftedAcc1, EntryDirection.DEBIT, Money.of("10.0000", "USD")),
                        new LedgerEntryLine(accB, EntryDirection.CREDIT, Money.of("10.0000", "USD"))
                )
        );
        PostingFingerprint fpCrafted2 = PostingFingerprint.forTransaction(
                LedgerTransactionType.STANDARD,
                usd,
                Money.of("10.0000", "USD"),
                "REF-1",
                null,
                List.of(
                        new LedgerEntryLine(craftedAcc2, EntryDirection.DEBIT, Money.of("10.0000", "USD")),
                        new LedgerEntryLine(accB, EntryDirection.CREDIT, Money.of("10.0000", "USD"))
                )
        );
        assertNotEquals(fpCrafted1, fpCrafted2);

        // Verify ofHex validation
        assertThrows(LedgerValidationException.class, () -> PostingFingerprint.ofHex(null));
        assertThrows(LedgerValidationException.class, () -> PostingFingerprint.ofHex("abc"));
        assertThrows(LedgerValidationException.class, () -> PostingFingerprint.ofHex("A".repeat(64))); // uppercase rejected
    }
}
