package com.crosspaynet.settlement;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.InvalidAccountStateException;
import com.crosspaynet.banking.contracts.InvalidReservationStateException;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.EntryDirection;
import com.crosspaynet.ledger.LedgerAccountRef;
import com.crosspaynet.ledger.LedgerAccountType;
import com.crosspaynet.ledger.LedgerBalance;
import com.crosspaynet.ledger.LedgerEntryLine;
import com.crosspaynet.ledger.LedgerEntryView;
import com.crosspaynet.ledger.LedgerPostingKey;
import com.crosspaynet.ledger.LedgerPostingKeyConflictException;
import com.crosspaynet.ledger.LedgerPostingResult;
import com.crosspaynet.ledger.LedgerService;
import com.crosspaynet.ledger.LedgerTransactionId;
import com.crosspaynet.ledger.LedgerTransactionType;
import com.crosspaynet.ledger.LedgerTransactionView;
import com.crosspaynet.ledger.PostLedgerTransactionCommand;
import com.crosspaynet.ledger.PostingOutcome;
import com.crosspaynet.payment.PaymentService;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.CancelPaymentCommand;
import com.crosspaynet.payment.contracts.CommitPaymentExecutionCommand;
import com.crosspaynet.payment.contracts.FailPaymentCommand;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.InvalidPaymentStateException;
import com.crosspaynet.payment.contracts.MarkPaymentDispatchedCommand;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentNotFoundException;
import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.payment.contracts.RejectPaymentCommand;
import com.crosspaynet.payment.settlement.PaymentSettlementPort;
import com.crosspaynet.payment.settlement.RecordPaymentSettlementCommand;
import com.crosspaynet.settlement.contracts.SettlePaymentCommand;
import com.crosspaynet.settlement.contracts.SettlementConflictException;
import com.crosspaynet.settlement.contracts.SettlementResult;
import com.crosspaynet.settlement.contracts.SettlementValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class SettlementServiceIntegrationTest {

    private static final Currency USD = new Currency("USD");

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentSettlementPort paymentSettlementPort;

    @Autowired
    private BankingService bankingService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================================
    // 1. End-to-End Happy-Path Settlement
    // =========================================================================

    @Test
    void shouldAtomicallyConsumeReservationPostDoubleEntryLedgerTransactionAndSettlePayment() {
        AccountId sourceAccountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView dispatched = createDispatchedPayment(
                sourceAccountId,
                Money.of("275.5000", "USD"),
                "ROUTE-SWIFT-EUR-01",
                "SWIFT-MT103-DISP-01"
        );
        String clearingRef = "CLEARING_SWIFT_USD_" + shortId();
        String settlementRef = "SWIFT-ACK-103-" + shortId();

        SettlementResult result = settlementService.settlePayment(
                new SettlePaymentCommand(dispatched.paymentId(), clearingRef, "  " + settlementRef + "  ")
        );

        // 1. Verify SettlementResult
        assertThat(result.paymentId()).isEqualTo(dispatched.paymentId());
        assertThat(result.reservationToken()).isEqualTo(dispatched.reservationToken());
        assertThat(result.ledgerTransactionId()).isNotNull();
        assertThat(result.ledgerPostingOutcome()).isEqualTo(PostingOutcome.POSTED);
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(result.payment().ledgerTransactionId()).isEqualTo(result.ledgerTransactionId().getValue());
        assertThat(result.settledAt()).isEqualTo(result.payment().updatedAt());

        // 2. Verify persisted Payment state
        PaymentView persistedPayment = paymentService.findPayment(dispatched.paymentId()).orElseThrow();
        assertThat(persistedPayment).isEqualTo(result.payment());
        assertThat(persistedPayment.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(persistedPayment.ledgerTransactionId()).isEqualTo(result.ledgerTransactionId().getValue());
        assertThat(persistedPayment.routeReference()).isEqualTo("ROUTE-SWIFT-EUR-01");
        assertThat(persistedPayment.externalDispatchReference()).isEqualTo("SWIFT-MT103-DISP-01");

        // 3. Verify persisted Banking state: available_balance remains deducted, reserved_amount is now 0, reservation is CONSUMED
        assertAccountBalances(sourceAccountId, new BigDecimal("724.5000"), new BigDecimal("0.0000"));
        assertReservationStatus(dispatched.reservationToken(), "CONSUMED", new BigDecimal("275.5000"), "USD");

        // 4. Verify persisted Ledger transaction and entry lines
        LedgerTransactionView ledgerTx = ledgerService.findTransaction(result.ledgerTransactionId()).orElseThrow();
        assertThat(ledgerTx.postingKey()).isEqualTo(SettlementService.postingKeyFor(dispatched.paymentId()));
        assertThat(ledgerTx.transactionType()).isEqualTo(LedgerTransactionType.STANDARD);
        assertThat(ledgerTx.currency()).isEqualTo(USD);
        assertThat(ledgerTx.totalAmount()).isEqualTo(Money.of("275.5000", "USD"));
        assertThat(ledgerTx.externalReference()).isEqualTo(settlementRef);
        assertThat(ledgerTx.description()).isEqualTo("Settlement for payment " + dispatched.paymentId().getValue());
        assertThat(ledgerTx.reversedTransactionId()).isNull();
        assertThat(ledgerTx.entries()).hasSize(2);

        LedgerAccountRef expectedSourceLedgerAccount = LedgerAccountRef.of(
                LedgerAccountType.BANKING_ACCOUNT,
                sourceAccountId.getValue().toString()
        );
        LedgerAccountRef expectedClearingAccount = LedgerAccountRef.of(
                LedgerAccountType.INTERNAL,
                clearingRef
        );

        LedgerEntryView debitLine = ledgerTx.entries().get(0);
        assertThat(debitLine.lineNumber()).isEqualTo(1);
        assertThat(debitLine.account()).isEqualTo(expectedSourceLedgerAccount);
        assertThat(debitLine.direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(debitLine.amount()).isEqualTo(Money.of("275.5000", "USD"));

        LedgerEntryView creditLine = ledgerTx.entries().get(1);
        assertThat(creditLine.lineNumber()).isEqualTo(2);
        assertThat(creditLine.account()).isEqualTo(expectedClearingAccount);
        assertThat(creditLine.direction()).isEqualTo(EntryDirection.CREDIT);
        assertThat(creditLine.amount()).isEqualTo(Money.of("275.5000", "USD"));

        // 5. Verify authoritative Ledger balances
        LedgerBalance sourceBalance = ledgerService.getBalance(expectedSourceLedgerAccount, USD);
        assertThat(sourceBalance.debitTotal()).isEqualTo(Money.of("275.5000", "USD"));
        assertThat(sourceBalance.creditTotal()).isEqualTo(Money.of("0.0000", "USD"));
        assertThat(sourceBalance.netBalance()).isEqualByComparingTo(new BigDecimal("275.5000"));

        LedgerBalance clearingBalance = ledgerService.getBalance(expectedClearingAccount, USD);
        assertThat(clearingBalance.debitTotal()).isEqualTo(Money.of("0.0000", "USD"));
        assertThat(clearingBalance.creditTotal()).isEqualTo(Money.of("275.5000", "USD"));
        assertThat(clearingBalance.netBalance()).isEqualByComparingTo(new BigDecimal("-275.5000"));
    }

    // =========================================================================
    // 2. Idempotent Duplicate Retry vs. Conflicting Retry on Already-SETTLED Payment
    // =========================================================================

    @Test
    void shouldReturnAlreadyPostedOnIdenticalRetryAndRejectConflictingParametersWhenAlreadySettled() {
        AccountId sourceAccountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView dispatched = createDispatchedPayment(
                sourceAccountId,
                Money.of("300.0000", "USD"),
                "ROUTE-IDEMP-01",
                "EXT-IDEMP-01"
        );
        String clearingRef = "CLEARING_USD_" + shortId();
        String settlementRef = "ACK-IDEMP-" + shortId();
        SettlePaymentCommand command = new SettlePaymentCommand(dispatched.paymentId(), clearingRef, settlementRef);

        SettlementResult first = settlementService.settlePayment(command);
        assertThat(first.ledgerPostingOutcome()).isEqualTo(PostingOutcome.POSTED);

        long txCountAfterFirst = countLedgerTransactionsForPostingKey(
                SettlementService.postingKeyFor(dispatched.paymentId())
        );
        assertThat(txCountAfterFirst).isEqualTo(1L);

        // 1. Identical retry (including whitespace around settlementReference) returns ALREADY_POSTED
        SettlementResult second = settlementService.settlePayment(
                new SettlePaymentCommand(dispatched.paymentId(), clearingRef, "   " + settlementRef + "   ")
        );
        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.reservationToken()).isEqualTo(first.reservationToken());
        assertThat(second.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());
        assertThat(second.ledgerPostingOutcome()).isEqualTo(PostingOutcome.ALREADY_POSTED);
        assertThat(second.payment()).isEqualTo(first.payment());

        // 2. Conflicting clearingAccountReference on already-SETTLED payment -> LedgerPostingKeyConflictException
        assertThatThrownBy(() -> settlementService.settlePayment(
                new SettlePaymentCommand(dispatched.paymentId(), "DIFFERENT_CLEARING_" + shortId(), settlementRef)
        )).isInstanceOf(LedgerPostingKeyConflictException.class);

        // 3. Conflicting settlementReference on already-SETTLED payment -> LedgerPostingKeyConflictException
        assertThatThrownBy(() -> settlementService.settlePayment(
                new SettlePaymentCommand(dispatched.paymentId(), clearingRef, "DIFFERENT-ACK-" + shortId())
        )).isInstanceOf(LedgerPostingKeyConflictException.class);

        // 4. Verify balances and row counts were never duplicated
        assertThat(countLedgerTransactionsForPostingKey(SettlementService.postingKeyFor(dispatched.paymentId())))
                .isEqualTo(1L);
        assertAccountBalances(sourceAccountId, new BigDecimal("700.0000"), new BigDecimal("0.0000"));
        assertReservationStatus(dispatched.reservationToken(), "CONSUMED", new BigDecimal("300.0000"), "USD");
    }

    @Test
    void shouldRejectAlreadySettledPaymentWhoseRecordedLedgerTransactionIdConflictsWithLedgerPosting() {
        AccountId sourceAccountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView dispatched = createDispatchedPayment(
                sourceAccountId,
                Money.of("150.0000", "USD"),
                "ROUTE-MISMATCH-01",
                "EXT-MISMATCH-01"
        );
        String clearingRef = "CLEARING_MISMATCH_" + shortId();
        String settlementRef = "ACK-MISMATCH-" + shortId();

        // Settle the payment directly via the low-level port with a synthetic UUID that does NOT match Ledger
        UUID syntheticTxId = UUID.randomUUID();
        paymentSettlementPort.recordSettlement(
                new RecordPaymentSettlementCommand(dispatched.paymentId(), syntheticTxId)
        );

        long ledgerCountBefore = countLedgerTransactionsForPostingKey(
                SettlementService.postingKeyFor(dispatched.paymentId())
        );
        assertThat(ledgerCountBefore).isZero();

        // Calling settlePayment on this inconsistently SETTLED payment must detect that
        // outcome == POSTED (or transactionId != syntheticTxId), roll back the newly inserted ledger row,
        // and throw SettlementConflictException!
        assertThatThrownBy(() -> settlementService.settlePayment(
                new SettlePaymentCommand(dispatched.paymentId(), clearingRef, settlementRef)
        )).isInstanceOf(SettlementConflictException.class);

        // Verify the ledger transaction posted during verifyAlreadySettledReplay was rolled back!
        long ledgerCountAfter = countLedgerTransactionsForPostingKey(
                SettlementService.postingKeyFor(dispatched.paymentId())
        );
        assertThat(ledgerCountAfter).isZero();
        assertReservationStatus(dispatched.reservationToken(), "ACTIVE", new BigDecimal("150.0000"), "USD");
    }

    // =========================================================================
    // 3. Pre-Existing Conflicting vs. Matching Ledger Posting Key on PENDING_EXTERNAL Payment
    // =========================================================================

    @Test
    void shouldRollBackReservationConsumptionWhenPreExistingLedgerPostingKeyConflicts() {
        AccountId sourceAccountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView dispatched = createDispatchedPayment(
                sourceAccountId,
                Money.of("200.0000", "USD"),
                "ROUTE-PRECONF-01",
                "EXT-PRECONF-01"
        );
        String clearingRef = "CLEARING_PRECONF_" + shortId();
        String settlementRef = "ACK-PRECONF-" + shortId();

        // Pre-post a conflicting transaction under PAYMENT_SETTLEMENT:{paymentId} (different amount: 999.0000)
        LedgerPostingKey settlementKey = SettlementService.postingKeyFor(dispatched.paymentId());
        LedgerPostingResult preExistingConflict = ledgerService.postTransaction(new PostLedgerTransactionCommand(
                settlementKey,
                USD,
                List.of(
                        new LedgerEntryLine(
                                LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, sourceAccountId.getValue().toString()),
                                EntryDirection.DEBIT,
                                Money.of("999.0000", "USD")
                        ),
                        new LedgerEntryLine(
                                LedgerAccountRef.of(LedgerAccountType.INTERNAL, clearingRef),
                                EntryDirection.CREDIT,
                                Money.of("999.0000", "USD")
                        )
                ),
                settlementRef,
                "Pre-existing conflicting transaction"
        ));
        assertThat(preExistingConflict.outcome()).isEqualTo(PostingOutcome.POSTED);

        // 1. Standalone call: consumeReservation executes first, then ledgerService.postTransaction throws
        //    LedgerPostingKeyConflictException -> consumeReservation MUST roll back!
        SettlePaymentCommand settleCmd = new SettlePaymentCommand(dispatched.paymentId(), clearingRef, settlementRef);
        assertThatThrownBy(() -> settlementService.settlePayment(settleCmd))
                .isInstanceOf(LedgerPostingKeyConflictException.class);

        assertReservationStatus(dispatched.reservationToken(), "ACTIVE", new BigDecimal("200.0000"), "USD");
        assertAccountBalances(sourceAccountId, new BigDecimal("800.0000"), new BigDecimal("200.0000"));
        assertThat(paymentService.findPayment(dispatched.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.PENDING_EXTERNAL);

        // 2. Caught inside an outer caller TransactionTemplate: becauseLedgerService.postTransaction has
        //    noRollbackFor = LedgerValidationException.class, SettlementService's explicit
        //    markCurrentTransactionRollbackOnly() is critical to prevent committing consumeReservation!
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            assertThatThrownBy(() -> settlementService.settlePayment(settleCmd))
                    .isInstanceOf(LedgerPostingKeyConflictException.class);
            assertThat(status.isRollbackOnly()).isTrue();
        })).isInstanceOf(UnexpectedRollbackException.class);

        // Reservation must still be ACTIVE and Payment must still be PENDING_EXTERNAL
        assertReservationStatus(dispatched.reservationToken(), "ACTIVE", new BigDecimal("200.0000"), "USD");
        assertAccountBalances(sourceAccountId, new BigDecimal("800.0000"), new BigDecimal("200.0000"));
        assertThat(paymentService.findPayment(dispatched.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.PENDING_EXTERNAL);
    }

    @Test
    void shouldCompleteSettlementWithAlreadyPostedOutcomeWhenMatchingLedgerTransactionAlreadyExists() {
        AccountId sourceAccountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView dispatched = createDispatchedPayment(
                sourceAccountId,
                Money.of("180.0000", "USD"),
                "ROUTE-MATCH-01",
                "EXT-MATCH-01"
        );
        String clearingRef = "CLEARING_MATCH_" + shortId();
        String settlementRef = "ACK-MATCH-" + shortId();

        // Pre-post the exact matching settlement transaction in Ledger while Payment is still PENDING_EXTERNAL
        LedgerPostingResult prePosted = ledgerService.postTransaction(new PostLedgerTransactionCommand(
                SettlementService.postingKeyFor(dispatched.paymentId()),
                USD,
                List.of(
                        new LedgerEntryLine(
                                LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, sourceAccountId.getValue().toString()),
                                EntryDirection.DEBIT,
                                Money.of("180.0000", "USD")
                        ),
                        new LedgerEntryLine(
                                LedgerAccountRef.of(LedgerAccountType.INTERNAL, clearingRef),
                                EntryDirection.CREDIT,
                                Money.of("180.0000", "USD")
                        )
                ),
                settlementRef,
                "Settlement for payment " + dispatched.paymentId().getValue()
        ));
        assertThat(prePosted.outcome()).isEqualTo(PostingOutcome.POSTED);

        SettlementResult result = settlementService.settlePayment(
                new SettlePaymentCommand(dispatched.paymentId(), clearingRef, settlementRef)
        );

        assertThat(result.ledgerTransactionId()).isEqualTo(prePosted.transactionId());
        assertThat(result.ledgerPostingOutcome()).isEqualTo(PostingOutcome.ALREADY_POSTED);
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(result.payment().ledgerTransactionId()).isEqualTo(prePosted.transactionId().getValue());
        assertReservationStatus(dispatched.reservationToken(), "CONSUMED", new BigDecimal("180.0000"), "USD");
        assertAccountBalances(sourceAccountId, new BigDecimal("820.0000"), new BigDecimal("0.0000"));
    }

    // =========================================================================
    // 4. Invalid Payment States Rejected Without Side Effects
    // =========================================================================

    @Test
    void shouldRejectNonPendingExternalPaymentStatesBeforeAnyBankingOrLedgerMutation() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("2000.0000"));
        String clearingRef = "CLEARING_STATES_" + shortId();
        String settlementRef = "ACK-STATES-" + shortId();

        // 1. COMPLIANCE_HOLD
        PaymentView pComplianceHold = paymentService.initiatePayment(new InitiatePaymentCommand(
                accountId,
                PaymentReference.of("PAY-ST-HOLD-" + shortId()),
                new BeneficiaryDetails("Deutsche Telekom AG Sanctioned", "DE89370400440532013000", new BicCode("DEUTDEFFXXX")),
                Money.of("100.0000", "USD")
        ));
        assertInvalidStateForSettlement(pComplianceHold, clearingRef, settlementRef);
        assertReservationStatus(pComplianceHold.reservationToken(), "ACTIVE", new BigDecimal("100.0000"), "USD");

        // 2. ROUTING
        paymentService.approveCompliance(pComplianceHold.paymentId());
        PaymentView pRouting = paymentService.findPayment(pComplianceHold.paymentId()).orElseThrow();
        assertInvalidStateForSettlement(pRouting, clearingRef, settlementRef);
        assertReservationStatus(pRouting.reservationToken(), "ACTIVE", new BigDecimal("100.0000"), "USD");

        // 3. EXECUTING (not yet dispatched to PENDING_EXTERNAL)
        paymentService.commitToExecution(new CommitPaymentExecutionCommand(pRouting.paymentId(), "ROUTE-ST-01"));
        PaymentView pExecuting = paymentService.findPayment(pRouting.paymentId()).orElseThrow();
        assertInvalidStateForSettlement(pExecuting, clearingRef, settlementRef);
        assertReservationStatus(pExecuting.reservationToken(), "ACTIVE", new BigDecimal("100.0000"), "USD");

        // 4. FAILED (from EXECUTING)
        PaymentView pFailed = paymentService.failPayment(new FailPaymentCommand(
                pExecuting.paymentId(),
                PaymentReasonCode.OPERATIONAL_FAILURE,
                "Failed before dispatch"
        ));
        assertInvalidStateForSettlement(pFailed, clearingRef, settlementRef);
        assertReservationStatus(pFailed.reservationToken(), "RELEASED", new BigDecimal("100.0000"), "USD");

        // 5. CANCELLED
        PaymentView pToCancel = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-ST-CANC-" + shortId()), Money.of("100.0000", "USD"))
        );
        PaymentView pCancelled = paymentService.cancelPayment(
                new CancelPaymentCommand(pToCancel.paymentId(), "Customer cancelled")
        );
        assertInvalidStateForSettlement(pCancelled, clearingRef, settlementRef);
        assertReservationStatus(pCancelled.reservationToken(), "RELEASED", new BigDecimal("100.0000"), "USD");

        // 6. REJECTED
        PaymentView pToReject = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-ST-REJ-" + shortId()), Money.of("100.0000", "USD"))
        );
        PaymentView pRejected = paymentService.rejectPayment(new RejectPaymentCommand(
                pToReject.paymentId(),
                PaymentReasonCode.COMPLIANCE_REJECTED,
                "Compliance rejected"
        ));
        assertInvalidStateForSettlement(pRejected, clearingRef, settlementRef);
        assertReservationStatus(pRejected.reservationToken(), "RELEASED", new BigDecimal("100.0000"), "USD");
    }

    // =========================================================================
    // 5. Banking Failure (Frozen Account / Released Reservation) Prevents Ledger & Payment Writes
    // =========================================================================

    @Test
    void shouldPreventLedgerPostingAndPaymentSettlementWhenBankingReservationConsumptionFails() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView dispatched = createDispatchedPayment(
                accountId,
                Money.of("250.0000", "USD"),
                "ROUTE-BANK-FAIL-01",
                "EXT-BANK-FAIL-01"
        );
        String clearingRef = "CLEARING_BANK_FAIL_" + shortId();
        String settlementRef = "ACK-BANK-FAIL-" + shortId();
        SettlePaymentCommand command = new SettlePaymentCommand(dispatched.paymentId(), clearingRef, settlementRef);

        // Case A: Freeze account before settlement -> consumeReservation throws InvalidAccountStateException
        jdbcTemplate.update("UPDATE banking.account SET status = 'FROZEN' WHERE account_id = ?", accountId.getValue());

        assertThatThrownBy(() -> settlementService.settlePayment(command))
                .isInstanceOf(InvalidAccountStateException.class);
        assertThat(countLedgerTransactionsForPostingKey(SettlementService.postingKeyFor(dispatched.paymentId())))
                .isZero();
        assertThat(paymentService.findPayment(dispatched.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.PENDING_EXTERNAL);
        assertReservationStatus(dispatched.reservationToken(), "ACTIVE", new BigDecimal("250.0000"), "USD");

        // Restore account to ACTIVE, then release reservation directly via BankingService to simulate unexpected RELEASED state
        jdbcTemplate.update("UPDATE banking.account SET status = 'ACTIVE' WHERE account_id = ?", accountId.getValue());
        bankingService.releaseReservation(accountId, dispatched.reservationToken());

        // Case B: Reservation is RELEASED -> consumeReservation throws InvalidReservationStateException
        assertThatThrownBy(() -> settlementService.settlePayment(command))
                .isInstanceOf(InvalidReservationStateException.class);
        assertThat(countLedgerTransactionsForPostingKey(SettlementService.postingKeyFor(dispatched.paymentId())))
                .isZero();
        assertThat(paymentService.findPayment(dispatched.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.PENDING_EXTERNAL);
    }

    // =========================================================================
    // 6. Final-Step Failure (recordSettlement Optimistic Lock) Rolls Back Both Banking & Ledger
    // =========================================================================

    @Test
    void shouldRollBackReservationConsumptionAndLedgerPostingWhenFinalPaymentUpdateFailsWithOptimisticLock() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView dispatched = createDispatchedPayment(
                accountId,
                Money.of("225.0000", "USD"),
                "ROUTE-FINAL-FAIL-01",
                "EXT-FINAL-FAIL-01"
        );
        String clearingRef = "CLEARING_FINAL_RB_" + shortId();
        String settlementRef = "ACK-FINAL-RB-" + shortId();

        TransactionTemplate requiresNewTx = new TransactionTemplate(
                transactionManager,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        );

        assertThatThrownBy(() -> requiresNewTx.executeWithoutResult(status -> {
            // 1. Prime current transaction's JPA persistence context with the PENDING_EXTERNAL PaymentEntity
            PaymentView cached = paymentService.findPayment(dispatched.paymentId()).orElseThrow();
            assertThat(cached.status()).isEqualTo(PaymentStatus.PENDING_EXTERNAL);

            // 2. Concurrent transaction bumps PaymentEntity's version in PostgreSQL
            requiresNewTx.executeWithoutResult(innerStatus ->
                    jdbcTemplate.update(
                            "UPDATE payment.payment SET version = version + 1 WHERE payment_id = ?",
                            dispatched.paymentId().getValue()
                    )
            );

            // 3. settlePayment sees the cached PENDING_EXTERNAL PaymentEntity, consumes the Banking reservation,
            //    posts the double-entry Ledger transaction, and updates the stale PaymentEntity ->
            //    at transaction commit/flush, ObjectOptimisticLockingFailureException is thrown!
            settlementService.settlePayment(
                    new SettlePaymentCommand(dispatched.paymentId(), clearingRef, settlementRef)
            );
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Verify ALL THREE modules rolled back atomically:
        // 1. Banking reservation is still ACTIVE and reserved_amount is still 225.0000
        assertReservationStatus(dispatched.reservationToken(), "ACTIVE", new BigDecimal("225.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("775.0000"), new BigDecimal("225.0000"));

        // 2. Ledger has zero committed rows for PAYMENT_SETTLEMENT:{paymentId}
        assertThat(countLedgerTransactionsForPostingKey(SettlementService.postingKeyFor(dispatched.paymentId())))
                .isZero();

        // 3. Payment is still PENDING_EXTERNAL
        PaymentView afterRollback = paymentService.findPayment(dispatched.paymentId()).orElseThrow();
        assertThat(afterRollback.status()).isEqualTo(PaymentStatus.PENDING_EXTERNAL);
        assertThat(afterRollback.ledgerTransactionId()).isNull();

        // 4. Subsequent clean retry succeeds end-to-end!
        SettlementResult retrySuccess = settlementService.settlePayment(
                new SettlePaymentCommand(dispatched.paymentId(), clearingRef, settlementRef)
        );
        assertThat(retrySuccess.ledgerPostingOutcome()).isEqualTo(PostingOutcome.POSTED);
        assertThat(retrySuccess.payment().status()).isEqualTo(PaymentStatus.SETTLED);
        assertReservationStatus(dispatched.reservationToken(), "CONSUMED", new BigDecimal("225.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("775.0000"), new BigDecimal("0.0000"));
        assertThat(countLedgerTransactionsForPostingKey(SettlementService.postingKeyFor(dispatched.paymentId())))
                .isEqualTo(1L);
    }

    // =========================================================================
    // 7. Concurrent Settlement Race (8 Threads)
    // =========================================================================

    @Test
    void shouldPreserveSingleLedgerPostingAndSingleConsumedReservationUnderConcurrentSettlementCalls() throws Exception {
        int threads = 8;
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView dispatched = createDispatchedPayment(
                accountId,
                Money.of("310.0000", "USD"),
                "ROUTE-CONC-SETTLE-01",
                "EXT-CONC-SETTLE-01"
        );
        String clearingRef = "CLEARING_CONC_" + shortId();
        String settlementRef = "ACK-CONC-" + shortId();
        SettlePaymentCommand sharedCmd = new SettlePaymentCommand(dispatched.paymentId(), clearingRef, settlementRef);

        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<SettlementResult> successes = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> transientContentionFailures = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    readyLatch.countDown();
                    try {
                        assertThat(startLatch.await(10, TimeUnit.SECONDS)).isTrue();
                        successes.add(settlementService.settlePayment(sharedCmd));
                    } catch (ObjectOptimisticLockingFailureException ex) {
                        transientContentionFailures.add(ex);
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

            assertThat(successes).isNotEmpty();
            assertThat(successes.size() + transientContentionFailures.size()).isEqualTo(threads);

            Set<LedgerTransactionId> distinctLedgerTxIds = Set.copyOf(
                    successes.stream().map(SettlementResult::ledgerTransactionId).toList()
            );
            assertThat(distinctLedgerTxIds).hasSize(1);

            // Follow-up retry after contention settles must succeed idempotently with ALREADY_POSTED
            SettlementResult postRaceRetry = settlementService.settlePayment(sharedCmd);
            assertThat(postRaceRetry.ledgerPostingOutcome()).isEqualTo(PostingOutcome.ALREADY_POSTED);
            assertThat(postRaceRetry.ledgerTransactionId()).isEqualTo(distinctLedgerTxIds.iterator().next());
            assertThat(postRaceRetry.payment().status()).isEqualTo(PaymentStatus.SETTLED);

            // Verify exactly 1 ledger transaction, 1 consumed reservation, and exact balances
            assertThat(countLedgerTransactionsForPostingKey(SettlementService.postingKeyFor(dispatched.paymentId())))
                    .isEqualTo(1L);
            assertReservationStatus(dispatched.reservationToken(), "CONSUMED", new BigDecimal("310.0000"), "USD");
            assertAccountBalances(accountId, new BigDecimal("690.0000"), new BigDecimal("0.0000"));
        } finally {
            pool.shutdownNow();
        }
    }

    // =========================================================================
    // 8. Command & Input Validation
    // =========================================================================

    @Test
    void shouldValidateSettlePaymentCommandAndRejectMissingPayments() {
        PaymentId paymentId = PaymentId.generate();

        // 1. Null command / null paymentId / null postingKeyFor
        assertThatThrownBy(() -> settlementService.settlePayment(null))
                .isInstanceOf(SettlementValidationException.class);
        assertThatThrownBy(() -> SettlementService.postingKeyFor(null))
                .isInstanceOf(SettlementValidationException.class);
        assertThatThrownBy(() -> new SettlePaymentCommand(null, "CLEARING_USD", "ACK-1"))
                .isInstanceOf(SettlementValidationException.class);

        // 2. clearingAccountReference validation (delegated to LedgerAccountRef rules)
        assertThatThrownBy(() -> new SettlePaymentCommand(paymentId, null, "ACK-1"))
                .isInstanceOf(SettlementValidationException.class);
        assertThatThrownBy(() -> new SettlePaymentCommand(paymentId, "   ", "ACK-1"))
                .isInstanceOf(SettlementValidationException.class);
        assertThatThrownBy(() -> new SettlePaymentCommand(paymentId, " UNTRIMMED ", "ACK-1"))
                .isInstanceOf(SettlementValidationException.class);
        assertThatThrownBy(() -> new SettlePaymentCommand(paymentId, "A".repeat(101), "ACK-1"))
                .isInstanceOf(SettlementValidationException.class);
        assertThatThrownBy(() -> new SettlePaymentCommand(paymentId, "CLEAR\u0007ING", "ACK-1"))
                .isInstanceOf(SettlementValidationException.class);

        // 3. settlementReference validation
        assertThatThrownBy(() -> new SettlePaymentCommand(paymentId, "CLEARING_USD", null))
                .isInstanceOf(SettlementValidationException.class);
        assertThatThrownBy(() -> new SettlePaymentCommand(paymentId, "CLEARING_USD", "   "))
                .isInstanceOf(SettlementValidationException.class);
        assertThatThrownBy(() -> new SettlePaymentCommand(paymentId, "CLEARING_USD", "S".repeat(256)))
                .isInstanceOf(SettlementValidationException.class);
        assertThatThrownBy(() -> new SettlePaymentCommand(paymentId, "CLEARING_USD", "ACK-\n-01"))
                .isInstanceOf(SettlementValidationException.class);

        // 4. Missing PaymentId throws PaymentNotFoundException
        assertThatThrownBy(() -> settlementService.settlePayment(
                new SettlePaymentCommand(paymentId, "CLEARING_USD", "ACK-MISSING-01")
        )).isInstanceOf(PaymentNotFoundException.class);
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private PaymentView createDispatchedPayment(
            AccountId sourceAccountId,
            Money amount,
            String routeReference,
            String externalDispatchReference) {
        PaymentView initiated = paymentService.initiatePayment(
                sampleInitiateCommand(
                        sourceAccountId,
                        PaymentReference.of("PAY-SETTLE-" + shortId()),
                        amount
                )
        );
        paymentService.approveCompliance(initiated.paymentId());
        paymentService.commitToExecution(new CommitPaymentExecutionCommand(initiated.paymentId(), routeReference));
        return paymentService.markDispatched(
                new MarkPaymentDispatchedCommand(initiated.paymentId(), externalDispatchReference)
        );
    }

    private void assertInvalidStateForSettlement(
            PaymentView payment,
            String clearingRef,
            String settlementRef) {
        assertThatThrownBy(() -> settlementService.settlePayment(
                new SettlePaymentCommand(payment.paymentId(), clearingRef, settlementRef)
        )).isInstanceOf(InvalidPaymentStateException.class);
        assertThat(countLedgerTransactionsForPostingKey(SettlementService.postingKeyFor(payment.paymentId())))
                .isZero();
        assertThat(paymentService.findPayment(payment.paymentId()).orElseThrow().status())
                .isEqualTo(payment.status());
    }

    private AccountId createAccount(String currency, String status, BigDecimal availableBalance) {
        UUID customerId = UUID.randomUUID();
        AccountId accountId = AccountId.generate();
        jdbcTemplate.update("INSERT INTO banking.customer (customer_id) VALUES (?)", customerId);
        jdbcTemplate.update(
                "INSERT INTO banking.account (account_id, customer_id, currency, status, available_balance, reserved_amount, version) "
                        + "VALUES (?, ?, ?, ?, ?, 0.0000, 0)",
                accountId.getValue(),
                customerId,
                currency,
                status,
                availableBalance
        );
        return accountId;
    }

    private static InitiatePaymentCommand sampleInitiateCommand(
            AccountId sourceAccountId,
            PaymentReference paymentReference,
            Money amount) {
        return new InitiatePaymentCommand(
                sourceAccountId,
                paymentReference,
                new BeneficiaryDetails(
                        "Deutsche Telekom AG",
                        "DE89370400440532013000",
                        new BicCode("DEUTDEFFXXX")
                ),
                amount
        );
    }

    private void assertAccountBalances(
            AccountId accountId,
            BigDecimal expectedAvailable,
            BigDecimal expectedReserved) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT available_balance, reserved_amount FROM banking.account WHERE account_id = ?",
                accountId.getValue()
        );
        assertThat((BigDecimal) row.get("available_balance")).isEqualByComparingTo(expectedAvailable);
        assertThat((BigDecimal) row.get("reserved_amount")).isEqualByComparingTo(expectedReserved);
    }

    private void assertReservationStatus(
            ReservationToken token,
            String expectedStatus,
            BigDecimal expectedAmount,
            String expectedCurrency) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, amount, currency FROM banking.fund_reservation WHERE reservation_token = ?",
                token.getValue()
        );
        assertThat((String) row.get("status")).isEqualTo(expectedStatus);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(expectedAmount);
        assertThat((String) row.get("currency")).isEqualTo(expectedCurrency);
    }

    private long countLedgerTransactionsForPostingKey(LedgerPostingKey postingKey) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger.ledger_transaction WHERE posting_key = ?",
                Long.class,
                postingKey.getValue()
        );
        return count != null ? count : 0L;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
