package com.crosspaynet.payment;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.AccountNotFoundException;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
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
import com.crosspaynet.payment.contracts.PaymentReferenceConflictException;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentValidationException;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.payment.contracts.RejectPaymentCommand;
import com.crosspaynet.payment.domain.PersistedPaymentStateException;
import com.crosspaynet.payment.settlement.PaymentSettlementPort;
import com.crosspaynet.payment.settlement.RecordPaymentSettlementCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class PaymentServiceIntegrationTest {

    private static final Currency USD = new Currency("USD");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentSettlementPort paymentSettlementPort;

    @Autowired
    private BankingService bankingService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================================
    // 1. Successful Initiation & Synchronous Fund Reservation
    // =========================================================================

    @Test
    void shouldInitiatePaymentReserveFundsOnceAndEnterComplianceHold() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentReference reference = PaymentReference.of("PAY-INIT-OK-" + shortId());
        InitiatePaymentCommand command = sampleInitiateCommand(
                accountId,
                reference,
                Money.of("250.5000", "USD")
        );

        PaymentView created = paymentService.initiatePayment(command);

        assertThat(created.paymentId()).isNotNull();
        assertThat(created.status()).isEqualTo(PaymentStatus.COMPLIANCE_HOLD);
        assertThat(created.reservationToken()).isNotNull();
        assertThat(created.instruction().sourceAccountId()).isEqualTo(accountId);
        assertThat(created.instruction().paymentReference()).isEqualTo(reference);
        assertThat(created.instruction().amount()).isEqualTo(Money.of("250.5000", "USD"));
        assertThat(created.routeReference()).isNull();
        assertThat(created.externalDispatchReference()).isNull();
        assertThat(created.ledgerTransactionId()).isNull();
        assertThat(created.statusReasonCode()).isNull();
        assertThat(created.statusReasonDetail()).isNull();
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isAfterOrEqualTo(created.createdAt());

        // Verify persisted Payment via both query contracts
        assertThat(paymentService.findPayment(created.paymentId())).contains(created);
        assertThat(paymentService.findBySourceAccountAndReference(accountId, reference)).contains(created);

        // Verify persisted Banking state
        assertAccountBalances(accountId, new BigDecimal("749.5000"), new BigDecimal("250.5000"));
        assertReservationStatus(created.reservationToken(), "ACTIVE", new BigDecimal("250.5000"), "USD");
        assertReservationCountForAccount(accountId, 1);
    }

    // =========================================================================
    // 2. Idempotent Retries & Conflicting Instruction Fingerprints
    // =========================================================================

    @Test
    void shouldReturnExistingPaymentOnIdenticalRetryAndRejectConflictingFingerprintsWithoutDuplicateReservations() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentReference reference = PaymentReference.of("PAY-IDEMP-" + shortId());
        BeneficiaryDetails beneficiary = new BeneficiaryDetails(
                "Siemens AG",
                "DE89370400440532013000",
                new BicCode("DEUTDEFFXXX")
        );
        InitiatePaymentCommand originalCmd = new InitiatePaymentCommand(
                accountId,
                reference,
                beneficiary,
                Money.of("300.0000", "USD")
        );

        PaymentView first = paymentService.initiatePayment(originalCmd);
        assertThat(first.status()).isEqualTo(PaymentStatus.COMPLIANCE_HOLD);

        // 1. Exact retry (including normalized trailing zeros 300.00 vs 300.0000 and trimmed whitespace)
        InitiatePaymentCommand equivalentCmd = new InitiatePaymentCommand(
                accountId,
                reference,
                new BeneficiaryDetails("  Siemens AG  ", "  DE89370400440532013000  ", new BicCode("  DEUTDEFFXXX  ")),
                Money.of("300.00", "USD")
        );
        PaymentView retry = paymentService.initiatePayment(equivalentCmd);
        assertThat(retry).isEqualTo(first);

        // 2. Conflicting amount
        InitiatePaymentCommand diffAmountCmd = new InitiatePaymentCommand(
                accountId,
                reference,
                beneficiary,
                Money.of("301.0000", "USD")
        );
        assertThatThrownBy(() -> paymentService.initiatePayment(diffAmountCmd))
                .isInstanceOf(PaymentReferenceConflictException.class);

        // 3. Conflicting currency
        InitiatePaymentCommand diffCurrencyCmd = new InitiatePaymentCommand(
                accountId,
                reference,
                beneficiary,
                Money.of("300.0000", "EUR")
        );
        assertThatThrownBy(() -> paymentService.initiatePayment(diffCurrencyCmd))
                .isInstanceOf(PaymentReferenceConflictException.class);

        // 4. Conflicting beneficiary name
        InitiatePaymentCommand diffBeneficiaryNameCmd = new InitiatePaymentCommand(
                accountId,
                reference,
                new BeneficiaryDetails("Different Beneficiary", "DE89370400440532013000", new BicCode("DEUTDEFFXXX")),
                Money.of("300.0000", "USD")
        );
        assertThatThrownBy(() -> paymentService.initiatePayment(diffBeneficiaryNameCmd))
                .isInstanceOf(PaymentReferenceConflictException.class);

        // 5. Conflicting beneficiary account number
        InitiatePaymentCommand diffBeneficiaryAcctCmd = new InitiatePaymentCommand(
                accountId,
                reference,
                new BeneficiaryDetails("Siemens AG", "DE89370400440532019999", new BicCode("DEUTDEFFXXX")),
                Money.of("300.0000", "USD")
        );
        assertThatThrownBy(() -> paymentService.initiatePayment(diffBeneficiaryAcctCmd))
                .isInstanceOf(PaymentReferenceConflictException.class);

        // 6. Conflicting beneficiary BIC
        InitiatePaymentCommand diffBicCmd = new InitiatePaymentCommand(
                accountId,
                reference,
                new BeneficiaryDetails("Siemens AG", "DE89370400440532013000", new BicCode("COBADEFFXXX")),
                Money.of("300.0000", "USD")
        );
        assertThatThrownBy(() -> paymentService.initiatePayment(diffBicCmd))
                .isInstanceOf(PaymentReferenceConflictException.class);

        // Verify Payment Reference Conflict inside a shared caller transaction does not mark it rollback-only
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            assertThatThrownBy(() -> paymentService.initiatePayment(diffAmountCmd))
                    .isInstanceOf(PaymentReferenceConflictException.class);
            assertThat(status.isRollbackOnly()).isFalse();
        });

        // Verify only 1 reservation exists and balances were deducted only once
        assertAccountBalances(accountId, new BigDecimal("700.0000"), new BigDecimal("300.0000"));
        assertReservationCountForAccount(accountId, 1);
    }

    // =========================================================================
    // 3. Business Reservation Failures -> Committed REJECTED Audit Trail
    // =========================================================================

    @Test
    void shouldPersistRejectedPaymentForInsufficientFundsInvalidAccountStateAndCurrencyMismatch() {
        AccountId activeUsdAccount = createAccount("USD", "ACTIVE", new BigDecimal("100.0000"));
        AccountId frozenUsdAccount = createAccount("USD", "FROZEN", new BigDecimal("500.0000"));
        AccountId closedUsdAccount = createAccount("USD", "CLOSED", new BigDecimal("0.0000"));

        // 1. Insufficient funds -> REJECTED (INSUFFICIENT_FUNDS, reservationToken == null)
        PaymentReference insuffRef = PaymentReference.of("PAY-REJ-INSUFF-" + shortId());
        InitiatePaymentCommand insuffCmd = sampleInitiateCommand(
                activeUsdAccount,
                insuffRef,
                Money.of("250.0000", "USD")
        );
        PaymentView rejectedInsuff = paymentService.initiatePayment(insuffCmd);
        assertThat(rejectedInsuff.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(rejectedInsuff.reservationToken()).isNull();
        assertThat(rejectedInsuff.statusReasonCode()).isEqualTo(PaymentReasonCode.INSUFFICIENT_FUNDS);
        assertThat(rejectedInsuff.statusReasonDetail()).isNotBlank();
        assertThat(paymentService.findPayment(rejectedInsuff.paymentId())).contains(rejectedInsuff);
        assertAccountBalances(activeUsdAccount, new BigDecimal("100.0000"), new BigDecimal("0.0000"));
        assertReservationCountForAccount(activeUsdAccount, 0);

        // Idempotent retry of REJECTED payment returns the exact same REJECTED record
        assertThat(paymentService.initiatePayment(insuffCmd)).isEqualTo(rejectedInsuff);

        // 2a. Frozen account -> REJECTED (INVALID_ACCOUNT_STATE, reservationToken == null)
        PaymentReference frozenRef = PaymentReference.of("PAY-REJ-FROZEN-" + shortId());
        InitiatePaymentCommand frozenCmd = sampleInitiateCommand(
                frozenUsdAccount,
                frozenRef,
                Money.of("50.0000", "USD")
        );
        PaymentView rejectedFrozen = paymentService.initiatePayment(frozenCmd);
        assertThat(rejectedFrozen.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(rejectedFrozen.reservationToken()).isNull();
        assertThat(rejectedFrozen.statusReasonCode()).isEqualTo(PaymentReasonCode.INVALID_ACCOUNT_STATE);
        assertThat(rejectedFrozen.statusReasonDetail()).isNotBlank();
        assertThat(paymentService.findPayment(rejectedFrozen.paymentId())).contains(rejectedFrozen);
        assertAccountBalances(frozenUsdAccount, new BigDecimal("500.0000"), new BigDecimal("0.0000"));
        assertReservationCountForAccount(frozenUsdAccount, 0);

        // 2b. Closed account -> REJECTED (INVALID_ACCOUNT_STATE, reservationToken == null)
        PaymentReference closedRef = PaymentReference.of("PAY-REJ-CLOSED-" + shortId());
        InitiatePaymentCommand closedCmd = sampleInitiateCommand(
                closedUsdAccount,
                closedRef,
                Money.of("10.0000", "USD")
        );
        PaymentView rejectedClosed = paymentService.initiatePayment(closedCmd);
        assertThat(rejectedClosed.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(rejectedClosed.reservationToken()).isNull();
        assertThat(rejectedClosed.statusReasonCode()).isEqualTo(PaymentReasonCode.INVALID_ACCOUNT_STATE);
        assertThat(paymentService.findPayment(rejectedClosed.paymentId())).contains(rejectedClosed);

        // 3. Currency mismatch -> REJECTED (CURRENCY_MISMATCH, reservationToken == null)
        PaymentReference currRef = PaymentReference.of("PAY-REJ-CURR-" + shortId());
        InitiatePaymentCommand currCmd = sampleInitiateCommand(
                activeUsdAccount,
                currRef,
                Money.of("25.0000", "EUR")
        );
        PaymentView rejectedCurr = paymentService.initiatePayment(currCmd);
        assertThat(rejectedCurr.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(rejectedCurr.reservationToken()).isNull();
        assertThat(rejectedCurr.statusReasonCode()).isEqualTo(PaymentReasonCode.CURRENCY_MISMATCH);
        assertThat(rejectedCurr.statusReasonDetail()).isNotBlank();
        assertThat(paymentService.findPayment(rejectedCurr.paymentId())).contains(rejectedCurr);
        assertAccountBalances(activeUsdAccount, new BigDecimal("100.0000"), new BigDecimal("0.0000"));
        assertReservationCountForAccount(activeUsdAccount, 0);
    }

    // =========================================================================
    // 4. Account-Not-Found & Unexpected Failures Roll Back the Claim (No VALIDATING Resting State)
    // =========================================================================

    @Test
    void shouldRollBackClaimOnAccountNotFoundAndUnexpectedFailureSoValidatingNeverRemainsCommitted() {
        // 1. AccountNotFoundException standalone
        AccountId missingAccountId = AccountId.generate();
        PaymentReference missingRef = PaymentReference.of("PAY-MISSING-ACC-" + shortId());
        InitiatePaymentCommand missingAccountCmd = sampleInitiateCommand(
                missingAccountId,
                missingRef,
                Money.of("100.0000", "USD")
        );

        assertThatThrownBy(() -> paymentService.initiatePayment(missingAccountCmd))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(paymentService.findBySourceAccountAndReference(missingAccountId, missingRef)).isEmpty();
        assertZeroCommittedValidatingPayments();

        // 2. AccountNotFoundException caught inside an outer caller transaction must still force rollback
        PaymentReference caughtMissingRef = PaymentReference.of("PAY-CAUGHT-MISSING-" + shortId());
        InitiatePaymentCommand caughtMissingCmd = sampleInitiateCommand(
                missingAccountId,
                caughtMissingRef,
                Money.of("100.0000", "USD")
        );
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            assertThatThrownBy(() -> paymentService.initiatePayment(caughtMissingCmd))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThat(status.isRollbackOnly()).isTrue();
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(paymentService.findBySourceAccountAndReference(missingAccountId, caughtMissingRef)).isEmpty();

        // 3. Corrupted persisted Banking account state (reserved_amount mismatch) rolls back the claim
        AccountId corruptedAccountId = AccountId.generate();
        UUID customerId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO banking.customer (customer_id) VALUES (?)", customerId);
        jdbcTemplate.update(
                "INSERT INTO banking.account (account_id, customer_id, currency, status, available_balance, reserved_amount, version) "
                        + "VALUES (?, ?, 'USD', 'ACTIVE', 500.0000, 100.0000, 0)",
                corruptedAccountId.getValue(),
                customerId
        );
        PaymentReference corruptRef = PaymentReference.of("PAY-CORRUPT-BANK-" + shortId());
        InitiatePaymentCommand corruptCmd = sampleInitiateCommand(
                corruptedAccountId,
                corruptRef,
                Money.of("50.0000", "USD")
        );

        assertThatThrownBy(() -> paymentService.initiatePayment(corruptCmd))
                .isInstanceOf(RuntimeException.class);
        assertThat(paymentService.findBySourceAccountAndReference(corruptedAccountId, corruptRef)).isEmpty();
        assertZeroCommittedValidatingPayments();
    }

    // =========================================================================
    // 5. Lifecycle Transitions (ROUTING, EXECUTING, PENDING_EXTERNAL) & Idempotent Retries
    // =========================================================================

    @Test
    void shouldTransitionThroughRoutingExecutingAndPendingExternalWithIdempotentRetries() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentReference reference = PaymentReference.of("PAY-LIFE-" + shortId());
        PaymentView initiated = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, reference, Money.of("400.0000", "USD"))
        );
        PaymentId paymentId = initiated.paymentId();
        assertThat(initiated.status()).isEqualTo(PaymentStatus.COMPLIANCE_HOLD);

        // Invalid transition check: cannot commit to execution directly from COMPLIANCE_HOLD
        assertThatThrownBy(() -> paymentService.commitToExecution(
                new CommitPaymentExecutionCommand(paymentId, "ROUTE-EARLY")))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(paymentService.findPayment(paymentId).orElseThrow().status())
                .isEqualTo(PaymentStatus.COMPLIANCE_HOLD);

        // 1. COMPLIANCE_HOLD -> ROUTING
        PaymentView routing = paymentService.approveCompliance(paymentId);
        assertThat(routing.status()).isEqualTo(PaymentStatus.ROUTING);
        assertThat(routing.reservationToken()).isEqualTo(initiated.reservationToken());
        // Idempotent retry in ROUTING
        assertThat(paymentService.approveCompliance(paymentId)).isEqualTo(routing);

        // Invalid transition check: cannot markDispatched directly from ROUTING
        assertThatThrownBy(() -> paymentService.markDispatched(
                new MarkPaymentDispatchedCommand(paymentId, "EXT-EARLY")))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(paymentService.findPayment(paymentId).orElseThrow().status())
                .isEqualTo(PaymentStatus.ROUTING);

        // 2. ROUTING -> EXECUTING (Financial Commitment Point)
        CommitPaymentExecutionCommand commitCmd = new CommitPaymentExecutionCommand(paymentId, "ROUTE-SWIFT-DE-01");
        PaymentView executing = paymentService.commitToExecution(commitCmd);
        assertThat(executing.status()).isEqualTo(PaymentStatus.EXECUTING);
        assertThat(executing.routeReference()).isEqualTo("ROUTE-SWIFT-DE-01");
        // Idempotent retry in EXECUTING with same routeReference
        assertThat(paymentService.commitToExecution(commitCmd)).isEqualTo(executing);
        // Conflicting routeReference in EXECUTING is rejected
        assertThatThrownBy(() -> paymentService.commitToExecution(
                new CommitPaymentExecutionCommand(paymentId, "ROUTE-DIFFERENT")))
                .isInstanceOf(InvalidPaymentStateException.class);

        // Invalid transition check: EXECUTING -> SETTLED is forbidden (must pass through PENDING_EXTERNAL)
        assertThatThrownBy(() -> paymentSettlementPort.recordSettlement(
                new RecordPaymentSettlementCommand(paymentId, UUID.randomUUID())))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(paymentService.findPayment(paymentId).orElseThrow().status())
                .isEqualTo(PaymentStatus.EXECUTING);

        // 3. EXECUTING -> PENDING_EXTERNAL
        MarkPaymentDispatchedCommand dispatchCmd = new MarkPaymentDispatchedCommand(paymentId, "SWIFT-MT103-0001");
        PaymentView pendingExternal = paymentService.markDispatched(dispatchCmd);
        assertThat(pendingExternal.status()).isEqualTo(PaymentStatus.PENDING_EXTERNAL);
        assertThat(pendingExternal.routeReference()).isEqualTo("ROUTE-SWIFT-DE-01");
        assertThat(pendingExternal.externalDispatchReference()).isEqualTo("SWIFT-MT103-0001");
        // Idempotent retry in PENDING_EXTERNAL with same externalDispatchReference
        assertThat(paymentService.markDispatched(dispatchCmd)).isEqualTo(pendingExternal);
        // Conflicting externalDispatchReference in PENDING_EXTERNAL is rejected
        assertThatThrownBy(() -> paymentService.markDispatched(
                new MarkPaymentDispatchedCommand(paymentId, "SWIFT-MT103-DIFFERENT")))
                .isInstanceOf(InvalidPaymentStateException.class);

        // Reservation remains ACTIVE throughout routing, execution, and pending external
        assertAccountBalances(accountId, new BigDecimal("600.0000"), new BigDecimal("400.0000"));
        assertReservationStatus(initiated.reservationToken(), "ACTIVE", new BigDecimal("400.0000"), "USD");
    }

    // =========================================================================
    // 6. Pre-Commitment Cancellation & Rejection Release Reservations Only When Permitted
    // =========================================================================

    @Test
    void shouldReleaseReservationOnValidCancelAndRejectAndPreserveBankingStateOnInvalidCommands() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));

        // Case A: Cancel from COMPLIANCE_HOLD releases reservation and transitions to CANCELLED
        PaymentView pCancelHold = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-CANC-HOLD-" + shortId()), Money.of("150.0000", "USD"))
        );
        assertAccountBalances(accountId, new BigDecimal("850.0000"), new BigDecimal("150.0000"));

        PaymentView cancelledHold = paymentService.cancelPayment(
                new CancelPaymentCommand(pCancelHold.paymentId(), "Customer cancelled in compliance hold")
        );
        assertThat(cancelledHold.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(cancelledHold.statusReasonCode()).isEqualTo(PaymentReasonCode.CUSTOMER_CANCELLED);
        assertThat(cancelledHold.statusReasonDetail()).isEqualTo("Customer cancelled in compliance hold");
        assertThat(cancelledHold.reservationToken()).isEqualTo(pCancelHold.reservationToken());
        assertAccountBalances(accountId, new BigDecimal("1000.0000"), new BigDecimal("0.0000"));
        assertReservationStatus(pCancelHold.reservationToken(), "RELEASED", new BigDecimal("150.0000"), "USD");
        // Idempotent retry
        assertThat(paymentService.cancelPayment(new CancelPaymentCommand(pCancelHold.paymentId(), "Retry")))
                .isEqualTo(cancelledHold);

        // Case B: Cancel from ROUTING releases reservation and transitions to CANCELLED
        PaymentView pCancelRouting = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-CANC-ROUT-" + shortId()), Money.of("200.0000", "USD"))
        );
        paymentService.approveCompliance(pCancelRouting.paymentId());
        PaymentView cancelledRouting = paymentService.cancelPayment(
                new CancelPaymentCommand(pCancelRouting.paymentId(), null)
        );
        assertThat(cancelledRouting.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(cancelledRouting.statusReasonCode()).isEqualTo(PaymentReasonCode.CUSTOMER_CANCELLED);
        assertThat(cancelledRouting.statusReasonDetail()).isNull();
        assertAccountBalances(accountId, new BigDecimal("1000.0000"), new BigDecimal("0.0000"));
        assertReservationStatus(pCancelRouting.reservationToken(), "RELEASED", new BigDecimal("200.0000"), "USD");

        // Case C: Reject from COMPLIANCE_HOLD (COMPLIANCE_REJECTED) releases reservation
        PaymentView pRejectHold = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-REJ-HOLD-" + shortId()), Money.of("175.0000", "USD"))
        );
        PaymentView rejectedHold = paymentService.rejectPayment(new RejectPaymentCommand(
                pRejectHold.paymentId(),
                PaymentReasonCode.COMPLIANCE_REJECTED,
                "Sanctions screening hit"
        ));
        assertThat(rejectedHold.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(rejectedHold.statusReasonCode()).isEqualTo(PaymentReasonCode.COMPLIANCE_REJECTED);
        assertThat(rejectedHold.statusReasonDetail()).isEqualTo("Sanctions screening hit");
        assertThat(rejectedHold.reservationToken()).isEqualTo(pRejectHold.reservationToken());
        assertAccountBalances(accountId, new BigDecimal("1000.0000"), new BigDecimal("0.0000"));
        assertReservationStatus(pRejectHold.reservationToken(), "RELEASED", new BigDecimal("175.0000"), "USD");
        // Idempotent retry with same reason code
        assertThat(paymentService.rejectPayment(new RejectPaymentCommand(
                pRejectHold.paymentId(),
                PaymentReasonCode.COMPLIANCE_REJECTED,
                "Sanctions screening hit"
        ))).isEqualTo(rejectedHold);
        // Conflicting reason code on already-REJECTED payment throws InvalidPaymentStateException
        assertThatThrownBy(() -> paymentService.rejectPayment(new RejectPaymentCommand(
                pRejectHold.paymentId(),
                PaymentReasonCode.NO_ROUTE_AVAILABLE,
                "Different reason"
        ))).isInstanceOf(InvalidPaymentStateException.class);

        // Case D: Reject from ROUTING (NO_ROUTE_AVAILABLE) releases reservation even if account was subsequently FROZEN
        PaymentView pRejectRouting = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-REJ-ROUT-" + shortId()), Money.of("125.0000", "USD"))
        );
        paymentService.approveCompliance(pRejectRouting.paymentId());
        jdbcTemplate.update("UPDATE banking.account SET status = 'FROZEN' WHERE account_id = ?", accountId.getValue());

        PaymentView rejectedRouting = paymentService.rejectPayment(new RejectPaymentCommand(
                pRejectRouting.paymentId(),
                PaymentReasonCode.NO_ROUTE_AVAILABLE,
                "No correspondent bank for BIC"
        ));
        assertThat(rejectedRouting.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(rejectedRouting.statusReasonCode()).isEqualTo(PaymentReasonCode.NO_ROUTE_AVAILABLE);
        assertAccountBalances(accountId, new BigDecimal("1000.0000"), new BigDecimal("0.0000"));
        assertReservationStatus(pRejectRouting.reservationToken(), "RELEASED", new BigDecimal("125.0000"), "USD");

        // Restore account to ACTIVE for subsequent checks
        jdbcTemplate.update("UPDATE banking.account SET status = 'ACTIVE' WHERE account_id = ?", accountId.getValue());

        // Case E: Invalid cancel/reject after Financial Commitment Point (EXECUTING & PENDING_EXTERNAL)
        // must NOT release reservation or alter Banking state!
        PaymentView pCommitted = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-COMMITTED-" + shortId()), Money.of("300.0000", "USD"))
        );
        paymentService.approveCompliance(pCommitted.paymentId());
        paymentService.commitToExecution(new CommitPaymentExecutionCommand(pCommitted.paymentId(), "ROUTE-COMMITTED-1"));

        assertThatThrownBy(() -> paymentService.cancelPayment(
                new CancelPaymentCommand(pCommitted.paymentId(), "Too late to cancel")))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThatThrownBy(() -> paymentService.rejectPayment(
                new RejectPaymentCommand(pCommitted.paymentId(), PaymentReasonCode.NO_ROUTE_AVAILABLE, "Too late to reject")))
                .isInstanceOf(InvalidPaymentStateException.class);

        // Reservation must still be ACTIVE and balances still reserved!
        assertAccountBalances(accountId, new BigDecimal("700.0000"), new BigDecimal("300.0000"));
        assertReservationStatus(pCommitted.reservationToken(), "ACTIVE", new BigDecimal("300.0000"), "USD");
        assertThat(paymentService.findPayment(pCommitted.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.EXECUTING);
    }

    // =========================================================================
    // 7. Confirmed Post-Commitment Failures Release Reservations Atomically With FAILED
    // =========================================================================

    @Test
    void shouldReleaseReservationAtomicallyOnConfirmedPostCommitmentFailureAndRejectInvalidFailCommands() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));

        // 1. Pre-commitment payment in ROUTING cannot call failPayment; reservation remains ACTIVE
        PaymentView pRouting = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-FAIL-PRE-" + shortId()), Money.of("100.0000", "USD"))
        );
        paymentService.approveCompliance(pRouting.paymentId());

        assertThatThrownBy(() -> paymentService.failPayment(new FailPaymentCommand(
                pRouting.paymentId(),
                PaymentReasonCode.OPERATIONAL_FAILURE,
                "Cannot fail in ROUTING"
        ))).isInstanceOf(InvalidPaymentStateException.class);
        assertAccountBalances(accountId, new BigDecimal("900.0000"), new BigDecimal("100.0000"));
        assertReservationStatus(pRouting.reservationToken(), "ACTIVE", new BigDecimal("100.0000"), "USD");

        // 2. Confirmed failure in EXECUTING (before dispatch) releases reservation and commits FAILED
        paymentService.commitToExecution(new CommitPaymentExecutionCommand(pRouting.paymentId(), "ROUTE-EXEC-FAIL"));
        PaymentView failedInExecuting = paymentService.failPayment(new FailPaymentCommand(
                pRouting.paymentId(),
                PaymentReasonCode.OPERATIONAL_FAILURE,
                "Unrecoverable gateway signing error"
        ));
        assertThat(failedInExecuting.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failedInExecuting.routeReference()).isEqualTo("ROUTE-EXEC-FAIL");
        assertThat(failedInExecuting.externalDispatchReference()).isNull();
        assertThat(failedInExecuting.statusReasonCode()).isEqualTo(PaymentReasonCode.OPERATIONAL_FAILURE);
        assertThat(failedInExecuting.statusReasonDetail()).isEqualTo("Unrecoverable gateway signing error");
        assertAccountBalances(accountId, new BigDecimal("1000.0000"), new BigDecimal("0.0000"));
        assertReservationStatus(pRouting.reservationToken(), "RELEASED", new BigDecimal("100.0000"), "USD");
        // Idempotent retry
        assertThat(paymentService.failPayment(new FailPaymentCommand(
                pRouting.paymentId(),
                PaymentReasonCode.OPERATIONAL_FAILURE,
                "Retry"
        ))).isEqualTo(failedInExecuting);

        // 3. Confirmed failure in PENDING_EXTERNAL (after dispatch) releases reservation and commits FAILED
        PaymentView pDispatched = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-FAIL-DISP-" + shortId()), Money.of("220.0000", "USD"))
        );
        paymentService.approveCompliance(pDispatched.paymentId());
        paymentService.commitToExecution(new CommitPaymentExecutionCommand(pDispatched.paymentId(), "ROUTE-DISP-FAIL"));
        paymentService.markDispatched(new MarkPaymentDispatchedCommand(pDispatched.paymentId(), "EXT-DISP-NACK-1"));

        PaymentView failedInPending = paymentService.failPayment(new FailPaymentCommand(
                pDispatched.paymentId(),
                PaymentReasonCode.EXTERNAL_NETWORK_REJECTED,
                "Confirmed pacs.002 RJCT from correspondent"
        ));
        assertThat(failedInPending.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failedInPending.routeReference()).isEqualTo("ROUTE-DISP-FAIL");
        assertThat(failedInPending.externalDispatchReference()).isEqualTo("EXT-DISP-NACK-1");
        assertThat(failedInPending.statusReasonCode()).isEqualTo(PaymentReasonCode.EXTERNAL_NETWORK_REJECTED);
        assertAccountBalances(accountId, new BigDecimal("1000.0000"), new BigDecimal("0.0000"));
        assertReservationStatus(pDispatched.reservationToken(), "RELEASED", new BigDecimal("220.0000"), "USD");
    }

    // =========================================================================
    // 8. Settlement Port Operation (Does Not Consume Reservation or Post Ledger Entries)
    // =========================================================================

    @Test
    void shouldRecordSettlementViaPortWithoutConsumingReservationOrPostingLedgerEntries() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView initiated = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-SETTLE-PORT-" + shortId()), Money.of("350.0000", "USD"))
        );
        PaymentId paymentId = initiated.paymentId();
        paymentService.approveCompliance(paymentId);
        paymentService.commitToExecution(new CommitPaymentExecutionCommand(paymentId, "ROUTE-SETTLE-01"));
        paymentService.markDispatched(new MarkPaymentDispatchedCommand(paymentId, "EXT-SETTLE-01"));

        long ledgerTxCountBefore = countRows("ledger.ledger_transaction");
        UUID suppliedLedgerTxId = UUID.randomUUID();

        PaymentView settled = paymentSettlementPort.recordSettlement(
                new RecordPaymentSettlementCommand(paymentId, suppliedLedgerTxId)
        );

        assertThat(settled.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(settled.ledgerTransactionId()).isEqualTo(suppliedLedgerTxId);
        assertThat(settled.routeReference()).isEqualTo("ROUTE-SETTLE-01");
        assertThat(settled.externalDispatchReference()).isEqualTo("EXT-SETTLE-01");
        assertThat(settled.reservationToken()).isEqualTo(initiated.reservationToken());
        assertThat(paymentService.findPayment(paymentId)).contains(settled);

        // Idempotent retry with the same ledgerTransactionId
        assertThat(paymentSettlementPort.recordSettlement(
                new RecordPaymentSettlementCommand(paymentId, suppliedLedgerTxId)
        )).isEqualTo(settled);

        // Conflicting ledgerTransactionId on an already SETTLED payment is rejected
        assertThatThrownBy(() -> paymentSettlementPort.recordSettlement(
                new RecordPaymentSettlementCommand(paymentId, UUID.randomUUID())
        )).isInstanceOf(InvalidPaymentStateException.class);

        // Boundary verification: PaymentSettlementPort.recordSettlement did NOT post to Ledger
        // and did NOT consume the Banking reservation (those belong to the future Settlement module)
        long ledgerTxCountAfter = countRows("ledger.ledger_transaction");
        assertThat(ledgerTxCountAfter).isEqualTo(ledgerTxCountBefore);
        assertReservationStatus(initiated.reservationToken(), "ACTIVE", new BigDecimal("350.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("650.0000"), new BigDecimal("350.0000"));
    }

    // =========================================================================
    // 9. Concurrent Initiation Races (Identical vs. Conflicting Instructions)
    // =========================================================================

    @Test
    void shouldCreateSinglePaymentAndReservationUnderConcurrentIdenticalInitiationRequests() throws Exception {
        int threads = 8;
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentReference sharedReference = PaymentReference.of("PAY-CONC-IDENT-" + shortId());
        InitiatePaymentCommand sharedCmd = sampleInitiateCommand(
                accountId,
                sharedReference,
                Money.of("120.0000", "USD")
        );

        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Callable<PaymentView>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    readyLatch.countDown();
                    assertThat(startLatch.await(10, TimeUnit.SECONDS)).isTrue();
                    return paymentService.initiatePayment(sharedCmd);
                });
            }

            List<Future<PaymentView>> futures = new ArrayList<>(threads);
            for (Callable<PaymentView> task : tasks) {
                futures.add(pool.submit(task));
            }

            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            List<PaymentView> views = new ArrayList<>(threads);
            for (Future<PaymentView> future : futures) {
                views.add(future.get(20, TimeUnit.SECONDS));
            }

            Set<PaymentId> distinctPaymentIds = Set.copyOf(views.stream().map(PaymentView::paymentId).toList());
            Set<ReservationToken> distinctTokens = Set.copyOf(views.stream().map(PaymentView::reservationToken).toList());

            assertThat(distinctPaymentIds).hasSize(1);
            assertThat(distinctTokens).hasSize(1);
            assertThat(views).allMatch(v -> v.status() == PaymentStatus.COMPLIANCE_HOLD);

            assertAccountBalances(accountId, new BigDecimal("880.0000"), new BigDecimal("120.0000"));
            assertReservationCountForAccount(accountId, 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldProduceOneWinnerAndReferenceConflictsUnderConcurrentConflictingInitiationRequests() throws Exception {
        int threads = 8;
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("5000.0000"));
        PaymentReference sharedReference = PaymentReference.of("PAY-CONC-CONF-" + shortId());

        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<PaymentView> winners = Collections.synchronizedList(new ArrayList<>());
        List<PaymentReferenceConflictException> conflicts = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int i = 1; i <= threads; i++) {
                Money distinctAmount = Money.of((i * 50) + ".0000", "USD");
                InitiatePaymentCommand cmd = sampleInitiateCommand(accountId, sharedReference, distinctAmount);
                futures.add(pool.submit(() -> {
                    readyLatch.countDown();
                    try {
                        assertThat(startLatch.await(10, TimeUnit.SECONDS)).isTrue();
                        winners.add(paymentService.initiatePayment(cmd));
                    } catch (PaymentReferenceConflictException ex) {
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

            assertThat(winners).hasSize(1);
            assertThat(conflicts).hasSize(threads - 1);

            PaymentView winner = winners.getFirst();
            assertThat(winner.status()).isEqualTo(PaymentStatus.COMPLIANCE_HOLD);
            assertThat(paymentService.findBySourceAccountAndReference(accountId, sharedReference)).contains(winner);

            BigDecimal winningAmount = winner.instruction().amount().getAmount();
            assertAccountBalances(
                    accountId,
                    new BigDecimal("5000.0000").subtract(winningAmount),
                    winningAmount
            );
            assertReservationCountForAccount(accountId, 1);
        } finally {
            pool.shutdownNow();
        }
    }

    // =========================================================================
    // 10. Atomic Rollback of Reservation Release & Payment State Transition
    // =========================================================================

    @Test
    void shouldRollBackReservationReleaseWhenConcurrentCommitmentWinsOptimisticLockInCancelRejectOrFail() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        TransactionTemplate requiresNewTx = new TransactionTemplate(
                transactionManager,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        );

        // ---------------------------------------------------------------------
        // A. Concurrent ROUTING -> EXECUTING commitment wins against cancelPayment:
        //    Banking reservation release inside cancelPayment MUST roll back!
        // ---------------------------------------------------------------------
        PaymentView paymentForCancel = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-ATOM-CANC-" + shortId()), Money.of("200.0000", "USD"))
        );
        paymentService.approveCompliance(paymentForCancel.paymentId());

        // Simulate a concurrent transaction committing ROUTING -> EXECUTING (version 2 -> 3)
        // right before cancelPayment flushes its PaymentEntity update
        assertThatThrownBy(() -> requiresNewTx.executeWithoutResult(status -> {
            // 1. Prime current transaction's persistence context with the ROUTING PaymentEntity (version = 2)
            paymentService.findPayment(paymentForCancel.paymentId());

            // 2. Concurrent transaction commits ROUTING -> EXECUTING, bumping version in DB
            requiresNewTx.executeWithoutResult(innerStatus ->
                    paymentService.commitToExecution(
                            new CommitPaymentExecutionCommand(paymentForCancel.paymentId(), "ROUTE-CONC-WINNER")
                    )
            );

            // 3. cancelPayment sees the cached ROUTING entity, calls BankingService.releaseReservation,
            //    and updates PaymentEntity -> flush at commit fails with ObjectOptimisticLockingFailureException!
            paymentService.cancelPayment(new CancelPaymentCommand(paymentForCancel.paymentId(), "Concurrent cancel"));
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Verify the reservation release was rolled back and Payment remains EXECUTING with an ACTIVE reservation!
        PaymentView afterFailedCancel = paymentService.findPayment(paymentForCancel.paymentId()).orElseThrow();
        assertThat(afterFailedCancel.status()).isEqualTo(PaymentStatus.EXECUTING);
        assertThat(afterFailedCancel.routeReference()).isEqualTo("ROUTE-CONC-WINNER");
        assertReservationStatus(paymentForCancel.reservationToken(), "ACTIVE", new BigDecimal("200.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("800.0000"), new BigDecimal("200.0000"));

        // ---------------------------------------------------------------------
        // B. Concurrent ROUTING -> EXECUTING commitment wins against rejectPayment:
        //    Banking reservation release inside rejectPayment MUST roll back!
        // ---------------------------------------------------------------------
        PaymentView paymentForReject = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-ATOM-REJ-" + shortId()), Money.of("150.0000", "USD"))
        );
        paymentService.approveCompliance(paymentForReject.paymentId());

        assertThatThrownBy(() -> requiresNewTx.executeWithoutResult(status -> {
            // 1. Prime current transaction's persistence context with the ROUTING PaymentEntity
            paymentService.findPayment(paymentForReject.paymentId());

            // 2. Concurrent transaction commits ROUTING -> EXECUTING
            requiresNewTx.executeWithoutResult(innerStatus ->
                    paymentService.commitToExecution(
                            new CommitPaymentExecutionCommand(paymentForReject.paymentId(), "ROUTE-CONC-REJ-WIN")
                    )
            );

            // 3. rejectPayment releases reservation and updates stale PaymentEntity -> optimistic lock failure rolls back both!
            paymentService.rejectPayment(new RejectPaymentCommand(
                    paymentForReject.paymentId(),
                    PaymentReasonCode.NO_ROUTE_AVAILABLE,
                    "Stale reject"
            ));
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        PaymentView afterFailedReject = paymentService.findPayment(paymentForReject.paymentId()).orElseThrow();
        assertThat(afterFailedReject.status()).isEqualTo(PaymentStatus.EXECUTING);
        assertThat(afterFailedReject.routeReference()).isEqualTo("ROUTE-CONC-REJ-WIN");
        assertReservationStatus(paymentForReject.reservationToken(), "ACTIVE", new BigDecimal("150.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("650.0000"), new BigDecimal("350.0000"));

        // ---------------------------------------------------------------------
        // C. Concurrent PENDING_EXTERNAL -> SETTLED wins against failPayment:
        //    Banking reservation release inside failPayment MUST roll back!
        // ---------------------------------------------------------------------
        paymentService.markDispatched(
                new MarkPaymentDispatchedCommand(paymentForReject.paymentId(), "EXT-CONC-SETTLE-WIN")
        );
        UUID winningLedgerTxId = UUID.randomUUID();

        assertThatThrownBy(() -> requiresNewTx.executeWithoutResult(status -> {
            // 1. Prime current transaction's persistence context with the PENDING_EXTERNAL PaymentEntity
            paymentService.findPayment(paymentForReject.paymentId());

            // 2. Concurrent transaction records settlement (PENDING_EXTERNAL -> SETTLED)
            requiresNewTx.executeWithoutResult(innerStatus ->
                    paymentSettlementPort.recordSettlement(
                            new RecordPaymentSettlementCommand(paymentForReject.paymentId(), winningLedgerTxId)
                    )
            );

            // 3. failPayment releases reservation and updates stale PaymentEntity -> optimistic lock failure rolls back both!
            paymentService.failPayment(new FailPaymentCommand(
                    paymentForReject.paymentId(),
                    PaymentReasonCode.EXTERNAL_NETWORK_REJECTED,
                    "Stale fail after settlement"
            ));
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        PaymentView afterFailedFail = paymentService.findPayment(paymentForReject.paymentId()).orElseThrow();
        assertThat(afterFailedFail.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(afterFailedFail.ledgerTransactionId()).isEqualTo(winningLedgerTxId);
        assertReservationStatus(paymentForReject.reservationToken(), "ACTIVE", new BigDecimal("150.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("650.0000"), new BigDecimal("350.0000"));
    }

    @Test
    void shouldRollBackReservationReleaseAndPaymentTransitionWhenSharedTransactionFailsWithDatabaseConstraintViolation() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView initiated = paymentService.initiatePayment(
                sampleInitiateCommand(accountId, PaymentReference.of("PAY-DB-FAIL-RB-" + shortId()), Money.of("250.0000", "USD"))
        );
        PaymentId paymentId = initiated.paymentId();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            // 1. cancelPayment releases the Banking reservation and transitions Payment to CANCELLED
            PaymentView cancelled = paymentService.cancelPayment(
                    new CancelPaymentCommand(paymentId, "Will roll back due to downstream DB constraint failure")
            );
            assertThat(cancelled.status()).isEqualTo(PaymentStatus.CANCELLED);

            // 2. Subsequent statement in the shared transaction violates a PostgreSQL check constraint
            jdbcTemplate.update(
                    "INSERT INTO payment.payment ("
                            + "payment_id, version, source_account_id, payment_reference, instruction_fingerprint, "
                            + "amount, currency, beneficiary_name, beneficiary_account_number, beneficiary_bank_bic, "
                            + "status, created_at, updated_at"
                            + ") VALUES (?, 0, ?, ?, ?, -10.0000, 'USD', 'Bad', 'DE89370400440532013000', 'DEUTDEFFXXX', "
                            + "'VALIDATING', ?, ?)",
                    UUID.randomUUID(),
                    accountId.getValue(),
                    "REF-BAD-CONSTRAINT-" + shortId(),
                    "a".repeat(64),
                    Timestamp.from(Instant.now()),
                    Timestamp.from(Instant.now())
            );
        })).isInstanceOf(DataAccessException.class);

        // Both the Banking reservation release and the Payment CANCELLED transition were rolled back together
        PaymentView persistedAfterRollback = paymentService.findPayment(paymentId).orElseThrow();
        assertThat(persistedAfterRollback.status()).isEqualTo(PaymentStatus.COMPLIANCE_HOLD);
        assertReservationStatus(initiated.reservationToken(), "ACTIVE", new BigDecimal("250.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("750.0000"), new BigDecimal("250.0000"));
    }

    // =========================================================================
    // 11. Null/Missing Argument & Corrupted Persisted State Handling
    // =========================================================================

    @Test
    void shouldRejectNullCommandsMissingPaymentsAndCorruptedPersistedPaymentState() {
        PaymentId missingId = PaymentId.generate();
        AccountId accountId = AccountId.generate();
        PaymentReference ref = PaymentReference.of("REF-NULL-CHECK");

        assertThat(paymentService.findPayment(missingId)).isEmpty();
        assertThat(paymentService.findBySourceAccountAndReference(accountId, ref)).isEmpty();

        assertThatThrownBy(() -> paymentService.initiatePayment(null))
                .isInstanceOf(PaymentValidationException.class);
        assertThatThrownBy(() -> paymentService.approveCompliance(null))
                .isInstanceOf(PaymentValidationException.class);
        assertThatThrownBy(() -> paymentService.commitToExecution(null))
                .isInstanceOf(PaymentValidationException.class);
        assertThatThrownBy(() -> paymentService.markDispatched(null))
                .isInstanceOf(PaymentValidationException.class);
        assertThatThrownBy(() -> paymentService.cancelPayment(null))
                .isInstanceOf(PaymentValidationException.class);
        assertThatThrownBy(() -> paymentService.rejectPayment(null))
                .isInstanceOf(PaymentValidationException.class);
        assertThatThrownBy(() -> paymentService.failPayment(null))
                .isInstanceOf(PaymentValidationException.class);
        assertThatThrownBy(() -> paymentSettlementPort.recordSettlement(null))
                .isInstanceOf(PaymentValidationException.class);
        assertThatThrownBy(() -> paymentService.findPayment(null))
                .isInstanceOf(PaymentValidationException.class);
        assertThatThrownBy(() -> paymentService.findBySourceAccountAndReference(null, ref))
                .isInstanceOf(PaymentValidationException.class);
        assertThatThrownBy(() -> paymentService.findBySourceAccountAndReference(accountId, null))
                .isInstanceOf(PaymentValidationException.class);

        assertThatThrownBy(() -> paymentService.approveCompliance(missingId))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> paymentService.commitToExecution(new CommitPaymentExecutionCommand(missingId, "R1")))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> paymentService.markDispatched(new MarkPaymentDispatchedCommand(missingId, "E1")))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> paymentService.cancelPayment(new CancelPaymentCommand(missingId, null)))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> paymentService.rejectPayment(
                new RejectPaymentCommand(missingId, PaymentReasonCode.COMPLIANCE_REJECTED, null)))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> paymentService.failPayment(
                new FailPaymentCommand(missingId, PaymentReasonCode.OPERATIONAL_FAILURE, null)))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> paymentSettlementPort.recordSettlement(
                new RecordPaymentSettlementCommand(missingId, UUID.randomUUID())))
                .isInstanceOf(PaymentNotFoundException.class);

        // Corrupted persisted Payment fingerprint throws PersistedPaymentStateException and rolls back
        AccountId realAccount = createAccount("USD", "ACTIVE", new BigDecimal("500.0000"));
        PaymentView validPayment = paymentService.initiatePayment(
                sampleInitiateCommand(realAccount, PaymentReference.of("PAY-CORRUPT-FP-" + shortId()), Money.of("50.0000", "USD"))
        );
        jdbcTemplate.update(
                "UPDATE payment.payment SET instruction_fingerprint = ? WHERE payment_id = ?",
                "f".repeat(64),
                validPayment.paymentId().getValue()
        );
        assertThatThrownBy(() -> paymentService.approveCompliance(validPayment.paymentId()))
                .isInstanceOf(PersistedPaymentStateException.class);
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

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

    private void assertReservationCountForAccount(AccountId accountId, int expectedCount) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM banking.fund_reservation WHERE account_id = ?",
                Integer.class,
                accountId.getValue()
        );
        assertThat(count).isEqualTo(expectedCount);
    }

    private void assertZeroCommittedValidatingPayments() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment.payment WHERE status = 'VALIDATING'",
                Integer.class
        );
        assertThat(count).isZero();
    }

    private long countRows(String tableName) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return count != null ? count : 0L;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
