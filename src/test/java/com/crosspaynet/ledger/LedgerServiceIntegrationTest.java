package com.crosspaynet.ledger;

import com.crosspaynet.CrossPayNetApplication;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class LedgerServiceIntegrationTest {

    private static final Currency USD = new Currency("USD");
    private static final Currency EUR = new Currency("EUR");

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================================
    // 1. Standard Posting, Public Read Models, Multi-Line Order, and Balances
    // =========================================================================

    @Test
    void shouldPostStandardTransactionAndLookupViewAndBalances() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef sender = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-SVC-SENDER-" + suffix);
        LedgerAccountRef clearing = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "NOSTRO-SVC-USD-" + suffix);

        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PK-SVC-STD-" + suffix),
                USD,
                List.of(
                        new LedgerEntryLine(sender, EntryDirection.DEBIT, Money.of("250.5000", "USD")),
                        new LedgerEntryLine(clearing, EntryDirection.CREDIT, Money.of("250.5000", "USD"))
                ),
                "EXT-SVC-STD-" + suffix,
                "Standard cross-border settlement"
        );

        LedgerPostingResult result = ledgerService.postTransaction(command);

        assertThat(result.transactionId()).isNotNull();
        assertThat(result.outcome()).isEqualTo(PostingOutcome.POSTED);
        assertThat(result.postedAt()).isNotNull();

        Optional<LedgerTransactionView> viewOpt = ledgerService.findTransaction(result.transactionId());
        assertThat(viewOpt).isPresent();

        LedgerTransactionView view = viewOpt.get();
        assertThat(view.transactionId()).isEqualTo(result.transactionId());
        assertThat(view.transactionType()).isEqualTo(LedgerTransactionType.STANDARD);
        assertThat(view.postingKey()).isEqualTo(command.postingKey());
        assertThat(view.currency()).isEqualTo(USD);
        assertThat(view.totalAmount()).isEqualTo(Money.of("250.5000", "USD"));
        assertThat(view.externalReference()).isEqualTo("EXT-SVC-STD-" + suffix);
        assertThat(view.description()).isEqualTo("Standard cross-border settlement");
        assertThat(view.reversedTransactionId()).isNull();
        assertThat(view.postedAt().truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(result.postedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(view.entries()).hasSize(2);

        LedgerEntryView line1 = view.entries().get(0);
        assertThat(line1.entryId()).isNotNull();
        assertThat(line1.lineNumber()).isEqualTo(1);
        assertThat(line1.account()).isEqualTo(sender);
        assertThat(line1.direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(line1.amount()).isEqualTo(Money.of("250.5000", "USD"));

        LedgerEntryView line2 = view.entries().get(1);
        assertThat(line2.entryId()).isNotNull();
        assertThat(line2.lineNumber()).isEqualTo(2);
        assertThat(line2.account()).isEqualTo(clearing);
        assertThat(line2.direction()).isEqualTo(EntryDirection.CREDIT);
        assertThat(line2.amount()).isEqualTo(Money.of("250.5000", "USD"));

        LedgerBalance senderBalance = ledgerService.getBalance(sender, USD);
        assertThat(senderBalance.account()).isEqualTo(sender);
        assertThat(senderBalance.currency()).isEqualTo(USD);
        assertThat(senderBalance.debitTotal()).isEqualTo(Money.of("250.5000", "USD"));
        assertThat(senderBalance.creditTotal()).isEqualTo(Money.of("0.0000", "USD"));
        assertThat(senderBalance.netBalance()).isEqualByComparingTo("250.5000");

        LedgerBalance clearingBalance = ledgerService.getBalance(clearing, USD);
        assertThat(clearingBalance.account()).isEqualTo(clearing);
        assertThat(clearingBalance.currency()).isEqualTo(USD);
        assertThat(clearingBalance.debitTotal()).isEqualTo(Money.of("0.0000", "USD"));
        assertThat(clearingBalance.creditTotal()).isEqualTo(Money.of("250.5000", "USD"));
        assertThat(clearingBalance.netBalance()).isEqualByComparingTo("-250.5000");
    }

    @Test
    void shouldPostMultiLineSplitTransactionAndPreserveLineOrder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef customer = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-SPLIT-CUST-" + suffix);
        LedgerAccountRef principalClearing = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-SPLIT-PRIN-" + suffix);
        LedgerAccountRef feeIncome = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-SPLIT-FEE-" + suffix);

        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PK-SVC-SPLIT-" + suffix),
                USD,
                List.of(
                        new LedgerEntryLine(customer, EntryDirection.DEBIT, Money.of("150.0000", "USD")),
                        new LedgerEntryLine(principalClearing, EntryDirection.CREDIT, Money.of("145.0000", "USD")),
                        new LedgerEntryLine(feeIncome, EntryDirection.CREDIT, Money.of("5.0000", "USD"))
                ),
                "EXT-SPLIT-" + suffix,
                "Principal and fee split"
        );

        LedgerPostingResult result = ledgerService.postTransaction(command);
        assertThat(result.outcome()).isEqualTo(PostingOutcome.POSTED);

        LedgerTransactionView view = ledgerService.findTransaction(result.transactionId()).orElseThrow();
        assertThat(view.totalAmount()).isEqualTo(Money.of("150.0000", "USD"));
        assertThat(view.entries()).hasSize(3);
        assertThat(view.entries().get(0).lineNumber()).isEqualTo(1);
        assertThat(view.entries().get(0).account()).isEqualTo(customer);
        assertThat(view.entries().get(1).lineNumber()).isEqualTo(2);
        assertThat(view.entries().get(1).account()).isEqualTo(principalClearing);
        assertThat(view.entries().get(2).lineNumber()).isEqualTo(3);
        assertThat(view.entries().get(2).account()).isEqualTo(feeIncome);

        assertThat(ledgerService.getBalance(customer, USD).netBalance()).isEqualByComparingTo("150.0000");
        assertThat(ledgerService.getBalance(principalClearing, USD).netBalance()).isEqualByComparingTo("-145.0000");
        assertThat(ledgerService.getBalance(feeIncome, USD).netBalance()).isEqualByComparingTo("-5.0000");
    }

    // =========================================================================
    // 2. Idempotent Retries vs. Conflicting Posting Keys
    // =========================================================================

    @Test
    void shouldReturnAlreadyPostedOnIdempotentRetryWithSameFingerprintAndDifferentDescription() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-IDEMP-A-" + suffix);
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-IDEMP-B-" + suffix);
        LedgerPostingKey postingKey = LedgerPostingKey.of("PK-IDEMP-" + suffix);

        PostLedgerTransactionCommand initialCommand = new PostLedgerTransactionCommand(
                postingKey,
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("125.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("125.0000", "USD"))
                ),
                "EXT-IDEMP-" + suffix,
                "Initial posting description"
        );

        LedgerPostingResult firstResult = ledgerService.postTransaction(initialCommand);
        assertThat(firstResult.outcome()).isEqualTo(PostingOutcome.POSTED);

        // Retry with reversed entry list order and different description (both excluded from PostingFingerprint)
        PostLedgerTransactionCommand retryCommand = new PostLedgerTransactionCommand(
                postingKey,
                USD,
                List.of(
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("125.0000", "USD")),
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("125.0000", "USD"))
                ),
                "EXT-IDEMP-" + suffix,
                "Retried posting with different description"
        );

        LedgerPostingResult retryResult = ledgerService.postTransaction(retryCommand);
        assertThat(retryResult.outcome()).isEqualTo(PostingOutcome.ALREADY_POSTED);
        assertThat(retryResult.transactionId()).isEqualTo(firstResult.transactionId());
        assertThat(retryResult.postedAt().truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(firstResult.postedAt().truncatedTo(ChronoUnit.MICROS));

        // Immutable history check: original description and original entry order are preserved
        LedgerTransactionView storedView = ledgerService.findTransaction(firstResult.transactionId()).orElseThrow();
        assertThat(storedView.description()).isEqualTo("Initial posting description");
        assertThat(storedView.entries().get(0).account()).isEqualTo(acctA);
        assertThat(storedView.entries().get(1).account()).isEqualTo(acctB);

        // Balances reflect exactly one posting
        assertThat(ledgerService.getBalance(acctA, USD).netBalance()).isEqualByComparingTo("125.0000");
        assertThat(ledgerService.getBalance(acctB, USD).netBalance()).isEqualByComparingTo("-125.0000");
    }

    @Test
    void shouldThrowPostingKeyConflictWhenReusedWithDifferentAmountAccountOrExternalRef() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-CONF-A-" + suffix);
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-CONF-B-" + suffix);
        LedgerAccountRef acctC = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-CONF-C-" + suffix);
        LedgerPostingKey postingKey = LedgerPostingKey.of("PK-CONF-" + suffix);

        LedgerPostingResult original = ledgerService.postTransaction(new PostLedgerTransactionCommand(
                postingKey,
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("100.0000", "USD"))
                ),
                "EXT-CONF-" + suffix,
                "Original transaction"
        ));

        // 1. Different amount
        PostLedgerTransactionCommand diffAmountCmd = new PostLedgerTransactionCommand(
                postingKey,
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("101.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("101.0000", "USD"))
                ),
                "EXT-CONF-" + suffix,
                "Original transaction"
        );
        assertThatThrownBy(() -> ledgerService.postTransaction(diffAmountCmd))
                .isInstanceOf(LedgerPostingKeyConflictException.class)
                .satisfies(ex -> {
                    LedgerPostingKeyConflictException conflict = (LedgerPostingKeyConflictException) ex;
                    assertThat(conflict.getPostingKey()).isEqualTo(postingKey);
                    assertThat(conflict.getExistingTransactionId()).isEqualTo(original.transactionId());
                });

        // 2. Different counterparty account
        PostLedgerTransactionCommand diffAccountCmd = new PostLedgerTransactionCommand(
                postingKey,
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                        new LedgerEntryLine(acctC, EntryDirection.CREDIT, Money.of("100.0000", "USD"))
                ),
                "EXT-CONF-" + suffix,
                "Original transaction"
        );
        assertThatThrownBy(() -> ledgerService.postTransaction(diffAccountCmd))
                .isInstanceOf(LedgerPostingKeyConflictException.class)
                .satisfies(ex -> {
                    LedgerPostingKeyConflictException conflict = (LedgerPostingKeyConflictException) ex;
                    assertThat(conflict.getPostingKey()).isEqualTo(postingKey);
                    assertThat(conflict.getExistingTransactionId()).isEqualTo(original.transactionId());
                });

        // 3. Different externalReference
        PostLedgerTransactionCommand diffExtRefCmd = new PostLedgerTransactionCommand(
                postingKey,
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("100.0000", "USD"))
                ),
                "EXT-CONF-DIFFERENT-" + suffix,
                "Original transaction"
        );
        assertThatThrownBy(() -> ledgerService.postTransaction(diffExtRefCmd))
                .isInstanceOf(LedgerPostingKeyConflictException.class)
                .satisfies(ex -> {
                    LedgerPostingKeyConflictException conflict = (LedgerPostingKeyConflictException) ex;
                    assertThat(conflict.getPostingKey()).isEqualTo(postingKey);
                    assertThat(conflict.getExistingTransactionId()).isEqualTo(original.transactionId());
                });

        // Original balances remain untouched
        assertThat(ledgerService.getBalance(acctA, USD).netBalance()).isEqualByComparingTo("100.0000");
        assertThat(ledgerService.getBalance(acctB, USD).netBalance()).isEqualByComparingTo("-100.0000");
        assertThat(ledgerService.getBalance(acctC, USD).netBalance()).isEqualByComparingTo("0.0000");
    }

    // =========================================================================
    // 3. Shared Caller Transaction Participation & Rollback Semantics
    // =========================================================================

    @Test
    void shouldNotMarkSharedCallerTransactionRollbackOnlyWhenConflictOrValidationExceptionIsCaught() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-SHARED-A-" + suffix);
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-SHARED-B-" + suffix);
        LedgerPostingKey key1 = LedgerPostingKey.of("PK-SHARED-1-" + suffix);
        LedgerPostingKey key2 = LedgerPostingKey.of("PK-SHARED-2-" + suffix);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        List<LedgerPostingResult> committedResults = txTemplate.execute(status -> {
            // Step 1: Valid posting inside shared transaction
            LedgerPostingResult res1 = ledgerService.postTransaction(new PostLedgerTransactionCommand(
                    key1,
                    USD,
                    List.of(
                            new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                            new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("100.0000", "USD"))
                    ),
                    "EXT-SHARED-1",
                    "First shared tx posting"
            ));

            // Step 2: Unbalanced posting throws LedgerValidationException; caller catches it
            assertThatThrownBy(() -> ledgerService.postTransaction(new PostLedgerTransactionCommand(
                    LedgerPostingKey.of("PK-UNBALANCED-" + suffix),
                    USD,
                    List.of(
                            new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("100.0000", "USD")),
                            new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("90.0000", "USD"))
                    ),
                    "EXT-UNBAL",
                    null
            ))).isInstanceOf(LedgerValidationException.class);

            // Step 3: Conflicting reuse of key1 throws LedgerPostingKeyConflictException; caller catches it
            assertThatThrownBy(() -> ledgerService.postTransaction(new PostLedgerTransactionCommand(
                    key1,
                    USD,
                    List.of(
                            new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("999.0000", "USD")),
                            new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("999.0000", "USD"))
                    ),
                    "EXT-SHARED-1",
                    "Conflicting attempt"
            ))).isInstanceOf(LedgerPostingKeyConflictException.class);

            // Step 4: Missing transaction reversal throws LedgerTransactionNotFoundException; caller catches it
            assertThatThrownBy(() -> ledgerService.reverseTransaction(new ReverseLedgerTransactionCommand(
                    LedgerTransactionId.generate(),
                    "REV-MISSING",
                    null
            ))).isInstanceOf(LedgerTransactionNotFoundException.class);

            assertThat(status.isRollbackOnly()).isFalse();

            // Step 5: Caller continues doing valid work in the same transaction and commits cleanly
            LedgerPostingResult res2 = ledgerService.postTransaction(new PostLedgerTransactionCommand(
                    key2,
                    USD,
                    List.of(
                            new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("50.0000", "USD")),
                            new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("50.0000", "USD"))
                    ),
                    "EXT-SHARED-2",
                    "Second shared tx posting"
            ));

            return List.of(res1, res2);
        });

        assertThat(committedResults).hasSize(2);
        assertThat(ledgerService.findTransaction(committedResults.get(0).transactionId())).isPresent();
        assertThat(ledgerService.findTransaction(committedResults.get(1).transactionId())).isPresent();
        assertThat(ledgerService.getBalance(acctA, USD).netBalance()).isEqualByComparingTo("150.0000");
        assertThat(ledgerService.getBalance(acctB, USD).netBalance()).isEqualByComparingTo("-150.0000");
    }

    @Test
    void shouldRollBackSharedCallerTransactionOnDatabaseTriggerOrConstraintViolation() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-RB-A-" + suffix);
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-RB-B-" + suffix);
        LedgerPostingKey validKey = LedgerPostingKey.of("PK-RB-VALID-" + suffix);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            // Valid posting inside caller transaction
            ledgerService.postTransaction(new PostLedgerTransactionCommand(
                    validKey,
                    USD,
                    List.of(
                            new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("75.0000", "USD")),
                            new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("75.0000", "USD"))
                    ),
                    "EXT-RB-1",
                    null
            ));

            // Corrupt raw SQL header with zero entries in the same transaction triggers deferred constraint failure at commit
            jdbcTemplate.update(
                    """
                    INSERT INTO ledger.ledger_transaction (
                        transaction_id, posting_key, transaction_type, currency,
                        total_amount, entry_count, external_reference, description,
                        reversed_transaction_id, request_fingerprint, posted_at
                    ) VALUES (?, ?, 'STANDARD', 'USD', ?, 2, 'EXT-BAD', NULL, NULL, ?, ?)
                    """,
                    UUID.randomUUID(),
                    "PK-RB-ORPHAN-" + suffix,
                    new BigDecimal("50.0000"),
                    "a".repeat(64),
                    Timestamp.from(Instant.now())
            );
        })).isInstanceOf(DataAccessException.class);

        // Verify the earlier valid posting in the same transaction was rolled back completely
        assertThat(ledgerService.getBalance(acctA, USD).netBalance()).isEqualByComparingTo("0.0000");
        assertThat(ledgerService.getBalance(acctB, USD).netBalance()).isEqualByComparingTo("0.0000");
    }

    // =========================================================================
    // 4. Reversal Semantics, Reversal Idempotency, and Conflict Handling
    // =========================================================================

    @Test
    void shouldReverseStandardTransactionAndNetAccountBalancesToZero() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef sender = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-REV-SENDER-" + suffix);
        LedgerAccountRef clearing = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-REV-CLEARING-" + suffix);

        LedgerPostingResult origResult = ledgerService.postTransaction(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PK-REV-ORIG-" + suffix),
                EUR,
                List.of(
                        new LedgerEntryLine(sender, EntryDirection.DEBIT, Money.of("300.0000", "EUR")),
                        new LedgerEntryLine(clearing, EntryDirection.CREDIT, Money.of("300.0000", "EUR"))
                ),
                "EXT-REV-ORIG-" + suffix,
                "Original EUR settlement"
        ));

        LedgerPostingResult revResult = ledgerService.reverseTransaction(new ReverseLedgerTransactionCommand(
                origResult.transactionId(),
                "REV-REF-" + suffix,
                "Downstream correspondent rejection"
        ));

        assertThat(revResult.outcome()).isEqualTo(PostingOutcome.POSTED);
        assertThat(revResult.transactionId()).isNotEqualTo(origResult.transactionId());

        // Original transaction remains completely unchanged
        LedgerTransactionView origView = ledgerService.findTransaction(origResult.transactionId()).orElseThrow();
        assertThat(origView.transactionType()).isEqualTo(LedgerTransactionType.STANDARD);
        assertThat(origView.reversedTransactionId()).isNull();
        assertThat(origView.description()).isEqualTo("Original EUR settlement");

        // Reversal view has deterministic key, reversedTransactionId, and flipped entry directions
        LedgerTransactionView revView = ledgerService.findTransaction(revResult.transactionId()).orElseThrow();
        assertThat(revView.transactionType()).isEqualTo(LedgerTransactionType.REVERSAL);
        assertThat(revView.postingKey()).isEqualTo(LedgerPostingKey.forReversal(origResult.transactionId()));
        assertThat(revView.reversedTransactionId()).isEqualTo(origResult.transactionId());
        assertThat(revView.currency()).isEqualTo(EUR);
        assertThat(revView.totalAmount()).isEqualTo(Money.of("300.0000", "EUR"));
        assertThat(revView.externalReference()).isEqualTo("REV-REF-" + suffix);
        assertThat(revView.description()).isEqualTo("Downstream correspondent rejection");
        assertThat(revView.entries()).hasSize(2);
        assertThat(revView.entries().get(0).account()).isEqualTo(sender);
        assertThat(revView.entries().get(0).direction()).isEqualTo(EntryDirection.CREDIT);
        assertThat(revView.entries().get(1).account()).isEqualTo(clearing);
        assertThat(revView.entries().get(1).direction()).isEqualTo(EntryDirection.DEBIT);

        // Both balances net to 0.0000
        LedgerBalance senderBalance = ledgerService.getBalance(sender, EUR);
        assertThat(senderBalance.debitTotal()).isEqualTo(Money.of("300.0000", "EUR"));
        assertThat(senderBalance.creditTotal()).isEqualTo(Money.of("300.0000", "EUR"));
        assertThat(senderBalance.netBalance()).isEqualByComparingTo("0.0000");

        LedgerBalance clearingBalance = ledgerService.getBalance(clearing, EUR);
        assertThat(clearingBalance.debitTotal()).isEqualTo(Money.of("300.0000", "EUR"));
        assertThat(clearingBalance.creditTotal()).isEqualTo(Money.of("300.0000", "EUR"));
        assertThat(clearingBalance.netBalance()).isEqualByComparingTo("0.0000");
    }

    @Test
    void shouldReturnAlreadyPostedOnIdempotentReversalRetryWithSameReversalReference() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-REVIDEMP-A-" + suffix);
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-REVIDEMP-B-" + suffix);

        LedgerPostingResult origResult = ledgerService.postTransaction(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PK-REVIDEMP-ORIG-" + suffix),
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("90.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("90.0000", "USD"))
                ),
                "EXT-REVIDEMP-" + suffix,
                null
        ));

        LedgerPostingResult firstRev = ledgerService.reverseTransaction(new ReverseLedgerTransactionCommand(
                origResult.transactionId(),
                "REV-SAME-REF-" + suffix,
                "Initial reversal reason"
        ));
        assertThat(firstRev.outcome()).isEqualTo(PostingOutcome.POSTED);

        // Retry with same originalTransactionId and reversalReference, but different reason (excluded from fingerprint)
        LedgerPostingResult secondRev = ledgerService.reverseTransaction(new ReverseLedgerTransactionCommand(
                origResult.transactionId(),
                "REV-SAME-REF-" + suffix,
                "Updated reason on retry"
        ));
        assertThat(secondRev.outcome()).isEqualTo(PostingOutcome.ALREADY_POSTED);
        assertThat(secondRev.transactionId()).isEqualTo(firstRev.transactionId());
        assertThat(secondRev.postedAt().truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(firstRev.postedAt().truncatedTo(ChronoUnit.MICROS));

        // Stored reversal reason remains immutable
        LedgerTransactionView revView = ledgerService.findTransaction(firstRev.transactionId()).orElseThrow();
        assertThat(revView.description()).isEqualTo("Initial reversal reason");
        assertThat(ledgerService.getBalance(acctA, USD).netBalance()).isEqualByComparingTo("0.0000");
    }

    @Test
    void shouldThrowLedgerReversalConflictWhenAlreadyReversedWithDifferentReversalReference() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-REVCONF-A-" + suffix);
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-REVCONF-B-" + suffix);

        LedgerPostingResult origResult = ledgerService.postTransaction(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PK-REVCONF-ORIG-" + suffix),
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("200.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("200.0000", "USD"))
                ),
                "EXT-REVCONF-" + suffix,
                null
        ));

        LedgerPostingResult firstRev = ledgerService.reverseTransaction(new ReverseLedgerTransactionCommand(
                origResult.transactionId(),
                "REV-REF-FIRST-" + suffix,
                "First reversal"
        ));

        // Execute conflicting reversal inside a caller TransactionTemplate to verify shared transaction survival
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            assertThatThrownBy(() -> ledgerService.reverseTransaction(new ReverseLedgerTransactionCommand(
                    origResult.transactionId(),
                    "REV-REF-SECOND-" + suffix,
                    "Conflicting reversal reference"
            )))
                    .isInstanceOf(LedgerReversalConflictException.class)
                    .satisfies(ex -> {
                        LedgerReversalConflictException conflict = (LedgerReversalConflictException) ex;
                        assertThat(conflict.getOriginalTransactionId()).isEqualTo(origResult.transactionId());
                        assertThat(conflict.getExistingReversalTransactionId()).isEqualTo(firstRev.transactionId());
                    });

            assertThat(status.isRollbackOnly()).isFalse();
        });

        assertThat(ledgerService.getBalance(acctA, USD).netBalance()).isEqualByComparingTo("0.0000");
    }

    @Test
    void shouldThrowTransactionNotFoundWhenReversingNonExistentTransaction() {
        LedgerTransactionId missingId = LedgerTransactionId.generate();

        assertThatThrownBy(() -> ledgerService.reverseTransaction(new ReverseLedgerTransactionCommand(
                missingId,
                "REV-MISSING-001",
                "Should fail"
        )))
                .isInstanceOf(LedgerTransactionNotFoundException.class)
                .satisfies(ex -> {
                    LedgerTransactionNotFoundException notFound = (LedgerTransactionNotFoundException) ex;
                    assertThat(notFound.getTransactionId()).isEqualTo(missingId);
                });
    }

    @Test
    void shouldRejectReversingAReversalTransaction() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-REVCHAIN-A-" + suffix);
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-REVCHAIN-B-" + suffix);

        LedgerPostingResult orig = ledgerService.postTransaction(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PK-REVCHAIN-" + suffix),
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("60.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("60.0000", "USD"))
                ),
                "EXT-REVCHAIN-" + suffix,
                null
        ));

        LedgerPostingResult rev = ledgerService.reverseTransaction(new ReverseLedgerTransactionCommand(
                orig.transactionId(),
                "REV-CHAIN-1-" + suffix,
                null
        ));

        assertThatThrownBy(() -> ledgerService.reverseTransaction(new ReverseLedgerTransactionCommand(
                rev.transactionId(),
                "REV-OF-REV-" + suffix,
                "Must be rejected"
        )))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("already a REVERSAL");
    }

    @Test
    void shouldReturnEmptyOptionalWhenFindingNonExistentTransactionAndRejectNullArguments() {
        assertThat(ledgerService.findTransaction(LedgerTransactionId.generate())).isEmpty();

        LedgerAccountRef validAcct = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-NULL-CHECK");

        assertThatThrownBy(() -> ledgerService.postTransaction(null))
                .isInstanceOf(LedgerValidationException.class);
        assertThatThrownBy(() -> ledgerService.reverseTransaction(null))
                .isInstanceOf(LedgerValidationException.class);
        assertThatThrownBy(() -> ledgerService.findTransaction(null))
                .isInstanceOf(LedgerValidationException.class);
        assertThatThrownBy(() -> ledgerService.getBalance(null, USD))
                .isInstanceOf(LedgerValidationException.class);
        assertThatThrownBy(() -> ledgerService.getBalance(validAcct, null))
                .isInstanceOf(LedgerValidationException.class);
    }

    // =========================================================================
    // 5. Multi-Threaded Concurrency Races Against PostgreSQL 18
    // =========================================================================

    @Test
    void shouldHandleConcurrentDuplicatePostTransactionRequestsDeterministically() throws Exception {
        int threads = 10;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-CONC-DUP-A-" + suffix);
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-CONC-DUP-B-" + suffix);
        LedgerPostingKey sharedKey = LedgerPostingKey.of("PK-CONC-DUP-" + suffix);

        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(
                sharedKey,
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("45.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("45.0000", "USD"))
                ),
                "EXT-CONC-DUP-" + suffix,
                "Concurrent identical posting"
        );

        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Callable<LedgerPostingResult>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    readyLatch.countDown();
                    assertThat(startLatch.await(10, TimeUnit.SECONDS)).isTrue();
                    return ledgerService.postTransaction(command);
                });
            }

            List<Future<LedgerPostingResult>> futures = new ArrayList<>(threads);
            for (Callable<LedgerPostingResult> task : tasks) {
                futures.add(pool.submit(task));
            }

            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            List<LedgerPostingResult> results = new ArrayList<>(threads);
            for (Future<LedgerPostingResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            long postedCount = results.stream().filter(r -> r.outcome() == PostingOutcome.POSTED).count();
            long alreadyPostedCount = results.stream().filter(r -> r.outcome() == PostingOutcome.ALREADY_POSTED).count();
            Set<LedgerTransactionId> distinctIds = Set.copyOf(results.stream().map(LedgerPostingResult::transactionId).toList());

            assertThat(postedCount).isEqualTo(1);
            assertThat(alreadyPostedCount).isEqualTo(threads - 1);
            assertThat(distinctIds).hasSize(1);

            assertThat(ledgerService.getBalance(acctA, USD).netBalance()).isEqualByComparingTo("45.0000");
            assertThat(ledgerService.getBalance(acctB, USD).netBalance()).isEqualByComparingTo("-45.0000");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldHandleConcurrentConflictingPostTransactionRequestsDeterministically() throws Exception {
        int threads = 10;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-CONC-CONF-A-" + suffix);
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-CONC-CONF-B-" + suffix);
        LedgerPostingKey sharedKey = LedgerPostingKey.of("PK-CONC-CONF-" + suffix);

        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<LedgerPostingResult> successes = Collections.synchronizedList(new ArrayList<>());
        List<LedgerPostingKeyConflictException> conflicts = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int i = 1; i <= threads; i++) {
                Money distinctAmount = Money.of((i * 10) + ".0000", "USD");
                PostLedgerTransactionCommand cmd = new PostLedgerTransactionCommand(
                        sharedKey,
                        USD,
                        List.of(
                                new LedgerEntryLine(acctA, EntryDirection.DEBIT, distinctAmount),
                                new LedgerEntryLine(acctB, EntryDirection.CREDIT, distinctAmount)
                        ),
                        "EXT-CONC-CONF-" + i,
                        null
                );

                futures.add(pool.submit(() -> {
                    readyLatch.countDown();
                    try {
                        assertThat(startLatch.await(10, TimeUnit.SECONDS)).isTrue();
                        successes.add(ledgerService.postTransaction(cmd));
                    } catch (LedgerPostingKeyConflictException ex) {
                        conflicts.add(ex);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ex);
                    }
                }));
            }

            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }

            assertThat(successes).hasSize(1);
            assertThat(successes.getFirst().outcome()).isEqualTo(PostingOutcome.POSTED);
            assertThat(conflicts).hasSize(threads - 1);

            LedgerTransactionId winningTxId = successes.getFirst().transactionId();
            for (LedgerPostingKeyConflictException conflict : conflicts) {
                assertThat(conflict.getPostingKey()).isEqualTo(sharedKey);
                assertThat(conflict.getExistingTransactionId()).isEqualTo(winningTxId);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldHandleConcurrentReversalRequestsDeterministically() throws Exception {
        int threads = 10;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LedgerAccountRef acctA = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-CONC-REV-A-" + suffix);
        LedgerAccountRef acctB = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "ACC-CONC-REV-B-" + suffix);

        LedgerPostingResult orig = ledgerService.postTransaction(new PostLedgerTransactionCommand(
                LedgerPostingKey.of("PK-CONC-REV-ORIG-" + suffix),
                USD,
                List.of(
                        new LedgerEntryLine(acctA, EntryDirection.DEBIT, Money.of("80.0000", "USD")),
                        new LedgerEntryLine(acctB, EntryDirection.CREDIT, Money.of("80.0000", "USD"))
                ),
                "EXT-CONC-REV-" + suffix,
                null
        ));

        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<LedgerPostingResult> successes = Collections.synchronizedList(new ArrayList<>());
        List<LedgerReversalConflictException> conflicts = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger idxCounter = new AtomicInteger(0);

        try {
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    int idx = idxCounter.getAndIncrement();
                    String reversalRef = (idx < 5) ? "REV-ALPHA-" + suffix : "REV-BETA-" + suffix;
                    ReverseLedgerTransactionCommand revCmd = new ReverseLedgerTransactionCommand(
                            orig.transactionId(),
                            reversalRef,
                            "Concurrent reversal attempt " + idx
                    );

                    readyLatch.countDown();
                    try {
                        assertThat(startLatch.await(10, TimeUnit.SECONDS)).isTrue();
                        successes.add(ledgerService.reverseTransaction(revCmd));
                    } catch (LedgerReversalConflictException ex) {
                        conflicts.add(ex);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ex);
                    }
                }));
            }

            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }

            // 5 threads shared the winning reference (1 POSTED + 4 ALREADY_POSTED);
            // the 5 threads with the other reference must all get LedgerReversalConflictException.
            assertThat(successes).hasSize(5);
            assertThat(successes.stream().filter(r -> r.outcome() == PostingOutcome.POSTED).count()).isEqualTo(1);
            assertThat(successes.stream().filter(r -> r.outcome() == PostingOutcome.ALREADY_POSTED).count()).isEqualTo(4);
            assertThat(conflicts).hasSize(5);

            LedgerTransactionId winningRevId = successes.getFirst().transactionId();
            for (LedgerPostingResult res : successes) {
                assertThat(res.transactionId()).isEqualTo(winningRevId);
            }
            for (LedgerReversalConflictException conflict : conflicts) {
                assertThat(conflict.getOriginalTransactionId()).isEqualTo(orig.transactionId());
                assertThat(conflict.getExistingReversalTransactionId()).isEqualTo(winningRevId);
            }

            assertThat(ledgerService.getBalance(acctA, USD).netBalance()).isEqualByComparingTo("0.0000");
            assertThat(ledgerService.getBalance(acctB, USD).netBalance()).isEqualByComparingTo("0.0000");
        } finally {
            pool.shutdownNow();
        }
    }

    // =========================================================================
    // 6. Spring Modulith & Public Contract Encapsulation Verification
    // =========================================================================

    @Test
    void shouldPassSpringModulithVerificationAndExposeOnlyPublicLedgerContracts() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);
        modules.verify();

        for (Method method : LedgerService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertNotInternalType(method.getGenericReturnType(), method.getName());
                for (Type paramType : method.getGenericParameterTypes()) {
                    assertNotInternalType(paramType, method.getName());
                }
            }
        }
    }

    private static void assertNotInternalType(Type type, String methodName) {
        if (type instanceof Class<?> clazz) {
            assertThat(clazz.getName())
                    .as("Public method %s must not expose internal domain or persistence types", methodName)
                    .doesNotStartWith("com.crosspaynet.ledger.domain.")
                    .doesNotStartWith("com.crosspaynet.ledger.infrastructure.");
        } else if (type instanceof ParameterizedType paramType) {
            assertNotInternalType(paramType.getRawType(), methodName);
            for (Type arg : paramType.getActualTypeArguments()) {
                assertNotInternalType(arg, methodName);
            }
        }
    }
}
