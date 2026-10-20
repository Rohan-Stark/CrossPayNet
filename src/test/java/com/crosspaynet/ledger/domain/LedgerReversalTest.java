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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LedgerReversalTest {

    private final Currency eur = new Currency("EUR");
    private final LedgerAccountRef senderAcc = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-SENDER-EUR");
    private final LedgerAccountRef nostroAcc = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "NOSTRO-EUR-01");
    private final LedgerAccountRef feeAcc = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "FEE-EUR-01");

    @Test
    void testReversalFlipsDirectionsPreservesOriginalAndDerivesDeterministicPostingKey() {
        PostLedgerTransactionCommand postCommand = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-EUR-001"),
                eur,
                List.of(
                        new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("500.0000", "EUR")),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("490.0000", "EUR")),
                        new LedgerEntryLine(feeAcc, EntryDirection.CREDIT, Money.of("10.0000", "EUR"))
                ),
                "EXT-EUR-001",
                "Original EUR settlement"
        );
        LedgerTransaction original = LedgerTransaction.post(postCommand);
        List<LedgerEntry> snapshotOriginalEntries = List.copyOf(original.getEntries());

        ReverseLedgerTransactionCommand reverseCommand = new ReverseLedgerTransactionCommand(
                original.getTransactionId(),
                "REV-REF-EUR-001",
                "Correspondent settlement return"
        );

        LedgerTransaction reversal = original.reverse(reverseCommand);

        // Header checks
        assertNotEquals(original.getTransactionId(), reversal.getTransactionId());
        assertEquals(LedgerTransactionType.REVERSAL, reversal.getTransactionType());
        assertEquals(original.getTransactionId(), reversal.getReversedTransactionId());
        assertEquals("REVERSAL:" + original.getTransactionId().getValue(), reversal.getPostingKey().getValue());
        assertTrue(reversal.getPostingKey().isReversalKey());
        assertEquals(eur, reversal.getCurrency());
        assertEquals(Money.of("500.0000", "EUR"), reversal.getTotalAmount());
        assertEquals("REV-REF-EUR-001", reversal.getExternalReference());
        assertEquals("Correspondent settlement return", reversal.getDescription());

        // Entry checks: 1-to-1 mirror with flipped directions
        assertEquals(3, reversal.getEntries().size());
        for (int i = 0; i < 3; i++) {
            LedgerEntry origEntry = original.getEntries().get(i);
            LedgerEntry revEntry = reversal.getEntries().get(i);

            assertEquals(i + 1, revEntry.getLineNumber());
            assertNotEquals(origEntry.getEntryId(), revEntry.getEntryId());
            assertEquals(origEntry.getAccount(), revEntry.getAccount());
            assertEquals(origEntry.getAmount(), revEntry.getAmount());
            assertEquals(origEntry.getDirection().opposite(), revEntry.getDirection());
        }

        // Original transaction remains completely unchanged
        assertEquals(LedgerTransactionType.STANDARD, original.getTransactionType());
        assertNull(original.getReversedTransactionId());
        assertEquals("EXT-EUR-001", original.getExternalReference());
        assertEquals(snapshotOriginalEntries, original.getEntries());
    }

    @Test
    void testReversalOfReversalRejected() {
        PostLedgerTransactionCommand postCommand = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-EUR-002"),
                eur,
                List.of(
                        new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("100.0000", "EUR")),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("100.0000", "EUR"))
                ),
                "EXT-EUR-002",
                null
        );
        LedgerTransaction original = LedgerTransaction.post(postCommand);
        LedgerTransaction reversal = original.reverse(new ReverseLedgerTransactionCommand(
                original.getTransactionId(),
                "REV-REF-002",
                null
        ));
        assertNull(reversal.getDescription());

        // Attempting to reverse a REVERSAL transaction must be rejected
        ReverseLedgerTransactionCommand secondReversalCmd = new ReverseLedgerTransactionCommand(
                reversal.getTransactionId(),
                "REV-OF-REV-REF",
                "Should fail"
        );
        assertThrows(LedgerValidationException.class, () -> reversal.reverse(secondReversalCmd));
    }

    @Test
    void testReversalRejectsMismatchedOriginalTransactionIdOrNullCommand() {
        PostLedgerTransactionCommand postCommand = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-EUR-003"),
                eur,
                List.of(
                        new LedgerEntryLine(senderAcc, EntryDirection.DEBIT, Money.of("100.0000", "EUR")),
                        new LedgerEntryLine(nostroAcc, EntryDirection.CREDIT, Money.of("100.0000", "EUR"))
                ),
                "EXT-EUR-003",
                null
        );
        LedgerTransaction original = LedgerTransaction.post(postCommand);

        assertThrows(LedgerValidationException.class, () -> original.reverse(null));

        ReverseLedgerTransactionCommand wrongTargetCmd = new ReverseLedgerTransactionCommand(
                LedgerTransactionId.generate(),
                "REV-REF-003",
                null
        );
        assertThrows(LedgerValidationException.class, () -> original.reverse(wrongTargetCmd));
    }
}
