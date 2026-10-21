package com.crosspaynet.ledger;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.domain.LedgerEntry;
import com.crosspaynet.ledger.domain.LedgerTransaction;
import com.crosspaynet.ledger.domain.PostingFingerprint;
import com.crosspaynet.ledger.infrastructure.persistence.LedgerTransactionEntity;
import com.crosspaynet.ledger.infrastructure.persistence.LedgerTransactionMapper;
import com.crosspaynet.ledger.infrastructure.persistence.LedgerTransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, LedgerTransactionMapper.class})
class LedgerPersistenceIntegrationTest {

    private static final Currency USD = new Currency("USD");
    private static final Currency EUR = new Currency("EUR");
    private static final String VALID_DUMMY_FINGERPRINT = "a".repeat(64);

    @Autowired
    private LedgerTransactionRepository repository;

    @Autowired
    private LedgerTransactionMapper mapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================================
    // A. Persistence Round-Trip, 100-Entry Boundary, and Balance Aggregation
    // =========================================================================

    @Test
    void shouldPersistAndRestoreStandardTransactionRoundTrip() {
        LedgerAccountRef debitAccount = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-SENDER-001");
        LedgerAccountRef creditAccount = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "NOSTRO-USD-CLEARING");
        Instant postedAt = Instant.parse("2026-10-20T12:00:00Z");

        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-ROUNDTRIP-001"),
                USD,
                List.of(
                        new LedgerEntryLine(debitAccount, EntryDirection.DEBIT, Money.of("250.7500", "USD")),
                        new LedgerEntryLine(creditAccount, EntryDirection.CREDIT, Money.of("250.7500", "USD"))
                ),
                "EXT-REF-001",
                "Standard cross-border settlement"
        );

        LedgerTransaction original = LedgerTransaction.post(
                LedgerTransactionId.generate(),
                command,
                postedAt
        );

        repository.saveAndFlush(mapper.toEntity(original));
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        entityManager.clear();

        Optional<LedgerTransactionEntity> byId = repository.findById(original.getTransactionId().getValue());
        Optional<LedgerTransactionEntity> byKey = repository.findByPostingKey("PAY-ROUNDTRIP-001");

        assertThat(byId).isPresent();
        assertThat(byKey).isPresent();

        LedgerTransaction restored = mapper.toDomain(byId.get());
        assertThat(restored.getTransactionId()).isEqualTo(original.getTransactionId());
        assertThat(restored.getPostingKey()).isEqualTo(original.getPostingKey());
        assertThat(restored.getTransactionType()).isEqualTo(LedgerTransactionType.STANDARD);
        assertThat(restored.getCurrency()).isEqualTo(USD);
        assertThat(restored.getTotalAmount()).isEqualTo(Money.of("250.7500", "USD"));
        assertThat(restored.getExternalReference()).isEqualTo("EXT-REF-001");
        assertThat(restored.getDescription()).isEqualTo("Standard cross-border settlement");
        assertThat(restored.getReversedTransactionId()).isNull();
        assertThat(restored.getRequestFingerprint()).isEqualTo(original.getRequestFingerprint());
        assertThat(restored.getPostedAt().truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(postedAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(restored.getEntries()).containsExactlyElementsOf(original.getEntries());
    }

    @Test
    void shouldPersistAndRestoreMultiLineSplitStandardTransaction() {
        LedgerAccountRef sender = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-SPLIT-SENDER");
        LedgerAccountRef clearing = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "CLEARING-PRINCIPAL");
        LedgerAccountRef feeIncome = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "FEE-REVENUE-USD");

        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-SPLIT-001"),
                USD,
                List.of(
                        new LedgerEntryLine(sender, EntryDirection.DEBIT, Money.of("150.0000", "USD")),
                        new LedgerEntryLine(clearing, EntryDirection.CREDIT, Money.of("145.0000", "USD")),
                        new LedgerEntryLine(feeIncome, EntryDirection.CREDIT, Money.of("5.0000", "USD"))
                ),
                "EXT-SPLIT-001",
                "Principal plus fee split"
        );

        LedgerTransaction tx = LedgerTransaction.post(command);
        repository.saveAndFlush(mapper.toEntity(tx));
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        entityManager.clear();

        LedgerTransaction restored = mapper.toDomain(
                repository.findById(tx.getTransactionId().getValue()).orElseThrow()
        );

        assertThat(restored.getEntries()).hasSize(3);
        assertThat(restored.getEntries().get(0).getLineNumber()).isEqualTo(1);
        assertThat(restored.getEntries().get(1).getLineNumber()).isEqualTo(2);
        assertThat(restored.getEntries().get(2).getLineNumber()).isEqualTo(3);
        assertThat(restored.getEntries()).containsExactlyElementsOf(tx.getEntries());
    }

    @Test
    void shouldPersistAndRestoreMax100EntryStandardTransactionBoundary() {
        List<LedgerEntryLine> lines = new ArrayList<>(100);
        for (int i = 1; i <= 50; i++) {
            lines.add(new LedgerEntryLine(
                    LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, String.format("ACC-DEBIT-%03d", i)),
                    EntryDirection.DEBIT,
                    Money.of("10.0000", "USD")
            ));
        }
        for (int i = 1; i <= 50; i++) {
            lines.add(new LedgerEntryLine(
                    LedgerAccountRef.of(LedgerAccountType.INTERNAL, String.format("ACC-CREDIT-%03d", i)),
                    EntryDirection.CREDIT,
                    Money.of("10.0000", "USD")
            ));
        }

        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-BOUNDARY-100-ENTRIES"),
                USD,
                lines,
                "EXT-MAX-100",
                "100-entry boundary test"
        );

        LedgerTransaction tx = LedgerTransaction.post(command);
        assertThat(tx.getEntries()).hasSize(100);
        assertThat(tx.getTotalAmount()).isEqualTo(Money.of("500.0000", "USD"));

        repository.saveAndFlush(mapper.toEntity(tx));
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        entityManager.clear();

        LedgerTransaction restored = mapper.toDomain(
                repository.findById(tx.getTransactionId().getValue()).orElseThrow()
        );

        assertThat(restored.getEntries()).hasSize(100);
        for (int i = 0; i < 100; i++) {
            assertThat(restored.getEntries().get(i).getLineNumber()).isEqualTo(i + 1);
        }
        assertThat(restored.getEntries()).containsExactlyElementsOf(tx.getEntries());
    }

    @Test
    void shouldPersistValidReversalAndComputeNetZeroBalances() {
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-REV-A");
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-REV-B");

        LedgerTransaction original = LedgerTransaction.post(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-ORIG-001"),
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("100.0000", "USD"))
                ),
                "EXT-ORIG-001",
                "Original payment"
        ));

        LedgerTransaction reversal = original.reverse(new ReverseLedgerTransactionCommand(
                original.getTransactionId(),
                "REV-REF-001",
                "Full settlement reversal"
        ));

        repository.saveAndFlush(mapper.toEntity(original));
        repository.saveAndFlush(mapper.toEntity(reversal));
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        entityManager.clear();

        Optional<LedgerTransactionEntity> loadedReversal = repository.findByReversedTransactionId(
                original.getTransactionId().getValue()
        );
        assertThat(loadedReversal).isPresent();

        LedgerTransaction restoredReversal = mapper.toDomain(loadedReversal.get());
        assertThat(restoredReversal.getTransactionType()).isEqualTo(LedgerTransactionType.REVERSAL);
        assertThat(restoredReversal.getReversedTransactionId()).isEqualTo(original.getTransactionId());
        assertThat(restoredReversal.getPostingKey())
                .isEqualTo(LedgerPostingKey.forReversal(original.getTransactionId()));

        LedgerBalance balanceA = repository.calculateBalance(acctA, USD);
        assertThat(balanceA.debitTotal()).isEqualTo(Money.of("100.0000", "USD"));
        assertThat(balanceA.creditTotal()).isEqualTo(Money.of("100.0000", "USD"));
        assertThat(balanceA.netBalance()).isEqualByComparingTo("0.0000");

        LedgerBalance balanceB = repository.calculateBalance(acctB, USD);
        assertThat(balanceB.debitTotal()).isEqualTo(Money.of("100.0000", "USD"));
        assertThat(balanceB.creditTotal()).isEqualTo(Money.of("100.0000", "USD"));
        assertThat(balanceB.netBalance()).isEqualByComparingTo("0.0000");
    }

    @Test
    void shouldReturnZeroBalanceWhenAccountHasNoEntries() {
        LedgerAccountRef unusedAccount = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-EMPTY-999");

        LedgerBalance balance = repository.calculateBalance(unusedAccount, EUR);

        assertThat(balance.account()).isEqualTo(unusedAccount);
        assertThat(balance.currency()).isEqualTo(EUR);
        assertThat(balance.debitTotal()).isEqualTo(Money.of("0.0000", "EUR"));
        assertThat(balance.creditTotal()).isEqualTo(Money.of("0.0000", "EUR"));
        assertThat(balance.netBalance()).isEqualByComparingTo("0.0000");
    }

    @Test
    void shouldComputeSignedNetBalanceAsDebitMinusCredit() {
        LedgerAccountRef customerAccount = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-NET-CUST");
        LedgerAccountRef clearingAccount = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-NET-CLEARING");

        LedgerTransaction tx1 = LedgerTransaction.post(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-NET-001"),
                USD,
                List.of(
                        new LedgerEntryLine(customerAccount, EntryDirection.DEBIT, Money.of("250.0000", "USD")),
                        new LedgerEntryLine(clearingAccount, EntryDirection.CREDIT, Money.of("250.0000", "USD"))
                ),
                "EXT-NET-001",
                null
        ));

        LedgerTransaction tx2 = LedgerTransaction.post(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PAY-NET-002"),
                USD,
                List.of(
                        new LedgerEntryLine(clearingAccount, EntryDirection.DEBIT, Money.of("90.0000", "USD")),
                        new LedgerEntryLine(customerAccount, EntryDirection.CREDIT, Money.of("90.0000", "USD"))
                ),
                "EXT-NET-002",
                null
        ));

        repository.saveAndFlush(mapper.toEntity(tx1));
        repository.saveAndFlush(mapper.toEntity(tx2));
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");

        LedgerBalance customerBalance = repository.calculateBalance(customerAccount, USD);
        assertThat(customerBalance.debitTotal()).isEqualTo(Money.of("250.0000", "USD"));
        assertThat(customerBalance.creditTotal()).isEqualTo(Money.of("90.0000", "USD"));
        assertThat(customerBalance.netBalance()).isEqualByComparingTo("160.0000");

        LedgerBalance clearingBalance = repository.calculateBalance(clearingAccount, USD);
        assertThat(clearingBalance.debitTotal()).isEqualTo(Money.of("90.0000", "USD"));
        assertThat(clearingBalance.creditTotal()).isEqualTo(Money.of("250.0000", "USD"));
        assertThat(clearingBalance.netBalance()).isEqualByComparingTo("-160.0000");
    }

    // =========================================================================
    // B. Isolated Append-Only Immutability Trigger Tests (1 Per Transaction)
    // =========================================================================

    @Test
    void shouldRejectUpdateOnLedgerTransaction() {
        LedgerTransaction tx = persistValidStandardTransaction("PK-IMMUT-TX-UPD");

        assertSqlFailure(
                () -> jdbcTemplate.update(
                        "UPDATE ledger.ledger_transaction SET description = 'tampered' WHERE transaction_id = ?",
                        tx.getTransactionId().getValue()
                ),
                "P0001",
                "Ledger tables are strictly append-only: UPDATE on ledger.ledger_transaction is prohibited"
        );
    }

    @Test
    void shouldRejectDeleteOnLedgerTransaction() {
        LedgerTransaction tx = persistValidStandardTransaction("PK-IMMUT-TX-DEL");

        assertSqlFailure(
                () -> jdbcTemplate.update(
                        "DELETE FROM ledger.ledger_transaction WHERE transaction_id = ?",
                        tx.getTransactionId().getValue()
                ),
                "P0001",
                "Ledger tables are strictly append-only: DELETE on ledger.ledger_transaction is prohibited"
        );
    }

    @Test
    void shouldRejectTruncateOnLedgerTransaction() {
        persistValidStandardTransaction("PK-IMMUT-TX-TRUNC");

        assertSqlFailure(
                () -> jdbcTemplate.execute("TRUNCATE TABLE ledger.ledger_transaction CASCADE"),
                "P0001",
                "Ledger tables are strictly append-only: TRUNCATE on ledger.ledger_transaction is prohibited"
        );
    }

    @Test
    void shouldRejectUpdateOnLedgerEntry() {
        LedgerTransaction tx = persistValidStandardTransaction("PK-IMMUT-ENT-UPD");
        UUID entryId = tx.getEntries().getFirst().getEntryId();

        assertSqlFailure(
                () -> jdbcTemplate.update(
                        "UPDATE ledger.ledger_entry SET amount = 999.0000 WHERE entry_id = ?",
                        entryId
                ),
                "P0001",
                "Ledger tables are strictly append-only: UPDATE on ledger.ledger_entry is prohibited"
        );
    }

    @Test
    void shouldRejectDeleteOnLedgerEntry() {
        LedgerTransaction tx = persistValidStandardTransaction("PK-IMMUT-ENT-DEL");
        UUID entryId = tx.getEntries().getFirst().getEntryId();

        assertSqlFailure(
                () -> jdbcTemplate.update(
                        "DELETE FROM ledger.ledger_entry WHERE entry_id = ?",
                        entryId
                ),
                "P0001",
                "Ledger tables are strictly append-only: DELETE on ledger.ledger_entry is prohibited"
        );
    }

    @Test
    void shouldRejectTruncateOnLedgerEntry() {
        persistValidStandardTransaction("PK-IMMUT-ENT-TRUNC");

        assertSqlFailure(
                () -> jdbcTemplate.execute("TRUNCATE TABLE ledger.ledger_entry"),
                "P0001",
                "Ledger tables are strictly append-only: TRUNCATE on ledger.ledger_entry is prohibited"
        );
    }

    // =========================================================================
    // C. Column, UNIQUE, Numeric Scale/Rounding, and Unicode/String Constraints
    // =========================================================================

    @Test
    void shouldRejectDuplicatePostingKey() {
        persistValidStandardTransaction("PK-DUPLICATE-001");

        assertSqlFailure(
                () -> insertRawHeader(
                        UUID.randomUUID(),
                        "PK-DUPLICATE-001",
                        "STANDARD",
                        "USD",
                        new BigDecimal("100.0000"),
                        2,
                        "EXT-DUP",
                        null,
                        null,
                        VALID_DUMMY_FINGERPRINT
                ),
                "23505",
                "uk_ledger_transaction_posting_key"
        );
    }

    @Test
    void shouldEnforceNumericScaleAndRangeChecksWithoutSilentRounding() {
        // 1. Reject > 4 meaningful fractional digits on total_amount (no silent rounding)
        assertSqlFailureInSavepoint(
                () -> insertRawHeader(
                        UUID.randomUUID(),
                        "PK-SCALE-TX-5DEC",
                        "STANDARD",
                        "USD",
                        new BigDecimal("100.12345"),
                        2,
                        "EXT-SCALE",
                        null,
                        null,
                        VALID_DUMMY_FINGERPRINT
                ),
                "23514",
                "chk_ledger_tx_total_amount"
        );

        // 2. Reject zero, negative, and > MAX_AMOUNT on total_amount
        for (String invalidAmount : List.of("0.0000", "-1.0000", "1000000000000000.0000")) {
            assertSqlFailureInSavepoint(
                    () -> insertRawHeader(
                            UUID.randomUUID(),
                            "PK-RANGE-" + UUID.randomUUID(),
                            "STANDARD",
                            "USD",
                            new BigDecimal(invalidAmount),
                            2,
                            "EXT-RANGE",
                            null,
                            null,
                            VALID_DUMMY_FINGERPRINT
                    ),
                    "23514",
                    "chk_ledger_tx_total_amount"
            );
        }

        // 3. Reject > 4 meaningful fractional digits on ledger_entry.amount (no silent rounding)
        UUID validTxId = UUID.randomUUID();
        insertRawHeader(
                validTxId,
                "PK-SCALE-ENT-TEST",
                "STANDARD",
                "USD",
                new BigDecimal("100.120000"), // Insignificant trailing zeros allowed by scale(trim_scale(...)) <= 4
                2,
                "EXT-SCALE-OK",
                null,
                null,
                VALID_DUMMY_FINGERPRINT
        );

        assertSqlFailureInSavepoint(
                () -> insertRawEntry(
                        UUID.randomUUID(),
                        validTxId,
                        1,
                        "BANKING_ACCOUNT",
                        "ACC-1",
                        "DEBIT",
                        "USD",
                        new BigDecimal("100.12345")
                ),
                "23514",
                "chk_ledger_entry_amount"
        );

        // 4. Positive proof that insignificant trailing zeros (100.120000) pass DB check and commit cleanly
        insertRawEntry(
                UUID.randomUUID(),
                validTxId,
                1,
                "BANKING_ACCOUNT",
                "ACC-1",
                "DEBIT",
                "USD",
                new BigDecimal("100.120000")
        );
        insertRawEntry(
                UUID.randomUUID(),
                validTxId,
                2,
                "INTERNAL",
                "ACC-2",
                "CREDIT",
                "USD",
                new BigDecimal("100.12")
        );
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    @Test
    void shouldRejectInvalidHeaderCheckConstraints() {
        // STANDARD with REVERSAL: prefix (case-insensitive)
        assertSqlFailureInSavepoint(
                () -> insertRawHeader(
                        UUID.randomUUID(),
                        "reversal:" + UUID.randomUUID(),
                        "STANDARD",
                        "USD",
                        new BigDecimal("50.0000"),
                        2,
                        "EXT-1",
                        null,
                        null,
                        VALID_DUMMY_FINGERPRINT
                ),
                "23514",
                "chk_ledger_tx_reversal_consistency"
        );

        // REVERSAL with null reversed_transaction_id
        assertSqlFailureInSavepoint(
                () -> insertRawHeader(
                        UUID.randomUUID(),
                        "REVERSAL:" + UUID.randomUUID(),
                        "REVERSAL",
                        "USD",
                        new BigDecimal("50.0000"),
                        2,
                        "EXT-1",
                        null,
                        null,
                        VALID_DUMMY_FINGERPRINT
                ),
                "23514",
                "chk_ledger_tx_reversal_consistency"
        );

        // REVERSAL with mismatched posting_key
        LedgerTransaction orig = persistValidStandardTransaction("PK-HDR-ORIG");
        assertSqlFailureInSavepoint(
                () -> insertRawHeader(
                        UUID.randomUUID(),
                        "REVERSAL:" + UUID.randomUUID(),
                        "REVERSAL",
                        "USD",
                        new BigDecimal("100.0000"),
                        2,
                        "EXT-1",
                        null,
                        orig.getTransactionId().getValue(),
                        VALID_DUMMY_FINGERPRINT
                ),
                "23514",
                "chk_ledger_tx_reversal_consistency"
        );

        // entry_count below 2 and above 100
        for (int badCount : List.of(1, 101)) {
            assertSqlFailureInSavepoint(
                    () -> insertRawHeader(
                            UUID.randomUUID(),
                            "PK-CNT-" + badCount,
                            "STANDARD",
                            "USD",
                            new BigDecimal("50.0000"),
                            badCount,
                            "EXT-1",
                            null,
                            null,
                            VALID_DUMMY_FINGERPRINT
                    ),
                    "23514",
                    "chk_ledger_tx_entry_count"
            );
        }

        // Unsupported currency
        assertSqlFailureInSavepoint(
                () -> insertRawHeader(
                        UUID.randomUUID(),
                        "PK-BAD-CURR",
                        "STANDARD",
                        "CAD",
                        new BigDecimal("50.0000"),
                        2,
                        "EXT-1",
                        null,
                        null,
                        VALID_DUMMY_FINGERPRINT
                ),
                "23514",
                "chk_ledger_tx_currency"
        );

        // Invalid fingerprint format
        assertSqlFailureInSavepoint(
                () -> insertRawHeader(
                        UUID.randomUUID(),
                        "PK-BAD-FP",
                        "STANDARD",
                        "USD",
                        new BigDecimal("50.0000"),
                        2,
                        "EXT-1",
                        null,
                        null,
                        "UPPERCASE_OR_SHORT_FINGERPRINT"
                ),
                "23514",
                "chk_ledger_tx_fingerprint"
        );
    }

    @Test
    void shouldEnforceUnicodeWhitespaceAndStorableControlCharacterChecksOnAccountReference() {
        UUID txId = UUID.randomUUID();
        insertRawHeader(
                txId,
                "PK-ACCREF-UNICODE",
                "STANDARD",
                "USD",
                new BigDecimal("100.0000"),
                2,
                "EXT-UNICODE",
                null,
                null,
                VALID_DUMMY_FINGERPRINT
        );

        // Representative boundary whitespace/space characters rejected by LedgerAccountRef
        List<String> boundarySpaces = List.of(
                " ",        // U+0020 SPACE
                "\t",       // U+0009 TAB
                "\n",       // U+000A LF
                "\u00A0",   // U+00A0 NO-BREAK SPACE (NBSP)
                "\u1680",   // U+1680 OGHAM SPACE MARK
                "\u2000",   // U+2000 EN QUAD
                "\u2007",   // U+2007 FIGURE SPACE
                "\u2028",   // U+2028 LINE SEPARATOR
                "\u2029",   // U+2029 PARAGRAPH SEPARATOR
                "\u202F",   // U+202F NARROW NO-BREAK SPACE
                "\u205F",   // U+205F MEDIUM MATHEMATICAL SPACE
                "\u3000"    // U+3000 IDEOGRAPHIC SPACE
        );

        for (String space : boundarySpaces) {
            assertSqlFailureInSavepoint(
                    () -> insertRawEntry(
                            UUID.randomUUID(),
                            txId,
                            1,
                            "BANKING_ACCOUNT",
                            space + "ACC-001",
                            "DEBIT",
                            "USD",
                            new BigDecimal("100.0000")
                    ),
                    "23514",
                    "chk_ledger_entry_account_ref"
            );
            assertSqlFailureInSavepoint(
                    () -> insertRawEntry(
                            UUID.randomUUID(),
                            txId,
                            1,
                            "BANKING_ACCOUNT",
                            "ACC-001" + space,
                            "DEBIT",
                            "USD",
                            new BigDecimal("100.0000")
                    ),
                    "23514",
                    "chk_ledger_entry_account_ref"
            );
        }

        // Storable interior ISO control characters (U+0001..U+001F, U+007F..U+009F)
        List<String> controlChars = List.of(
                "\u0001",   // SOH (C0)
                "\u0007",   // BEL (C0)
                "\u001F",   // US  (C0)
                "\u007F",   // DEL
                "\u0085",   // NEL (C1)
                "\u009F"    // APC (C1)
        );

        for (String ctrl : controlChars) {
            assertSqlFailureInSavepoint(
                    () -> insertRawEntry(
                            UUID.randomUUID(),
                            txId,
                            1,
                            "BANKING_ACCOUNT",
                            "ACC-" + ctrl + "-001",
                            "DEBIT",
                            "USD",
                            new BigDecimal("100.0000")
                    ),
                    "23514",
                    "chk_ledger_entry_account_ref"
            );
        }

        // Empty string rejected by chk_ledger_entry_account_ref (SQLSTATE 23514)
        assertSqlFailureInSavepoint(
                () -> insertRawEntry(
                        UUID.randomUUID(),
                        txId,
                        1,
                        "BANKING_ACCOUNT",
                        "",
                        "DEBIT",
                        "USD",
                        new BigDecimal("100.0000")
                ),
                "23514",
                "chk_ledger_entry_account_ref"
        );

        // 101-code-point overflow rejected by VARCHAR(100) type modifier (SQLSTATE 22001)
        assertSqlFailureInSavepoint(
                () -> insertRawEntry(
                        UUID.randomUUID(),
                        txId,
                        1,
                        "BANKING_ACCOUNT",
                        "A".repeat(101),
                        "DEBIT",
                        "USD",
                        new BigDecimal("100.0000")
                ),
                "22001",
                "character varying(100)"
        );

        // Valid 100-code-point non-ASCII Unicode account_reference succeeds
        String validUnicode100 = "MÜNCHEN-口座-" + "A".repeat(89);
        assertThat(validUnicode100.codePointCount(0, validUnicode100.length())).isEqualTo(100);

        insertRawEntry(
                UUID.randomUUID(),
                txId,
                1,
                "BANKING_ACCOUNT",
                validUnicode100,
                "DEBIT",
                "USD",
                new BigDecimal("100.0000")
        );
        insertRawEntry(
                UUID.randomUUID(),
                txId,
                2,
                "INTERNAL",
                "CLEARING-OK",
                "CREDIT",
                "USD",
                new BigDecimal("100.0000")
        );
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    @Test
    void shouldRejectNulCharacterAtSqlDriverEncodingBoundary() {
        UUID txId = UUID.randomUUID();
        insertRawHeader(
                txId,
                "PK-NUL-BOUNDARY",
                "STANDARD",
                "USD",
                new BigDecimal("100.0000"),
                2,
                "EXT-NUL",
                null,
                null,
                VALID_DUMMY_FINGERPRINT
        );

        // PostgreSQL rejects U+0000 at the UTF-8 encoding / wire-protocol boundary (SQLSTATE 22021)
        // before table CHECK constraints can evaluate the value.
        assertSqlFailureInSavepoint(
                () -> insertRawEntry(
                        UUID.randomUUID(),
                        txId,
                        1,
                        "BANKING_ACCOUNT",
                        "ACC-\u0000-NUL",
                        "DEBIT",
                        "USD",
                        new BigDecimal("100.0000")
                ),
                "22021",
                "0x00"
        );
    }

    @Test
    void shouldEnforceUtf16LengthAndTrimRulesOnExternalReferenceAndDescription() {
        // 1. Reject empty, blank, and untrimmed external_reference
        for (String invalidExtRef : List.of("", "   ", " EXT-REF", "EXT-REF ", "\tEXT-REF", "EXT-REF\n")) {
            assertSqlFailureInSavepoint(
                    () -> insertRawHeader(
                            UUID.randomUUID(),
                            "PK-EXTREF-" + UUID.randomUUID(),
                            "STANDARD",
                            "USD",
                            new BigDecimal("100.0000"),
                            2,
                            invalidExtRef,
                            null,
                            null,
                            VALID_DUMMY_FINGERPRINT
                    ),
                    "23514",
                    "chk_ledger_tx_external_ref"
            );
        }

        // 2. Reject empty, blank, and untrimmed description
        for (String invalidDesc : List.of("", "   ", " desc", "desc ", "\tdesc", "desc\n")) {
            assertSqlFailureInSavepoint(
                    () -> insertRawHeader(
                            UUID.randomUUID(),
                            "PK-DESC-" + UUID.randomUUID(),
                            "STANDARD",
                            "USD",
                            new BigDecimal("100.0000"),
                            2,
                            "EXT-VALID",
                            invalidDesc,
                            null,
                            VALID_DUMMY_FINGERPRINT
                    ),
                    "23514",
                    "chk_ledger_tx_description"
            );
        }

        // 3. Reject external_reference with 255 Unicode code points but 256 UTF-16 code units
        String extRef256Utf16Units = "A".repeat(254) + "\uD83D\uDE00"; // 😀 (U+1F600)
        assertThat(extRef256Utf16Units.codePointCount(0, extRef256Utf16Units.length())).isEqualTo(255);
        assertThat(extRef256Utf16Units.length()).isEqualTo(256);

        assertSqlFailureInSavepoint(
                () -> insertRawHeader(
                        UUID.randomUUID(),
                        "PK-EXTREF-256-UTF16",
                        "STANDARD",
                        "USD",
                        new BigDecimal("100.0000"),
                        2,
                        extRef256Utf16Units,
                        null,
                        null,
                        VALID_DUMMY_FINGERPRINT
                ),
                "23514",
                "chk_ledger_tx_external_ref"
        );

        // 4. Reject description with 500 Unicode code points but 501 UTF-16 code units
        String desc501Utf16Units = "A".repeat(499) + "\uD83D\uDE00"; // 😀 (U+1F600)
        assertThat(desc501Utf16Units.codePointCount(0, desc501Utf16Units.length())).isEqualTo(500);
        assertThat(desc501Utf16Units.length()).isEqualTo(501);

        assertSqlFailureInSavepoint(
                () -> insertRawHeader(
                        UUID.randomUUID(),
                        "PK-DESC-501-UTF16",
                        "STANDARD",
                        "USD",
                        new BigDecimal("100.0000"),
                        2,
                        "EXT-VALID",
                        desc501Utf16Units,
                        null,
                        VALID_DUMMY_FINGERPRINT
                ),
                "23514",
                "chk_ledger_tx_description"
        );

        // 5. Accept exact UTF-16 boundary: 255 UTF-16 units for external_reference and 500 UTF-16 units for description
        String validExtRef255Utf16 = "A".repeat(253) + "\uD83D\uDE00";
        String validDesc500Utf16 = "A".repeat(498) + "\uD83D\uDE00";
        assertThat(validExtRef255Utf16.length()).isEqualTo(255);
        assertThat(validDesc500Utf16.length()).isEqualTo(500);

        LedgerTransaction boundaryTx = LedgerTransaction.post(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PK-UTF16-BOUNDARY-OK"),
                USD,
                List.of(
                        new LedgerEntryLine(
                                LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-UTF16-1"),
                                EntryDirection.DEBIT,
                                Money.of("100.0000", "USD")
                        ),
                        new LedgerEntryLine(
                                LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-UTF16-2"),
                                EntryDirection.CREDIT,
                                Money.of("100.0000", "USD")
                        )
                ),
                validExtRef255Utf16,
                validDesc500Utf16
        ));

        repository.saveAndFlush(mapper.toEntity(boundaryTx));
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        entityManager.clear();

        LedgerTransaction restored = mapper.toDomain(
                repository.findById(boundaryTx.getTransactionId().getValue()).orElseThrow()
        );
        assertThat(restored.getExternalReference()).isEqualTo(validExtRef255Utf16);
        assertThat(restored.getDescription()).isEqualTo(validDesc500Utf16);
    }

    // =========================================================================
    // D. Deferred Constraint Trigger Tests — Standard Transaction Integrity
    // =========================================================================

    @Test
    void shouldRejectTransactionWithZeroEntriesAtCommit() {
        insertRawHeader(
                UUID.randomUUID(),
                "PK-ZERO-ENTRIES",
                "STANDARD",
                "USD",
                new BigDecimal("100.0000"),
                2,
                "EXT-ZERO",
                null,
                null,
                VALID_DUMMY_FINGERPRINT
        );

        assertSqlFailure(
                () -> jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE"),
                "P0001",
                "entry count mismatch (actual 0, expected 2)"
        );
    }

    @Test
    void shouldRejectEntryCountMismatchOrNonContiguousLineNumbersAtCommit() {
        UUID txId = UUID.randomUUID();
        insertRawHeader(
                txId,
                "PK-GAP-LINES",
                "STANDARD",
                "USD",
                new BigDecimal("100.0000"),
                2,
                "EXT-GAP",
                null,
                null,
                VALID_DUMMY_FINGERPRINT
        );
        insertRawEntry(
                UUID.randomUUID(),
                txId,
                1,
                "BANKING_ACCOUNT",
                "ACC-1",
                "DEBIT",
                "USD",
                new BigDecimal("100.0000")
        );
        // Line 3 instead of 2 creates a non-contiguous sequence 1, 3
        insertRawEntry(
                UUID.randomUUID(),
                txId,
                3,
                "INTERNAL",
                "ACC-2",
                "CREDIT",
                "USD",
                new BigDecimal("100.0000")
        );

        assertSqlFailure(
                () -> jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE"),
                "P0001",
                "do not have contiguous line numbers 1..2 (min 1, max 3)"
        );
    }

    @Test
    void shouldRejectMixedCurrencyEntriesAtCommit() {
        UUID txId = UUID.randomUUID();
        insertRawHeader(
                txId,
                "PK-MIXED-CURR",
                "STANDARD",
                "USD",
                new BigDecimal("100.0000"),
                2,
                "EXT-MIXED",
                null,
                null,
                VALID_DUMMY_FINGERPRINT
        );
        insertRawEntry(
                UUID.randomUUID(),
                txId,
                1,
                "BANKING_ACCOUNT",
                "ACC-1",
                "DEBIT",
                "USD",
                new BigDecimal("100.0000")
        );
        insertRawEntry(
                UUID.randomUUID(),
                txId,
                2,
                "INTERNAL",
                "ACC-2",
                "CREDIT",
                "EUR",
                new BigDecimal("100.0000")
        );

        assertSqlFailure(
                () -> jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE"),
                "P0001",
                "not matching transaction currency USD"
        );
    }

    @Test
    void shouldRejectUnbalancedEntriesOrHeaderTotalMismatchAtCommit() {
        // Case 1: debit 100 != credit 90
        assertSqlFailureInSavepoint(
                () -> {
                    UUID txId = UUID.randomUUID();
                    insertRawHeader(
                            txId,
                            "PK-UNBALANCED-1",
                            "STANDARD",
                            "USD",
                            new BigDecimal("100.0000"),
                            2,
                            "EXT-UNBAL",
                            null,
                            null,
                            VALID_DUMMY_FINGERPRINT
                    );
                    insertRawEntry(
                            UUID.randomUUID(),
                            txId,
                            1,
                            "BANKING_ACCOUNT",
                            "ACC-1",
                            "DEBIT",
                            "USD",
                            new BigDecimal("100.0000")
                    );
                    insertRawEntry(
                            UUID.randomUUID(),
                            txId,
                            2,
                            "INTERNAL",
                            "ACC-2",
                            "CREDIT",
                            "USD",
                            new BigDecimal("90.0000")
                    );
                    jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
                },
                "P0001",
                "is unbalanced or does not match header total_amount"
        );

        // Case 2: debit 80 == credit 80, but header total_amount == 100
        assertSqlFailureInSavepoint(
                () -> {
                    UUID txId = UUID.randomUUID();
                    insertRawHeader(
                            txId,
                            "PK-UNBALANCED-2",
                            "STANDARD",
                            "USD",
                            new BigDecimal("100.0000"),
                            2,
                            "EXT-UNBAL-2",
                            null,
                            null,
                            VALID_DUMMY_FINGERPRINT
                    );
                    insertRawEntry(
                            UUID.randomUUID(),
                            txId,
                            1,
                            "BANKING_ACCOUNT",
                            "ACC-1",
                            "DEBIT",
                            "USD",
                            new BigDecimal("80.0000")
                    );
                    insertRawEntry(
                            UUID.randomUUID(),
                            txId,
                            2,
                            "INTERNAL",
                            "ACC-2",
                            "CREDIT",
                            "USD",
                            new BigDecimal("80.0000")
                    );
                    jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
                },
                "P0001",
                "is unbalanced or does not match header total_amount"
        );
    }

    // =========================================================================
    // E. Expanded Deferred Constraint Trigger Tests — Reversal Integrity
    // =========================================================================

    @Test
    void shouldRejectSecondReversalOfSameTransaction() {
        LedgerTransaction orig = persistValidStandardTransaction("PK-ORIG-DUP-REV");
        LedgerTransaction firstReversal = orig.reverse(new ReverseLedgerTransactionCommand(
                orig.getTransactionId(),
                "REV-REF-1",
                "First reversal"
        ));
        repository.saveAndFlush(mapper.toEntity(firstReversal));
        verifyDeferredConstraints();

        UUID origId = orig.getTransactionId().getValue();
        assertSqlFailure(
                () -> insertRawHeader(
                        UUID.randomUUID(),
                        "REVERSAL:" + origId,
                        "REVERSAL",
                        "USD",
                        new BigDecimal("100.0000"),
                        2,
                        "REV-REF-2",
                        null,
                        origId,
                        VALID_DUMMY_FINGERPRINT
                ),
                "23505",
                "uk_ledger_transaction_posting_key"
        );
    }

    @Test
    void shouldRejectReversalOfReversalAtCommit() {
        LedgerTransaction orig = persistValidStandardTransaction("PK-ORIG-CHAIN");
        LedgerTransaction rev1 = orig.reverse(new ReverseLedgerTransactionCommand(
                orig.getTransactionId(),
                "REV-1",
                "First reversal"
        ));
        repository.saveAndFlush(mapper.toEntity(rev1));
        verifyDeferredConstraints();

        UUID rev1Id = rev1.getTransactionId().getValue();
        UUID rev2Id = UUID.randomUUID();
        insertRawHeader(
                rev2Id,
                "REVERSAL:" + rev1Id,
                "REVERSAL",
                "USD",
                new BigDecimal("100.0000"),
                2,
                "REV-OF-REV",
                null,
                rev1Id,
                VALID_DUMMY_FINGERPRINT
        );
        insertRawEntry(
                UUID.randomUUID(),
                rev2Id,
                1,
                "BANKING_ACCOUNT",
                "ACC-A",
                "DEBIT",
                "USD",
                new BigDecimal("100.0000")
        );
        insertRawEntry(
                UUID.randomUUID(),
                rev2Id,
                2,
                "INTERNAL",
                "ACC-B",
                "CREDIT",
                "USD",
                new BigDecimal("100.0000")
        );

        assertSqlFailure(
                () -> jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE"),
                "P0001",
                "is not a STANDARD transaction (type REVERSAL)"
        );
    }

    @Test
    void shouldRejectReversalMissingOriginalEntryAtCommit() {
        LedgerTransaction orig3Lines = LedgerTransaction.post(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PK-ORIG-3LINES"),
                USD,
                List.of(
                        new LedgerEntryLine(
                                LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-A"),
                                EntryDirection.DEBIT,
                                Money.of("100.0000", "USD")
                        ),
                        new LedgerEntryLine(
                                LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-B"),
                                EntryDirection.CREDIT,
                                Money.of("60.0000", "USD")
                        ),
                        new LedgerEntryLine(
                                LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-C"),
                                EntryDirection.CREDIT,
                                Money.of("40.0000", "USD")
                        )
                ),
                "EXT-3LINES",
                null
        ));
        repository.saveAndFlush(mapper.toEntity(orig3Lines));
        verifyDeferredConstraints();

        UUID origId = orig3Lines.getTransactionId().getValue();
        UUID revId = UUID.randomUUID();
        // Reversal has only 2 entries (missing ACC-C) while balanced at 100.0000
        insertRawHeader(
                revId,
                "REVERSAL:" + origId,
                "REVERSAL",
                "USD",
                new BigDecimal("100.0000"),
                2,
                "REV-MISSING-LINE",
                null,
                origId,
                VALID_DUMMY_FINGERPRINT
        );
        insertRawEntry(
                UUID.randomUUID(),
                revId,
                1,
                "BANKING_ACCOUNT",
                "ACC-A",
                "CREDIT",
                "USD",
                new BigDecimal("100.0000")
        );
        insertRawEntry(
                UUID.randomUUID(),
                revId,
                2,
                "INTERNAL",
                "ACC-B",
                "DEBIT",
                "USD",
                new BigDecimal("100.0000")
        );

        assertSqlFailure(
                () -> jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE"),
                "P0001",
                "does not match original transaction"
        );
    }

    @Test
    void shouldRejectReversalContainingExtraEntryAtCommit() {
        LedgerTransaction orig2Lines = persistValidStandardTransaction("PK-ORIG-2LINES-EXTRA");
        UUID origId = orig2Lines.getTransactionId().getValue();
        UUID revId = UUID.randomUUID();

        // Reversal has 3 entries (an extra entry on ACC-EXTRA) while balanced at 100.0000
        insertRawHeader(
                revId,
                "REVERSAL:" + origId,
                "REVERSAL",
                "USD",
                new BigDecimal("100.0000"),
                3,
                "REV-EXTRA-LINE",
                null,
                origId,
                VALID_DUMMY_FINGERPRINT
        );
        insertRawEntry(
                UUID.randomUUID(),
                revId,
                1,
                "BANKING_ACCOUNT",
                "ACC-A",
                "CREDIT",
                "USD",
                new BigDecimal("100.0000")
        );
        insertRawEntry(
                UUID.randomUUID(),
                revId,
                2,
                "INTERNAL",
                "ACC-B",
                "DEBIT",
                "USD",
                new BigDecimal("60.0000")
        );
        insertRawEntry(
                UUID.randomUUID(),
                revId,
                3,
                "INTERNAL",
                "ACC-EXTRA",
                "DEBIT",
                "USD",
                new BigDecimal("40.0000")
        );

        assertSqlFailure(
                () -> jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE"),
                "P0001",
                "does not match original transaction"
        );
    }

    @Test
    void shouldRejectReversalWithTamperedCounterpartyOrUnflippedDirectionAtCommit() {
        LedgerTransaction orig = persistValidStandardTransaction("PK-ORIG-TAMPER");
        UUID origId = orig.getTransactionId().getValue();

        // Case 1: Same entry_count (2), currency (USD), total_amount (100.0000), balanced,
        // but ACC-B is replaced by ACC-TAMPERED
        assertSqlFailureInSavepoint(
                () -> {
                    UUID revId = UUID.randomUUID();
                    insertRawHeader(
                            revId,
                            "REVERSAL:" + origId,
                            "REVERSAL",
                            "USD",
                            new BigDecimal("100.0000"),
                            2,
                            "REV-TAMPER-ACCT",
                            null,
                            origId,
                            VALID_DUMMY_FINGERPRINT
                    );
                    insertRawEntry(
                            UUID.randomUUID(),
                            revId,
                            1,
                            "BANKING_ACCOUNT",
                            "ACC-A",
                            "CREDIT",
                            "USD",
                            new BigDecimal("100.0000")
                    );
                    insertRawEntry(
                            UUID.randomUUID(),
                            revId,
                            2,
                            "INTERNAL",
                            "ACC-TAMPERED",
                            "DEBIT",
                            "USD",
                            new BigDecimal("100.0000")
                    );
                    jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
                },
                "P0001",
                "entries do not form a 1-to-1 inverted bijection"
        );

        // Case 2: Directions not flipped (same directions as original)
        assertSqlFailureInSavepoint(
                () -> {
                    UUID revId = UUID.randomUUID();
                    insertRawHeader(
                            revId,
                            "REVERSAL:" + origId,
                            "REVERSAL",
                            "USD",
                            new BigDecimal("100.0000"),
                            2,
                            "REV-UNFLIPPED",
                            null,
                            origId,
                            VALID_DUMMY_FINGERPRINT
                    );
                    insertRawEntry(
                            UUID.randomUUID(),
                            revId,
                            1,
                            "BANKING_ACCOUNT",
                            "ACC-A",
                            "DEBIT",
                            "USD",
                            new BigDecimal("100.0000")
                    );
                    insertRawEntry(
                            UUID.randomUUID(),
                            revId,
                            2,
                            "INTERNAL",
                            "ACC-B",
                            "CREDIT",
                            "USD",
                            new BigDecimal("100.0000")
                    );
                    jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
                },
                "P0001",
                "entries do not form a 1-to-1 inverted bijection"
        );
    }

    @Test
    void shouldRejectReversalWhenDuplicateTupleMultiplicitiesDifferAtCommit() {
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-MULT-A");
        LedgerAccountRef acctC = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-MULT-C");
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-MULT-B");

        // Original has 4 entries totaling 150.0000 USD:
        // - (ACC-MULT-A, DEBIT, 50.0000) x 2 (multiplicity 2)
        // - (ACC-MULT-C, DEBIT, 50.0000) x 1 (multiplicity 1)
        // - (ACC-MULT-B, CREDIT, 150.0000) x 1 (multiplicity 1)
        LedgerTransaction orig = LedgerTransaction.post(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PK-MULT-ORIG-DIFF"),
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("50.0000", "USD")),
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("50.0000", "USD")),
                        new LedgerEntryLine(acctC, EntryDirection.DEBIT, Money.of("50.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("150.0000", "USD"))
                ),
                "EXT-MULT-DIFF",
                null
        ));
        repository.saveAndFlush(mapper.toEntity(orig));
        verifyDeferredConstraints();

        UUID origId = orig.getTransactionId().getValue();
        UUID revId = UUID.randomUUID();

        // Tampered reversal has same entry_count (4), same total_amount (150.0000), balanced,
        // and the exact same distinct tuples, but swapped multiplicities:
        // - (ACC-MULT-A, CREDIT, 50.0000) x 1 (expected 2)
        // - (ACC-MULT-C, CREDIT, 50.0000) x 2 (expected 1)
        // - (ACC-MULT-B, DEBIT, 150.0000) x 1
        insertRawHeader(
                revId,
                "REVERSAL:" + origId,
                "REVERSAL",
                "USD",
                new BigDecimal("150.0000"),
                4,
                "REV-MULT-DIFF",
                null,
                origId,
                VALID_DUMMY_FINGERPRINT
        );
        insertRawEntry(UUID.randomUUID(), revId, 1, "BANKING_ACCOUNT", "ACC-MULT-A", "CREDIT", "USD", new BigDecimal("50.0000"));
        insertRawEntry(UUID.randomUUID(), revId, 2, "BANKING_ACCOUNT", "ACC-MULT-C", "CREDIT", "USD", new BigDecimal("50.0000"));
        insertRawEntry(UUID.randomUUID(), revId, 3, "BANKING_ACCOUNT", "ACC-MULT-C", "CREDIT", "USD", new BigDecimal("50.0000"));
        insertRawEntry(UUID.randomUUID(), revId, 4, "INTERNAL", "ACC-MULT-B", "DEBIT", "USD", new BigDecimal("150.0000"));

        assertSqlFailure(
                () -> jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE"),
                "P0001",
                "entries do not form a 1-to-1 inverted bijection"
        );
    }

    @Test
    void shouldAcceptValidReversalWithDuplicateOriginalTuplesHavingMatchingMultiplicities() {
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-MULT-OK-A");
        LedgerAccountRef acctC = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-MULT-OK-C");
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-MULT-OK-B");

        LedgerTransaction orig = LedgerTransaction.post(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PK-MULT-ORIG-OK"),
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("50.0000", "USD")),
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("50.0000", "USD")),
                        new LedgerEntryLine(acctC, EntryDirection.DEBIT, Money.of("50.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("150.0000", "USD"))
                ),
                "EXT-MULT-OK",
                "Duplicate tuple transaction"
        ));

        LedgerTransaction reversal = orig.reverse(new ReverseLedgerTransactionCommand(
                orig.getTransactionId(),
                "REV-MULT-OK",
                "Reversal with matching duplicate multiplicities"
        ));

        repository.saveAndFlush(mapper.toEntity(orig));
        repository.saveAndFlush(mapper.toEntity(reversal));
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");

        assertThat(repository.calculateBalance(acctA, USD).netBalance()).isEqualByComparingTo("0.0000");
        assertThat(repository.calculateBalance(acctC, USD).netBalance()).isEqualByComparingTo("0.0000");
        assertThat(repository.calculateBalance(acctB, USD).netBalance()).isEqualByComparingTo("0.0000");
    }

    @Test
    void shouldRejectTamperedPersistedReversalKeyOrFingerprintOnMapperRestore() {
        LedgerTransaction orig = persistValidStandardTransaction("PK-MAPPER-GUARD");
        LedgerTransaction rev = orig.reverse(new ReverseLedgerTransactionCommand(
                orig.getTransactionId(),
                "REV-MAPPER-GUARD",
                null
        ));

        LedgerTransactionEntity tamperedKeyEntity = new LedgerTransactionEntity(
                rev.getTransactionId().getValue(),
                "PK-NOT-REVERSAL-PREFIX",
                LedgerTransactionType.REVERSAL,
                "USD",
                new BigDecimal("100.0000"),
                2,
                "REV-MAPPER-GUARD",
                null,
                orig.getTransactionId().getValue(),
                rev.getRequestFingerprint().getValue(),
                rev.getPostedAt()
        );
        for (LedgerEntry entry : rev.getEntries()) {
            tamperedKeyEntity.addEntry(mapper.toEntity(rev).getEntries().get(entry.getLineNumber() - 1));
        }

        assertThatThrownBy(() -> mapper.toDomain(tamperedKeyEntity))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("does not match expected key");

        LedgerTransactionEntity tamperedFpEntity = new LedgerTransactionEntity(
                orig.getTransactionId().getValue(),
                orig.getPostingKey().getValue(),
                LedgerTransactionType.STANDARD,
                "USD",
                new BigDecimal("100.0000"),
                2,
                orig.getExternalReference(),
                orig.getDescription(),
                null,
                PostingFingerprint.ofHex("b".repeat(64)).getValue(),
                orig.getPostedAt()
        );
        for (LedgerEntry entry : orig.getEntries()) {
            tamperedFpEntity.addEntry(mapper.toEntity(orig).getEntries().get(entry.getLineNumber() - 1));
        }

        assertThatThrownBy(() -> mapper.toDomain(tamperedFpEntity))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("requestFingerprint");
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private LedgerTransaction persistValidStandardTransaction(String postingKey) {
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-A");
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-B");

        LedgerTransaction tx = LedgerTransaction.post(new PostLedgerTransactionCommand(
                LedgerPostingKey.of(postingKey),
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("100.0000", "USD"))
                ),
                "EXT-" + postingKey,
                "Valid transaction for " + postingKey
        ));

        repository.saveAndFlush(mapper.toEntity(tx));
        verifyDeferredConstraints();
        return tx;
    }

    private void verifyDeferredConstraints() {
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        jdbcTemplate.execute("SET CONSTRAINTS ALL DEFERRED");
    }

    private void insertRawHeader(
            UUID transactionId,
            String postingKey,
            String transactionType,
            String currency,
            BigDecimal totalAmount,
            int entryCount,
            String externalReference,
            String description,
            UUID reversedTransactionId,
            String requestFingerprint) {
        jdbcTemplate.update(
                """
                INSERT INTO ledger.ledger_transaction (
                    transaction_id, posting_key, transaction_type, currency,
                    total_amount, entry_count, external_reference, description,
                    reversed_transaction_id, request_fingerprint, posted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                transactionId,
                postingKey,
                transactionType,
                currency,
                totalAmount,
                entryCount,
                externalReference,
                description,
                reversedTransactionId,
                requestFingerprint,
                Timestamp.from(Instant.now())
        );
    }

    private void insertRawEntry(
            UUID entryId,
            UUID transactionId,
            int lineNumber,
            String accountType,
            String accountReference,
            String direction,
            String currency,
            BigDecimal amount) {
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    """
                    INSERT INTO ledger.ledger_entry (
                        entry_id, transaction_id, line_number, account_type,
                        account_reference, direction, currency, amount
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """
            );
            ps.setObject(1, entryId);
            ps.setObject(2, transactionId);
            ps.setInt(3, lineNumber);
            ps.setString(4, accountType);
            ps.setString(5, accountReference);
            ps.setString(6, direction);
            ps.setString(7, currency);
            ps.setBigDecimal(8, amount);
            return ps;
        });
    }

    private void assertSqlFailure(Runnable action, String expectedSqlState, String expectedMessageFragment) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> {
                    SQLException sqlEx = extractSqlException(ex);
                    assertThat((Throwable) sqlEx).as("Root SQLException").isNotNull();
                    assertThat(sqlEx.getSQLState())
                            .as("SQLSTATE for error: %s", sqlEx.getMessage())
                            .isEqualTo(expectedSqlState);
                    assertThat(sqlEx.getMessage()).contains(expectedMessageFragment);
                });
    }

    private void assertSqlFailureInSavepoint(Runnable action, String expectedSqlState, String expectedMessageFragment) {
        jdbcTemplate.execute((ConnectionCallback<Void>) conn -> {
            Savepoint sp = conn.setSavepoint();
            try {
                assertSqlFailure(action, expectedSqlState, expectedMessageFragment);
            } finally {
                conn.rollback(sp);
                conn.releaseSavepoint(sp);
            }
            return null;
        });
    }

    private static SQLException extractSqlException(Throwable throwable) {
        Throwable current = throwable;
        SQLException lastSqlEx = null;
        while (current != null) {
            if (current instanceof SQLException sqlEx) {
                lastSqlEx = sqlEx;
            }
            current = current.getCause();
        }
        return lastSqlEx;
    }
}
