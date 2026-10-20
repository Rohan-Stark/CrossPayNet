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
import com.crosspaynet.ledger.LedgerTransactionView;
import com.crosspaynet.ledger.LedgerValidationException;
import com.crosspaynet.ledger.PostLedgerTransactionCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LedgerTransactionTest {

    private final Currency usd = new Currency("USD");
    private final LedgerAccountRef senderAcc = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-SENDER-01");
    private final LedgerAccountRef nostroAcc = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "NOSTRO-BANK-A");
    private final LedgerAccountRef feeAcc = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "INTERNAL-FEE-USD");

    @Test
    void testValidTwoEntryBalancedTransaction() {
        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-SETTLE-001"),
                usd,
                List.of(
                        new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("1250.7500", "USD")),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("1250.7500", "USD"))
                ),
                "PAY-REF-001",
                "Cross-border settlement"
        );

        LedgerTransaction tx = LedgerTransaction.post(command);

        assertNotNull(tx.getTransactionId());
        assertEquals(LedgerTransactionType.STANDARD, tx.getTransactionType());
        assertEquals(LedgerPostingKey.of("PAY-SETTLE-001"), tx.getPostingKey());
        assertEquals(usd, tx.getCurrency());
        assertEquals(Money.of("1250.7500", "USD"), tx.getTotalAmount());
        assertEquals("PAY-REF-001", tx.getExternalReference());
        assertEquals("Cross-border settlement", tx.getDescription());
        assertNull(tx.getReversedTransactionId());
        assertNotNull(tx.getRequestFingerprint());
        assertNotNull(tx.getPostedAt());
        assertEquals(2, tx.getEntries().size());

        assertEquals(1, tx.getEntries().get(0).getLineNumber());
        assertEquals(2, tx.getEntries().get(1).getLineNumber());
        assertNotEquals(tx.getEntries().get(0).getEntryId(), tx.getEntries().get(1).getEntryId());

        LedgerTransactionView view = tx.toView();
        assertEquals(tx.getTransactionId(), view.transactionId());
        assertEquals(2, view.entries().size());
        assertEquals(1, view.entries().get(0).lineNumber());
        assertEquals(2, view.entries().get(1).lineNumber());
    }

    @Test
    void testValidMultiEntryAndMaxHundredEntriesTransaction() {
        // 3-entry split transaction (1 debit, 2 credits)
        PostLedgerTransactionCommand splitCmd = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-SPLIT-001"),
                usd,
                List.of(
                        new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("95.0000", "USD")),
                        new LedgerEntryLine(feeAcc, EntryDirection.CREDIT, Money.of("5.0000", "USD"))
                ),
                "PAY-REF-SPLIT",
                null
        );
        LedgerTransaction splitTx = LedgerTransaction.post(splitCmd);
        assertEquals(Money.of("100.0000", "USD"), splitTx.getTotalAmount());
        assertEquals(3, splitTx.getEntries().size());
        assertEquals(3, splitTx.getEntries().get(2).getLineNumber());

        // Boundary: 100 entries (50 debits + 50 credits)
        List<LedgerEntryLine> hundredLines = new ArrayList<>(100);
        for (int i = 0; i < 50; i++) {
            hundredLines.add(new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("1.0000", "USD")));
            hundredLines.add(new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("1.0000", "USD")));
        }
        PostLedgerTransactionCommand maxCmd = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-MAX-100"),
                usd,
                hundredLines,
                "PAY-REF-100",
                null
        );
        LedgerTransaction maxTx = LedgerTransaction.post(maxCmd);
        assertEquals(100, maxTx.getEntries().size());
        assertEquals(Money.of("50.0000", "USD"), maxTx.getTotalAmount());
        assertEquals(1, maxTx.getEntries().get(0).getLineNumber());
        assertEquals(100, maxTx.getEntries().get(99).getLineNumber());
    }

    @Test
    void testUnbalancedTransactionRejected() {
        PostLedgerTransactionCommand unbalanced = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-UNBALANCED"),
                usd,
                List.of(
                        new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("99.9999", "USD"))
                ),
                "PAY-REF-UNBAL",
                null
        );
        assertThrows(LedgerValidationException.class, () -> LedgerTransaction.post(unbalanced));

        PostLedgerTransactionCommand allCredits = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-ALL-CREDITS"),
                usd,
                List.of(
                        new LedgerEntryLine(senderAcc, EntryDirection.CREDIT, Money.of("50.0000", "USD")),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("50.0000", "USD"))
                ),
                "PAY-REF-CREDITS",
                null
        );
        assertThrows(LedgerValidationException.class, () -> LedgerTransaction.post(allCredits));
    }

    @Test
    void testEntryCountBoundsRejected() {
        // Fewer than 2 entries
        assertThrows(LedgerValidationException.class, () -> new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-ONE-ENTRY"),
                usd,
                List.of(new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("10.0000", "USD"))),
                "REF-1",
                null
        ));

        // More than 100 entries (101 entries)
        List<LedgerEntryLine> hundredAndOne = new ArrayList<>(101);
        for (int i = 0; i < 101; i++) {
            hundredAndOne.add(new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("1.0000", "USD")));
        }
        assertThrows(LedgerValidationException.class, () -> new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-101-ENTRIES"),
                usd,
                hundredAndOne,
                "REF-101",
                null
        ));
    }

    @Test
    void testAmountsScaleAndNumericBoundsEnforced() {
        // Zero or negative entry amounts rejected
        assertThrows(LedgerValidationException.class,
                () -> new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.zero(usd)));
        assertThrows(LedgerValidationException.class,
                () -> new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("-10.0000", "USD")));

        // Scale > 4 rejected by Money without silent rounding
        assertThrows(IllegalArgumentException.class, () -> Money.of("10.12345", "USD"));

        // Entry exceeding NUMERIC(19,4) MAX_AMOUNT rejected
        Money overMax = new Money(new BigDecimal("1000000000000000.0000"), usd);
        assertThrows(LedgerValidationException.class,
                () -> new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, overMax));

        // Individual entries within MAX_AMOUNT whose sum overflows MAX_AMOUNT rejected by LedgerTransaction
        Money sixtyTrillion = new Money(new BigDecimal("600000000000000.0000"), usd);
        PostLedgerTransactionCommand overflowTotalCmd = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-OVERFLOW-TOTAL"),
                usd,
                List.of(
                        new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, sixtyTrillion),
                        new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, sixtyTrillion),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, sixtyTrillion),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, sixtyTrillion)
                ),
                "REF-OVERFLOW",
                null
        );
        assertThrows(LedgerValidationException.class, () -> LedgerTransaction.post(overflowTotalCmd));
    }

    @Test
    void testMixedAndUnsupportedCurrenciesRejected() {
        // Mixed currency in entry rejected
        assertThrows(LedgerValidationException.class, () -> new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-MIXED-CURR"),
                usd,
                List.of(
                        new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("100.0000", "EUR"))
                ),
                "REF-MIXED",
                null
        ));

        // Unsupported currency (e.g. CAD) rejected even though Currency("CAD") is a valid 3-letter ISO code
        Currency cad = new Currency("CAD");
        assertThrows(LedgerValidationException.class, () -> new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-UNSUPPORTED-CURR"),
                cad,
                List.of(
                        new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("100.0000", "CAD")),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("100.0000", "CAD"))
                ),
                "REF-CAD",
                null
        ));

        // All 8 supported currencies succeed
        for (String code : SupportedCurrencyPolicy.supportedCodes()) {
            Currency c = new Currency(code);
            PostLedgerTransactionCommand cmd = new PostLedgerTransactionCommand(
                    LedgerPostingKey.of("PAY-CURR-" + code),
                    c,
                    List.of(
                            new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("10.0000", code)),
                            new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("10.0000", code))
                    ),
                    "REF-" + code,
                    null
            );
            assertDoesNotThrow(() -> LedgerTransaction.post(cmd));
        }
    }

    @Test
    void testTransactionEntriesImmutabilityAndRestoreValidation() {
        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-IMMUT-001"),
                usd,
                List.of(
                        new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("50.0000", "USD")),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("50.0000", "USD"))
                ),
                "REF-IMMUT",
                "Immutable test"
        );
        LedgerTransaction tx = LedgerTransaction.post(command);

        assertThrows(UnsupportedOperationException.class, () -> tx.getEntries().clear());
        assertThrows(UnsupportedOperationException.class, () -> tx.toView().entries().clear());

        // Valid restore succeeds
        LedgerTransaction restored = LedgerTransaction.restore(
                tx.getTransactionId(),
                tx.getTransactionType(),
                tx.getPostingKey(),
                tx.getCurrency(),
                tx.getTotalAmount(),
                tx.getExternalReference(),
                tx.getDescription(),
                tx.getReversedTransactionId(),
                tx.getRequestFingerprint(),
                tx.getPostedAt(),
                tx.getEntries()
        );
        assertEquals(tx.getTransactionId(), restored.getTransactionId());
        assertEquals(tx.getRequestFingerprint(), restored.getRequestFingerprint());

        // Restore with non-contiguous line numbers (1, 3) rejected
        List<LedgerEntry> badLineNumbers = List.of(
                new LedgerEntry(UUID.randomUUID(), 1, senderAcc, EntryDirection.DEBIT, Money.of("50.0000", "USD")),
                new LedgerEntry(UUID.randomUUID(), 3, nostroAcc, EntryDirection.CREDIT, Money.of("50.0000", "USD"))
        );
        assertThrows(LedgerValidationException.class, () -> LedgerTransaction.restore(
                tx.getTransactionId(),
                LedgerTransactionType.STANDARD,
                tx.getPostingKey(),
                usd,
                Money.of("50.0000", "USD"),
                "REF-IMMUT",
                null,
                null,
                tx.getRequestFingerprint(),
                Instant.now(),
                badLineNumbers
        ));

        // Restore with tampered totalAmount rejected
        assertThrows(LedgerValidationException.class, () -> LedgerTransaction.restore(
                tx.getTransactionId(),
                LedgerTransactionType.STANDARD,
                tx.getPostingKey(),
                usd,
                Money.of("100.0000", "USD"),
                "REF-IMMUT",
                null,
                null,
                tx.getRequestFingerprint(),
                Instant.now(),
                tx.getEntries()
        ));
    }
}
