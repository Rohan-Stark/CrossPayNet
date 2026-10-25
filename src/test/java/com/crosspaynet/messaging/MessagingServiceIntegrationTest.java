package com.crosspaynet.messaging;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.PostingOutcome;
import com.crosspaynet.messaging.contracts.AcknowledgeMessageCommand;
import com.crosspaynet.messaging.contracts.DispatchPaymentMessageCommand;
import com.crosspaynet.messaging.contracts.InvalidMessageStateException;
import com.crosspaynet.messaging.contracts.MessageConflictException;
import com.crosspaynet.messaging.contracts.MessageId;
import com.crosspaynet.messaging.contracts.MessageNotFoundException;
import com.crosspaynet.messaging.contracts.MessagingValidationException;
import com.crosspaynet.messaging.contracts.NacknowledgeMessageCommand;
import com.crosspaynet.messaging.contracts.PaymentMessageStatus;
import com.crosspaynet.messaging.contracts.PaymentMessageType;
import com.crosspaynet.messaging.contracts.PaymentMessageView;
import com.crosspaynet.messaging.contracts.TimeoutMessageCommand;
import com.crosspaynet.payment.PaymentService;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.CommitPaymentExecutionCommand;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.InvalidPaymentStateException;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentNotFoundException;
import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.settlement.SettlementService;
import com.crosspaynet.settlement.contracts.SettlePaymentCommand;
import com.crosspaynet.settlement.contracts.SettlementResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
class MessagingServiceIntegrationTest {

    private static final BicCode SENDER_BIC = new BicCode("CHASUS33XXX");

    @Autowired
    private MessagingService messagingService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================================
    // 1. Initial Dispatch (Attempt 1) -> Payment EXECUTING to PENDING_EXTERNAL
    // =========================================================================

    @Test
    void shouldDispatchFirstAttemptAndTransitionPaymentFromExecutingToPendingExternal() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView executing = createExecutingPayment(
                accountId,
                Money.of("275.5000", "USD"),
                "ROUTE-SWIFT-EUR-01"
        );

        PaymentMessageView dispatchedMsg = messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(executing.paymentId(), 1, SENDER_BIC)
        );

        assertThat(dispatchedMsg.messageId()).isNotNull();
        assertThat(dispatchedMsg.paymentId()).isEqualTo(executing.paymentId());
        assertThat(dispatchedMsg.attemptNumber()).isEqualTo(1);
        assertThat(dispatchedMsg.idempotencyKey())
                .isEqualTo("MSG:" + executing.paymentId().getValue() + ":ATTEMPT:1");
        assertThat(dispatchedMsg.messageType()).isEqualTo(PaymentMessageType.PACS_008);
        assertThat(dispatchedMsg.senderBic()).isEqualTo(SENDER_BIC);
        assertThat(dispatchedMsg.receiverBic()).isEqualTo(new BicCode("DEUTDEFFXXX"));
        assertThat(dispatchedMsg.routeReference()).isEqualTo("ROUTE-SWIFT-EUR-01");
        assertThat(dispatchedMsg.amount()).isEqualTo(Money.of("275.5000", "USD"));
        assertThat(dispatchedMsg.status()).isEqualTo(PaymentMessageStatus.SENT);
        assertThat(dispatchedMsg.payloadDigest()).hasSize(64);
        assertThat(dispatchedMsg.acknowledgmentReference()).isNull();
        assertThat(dispatchedMsg.peerRejectionCode()).isNull();
        assertThat(dispatchedMsg.timeoutReason()).isNull();

        // Verify persisted Payment transitioned EXECUTING -> PENDING_EXTERNAL with externalDispatchReference = messageId
        PaymentView updatedPayment = paymentService.findPayment(executing.paymentId()).orElseThrow();
        assertThat(updatedPayment.status()).isEqualTo(PaymentStatus.PENDING_EXTERNAL);
        assertThat(updatedPayment.externalDispatchReference())
                .isEqualTo(dispatchedMsg.messageId().getValue().toString());

        // Verify read queries on MessagingService
        assertThat(messagingService.findMessage(dispatchedMsg.messageId())).contains(dispatchedMsg);
        assertThat(messagingService.findByPaymentAndAttempt(executing.paymentId(), 1)).contains(dispatchedMsg);
        assertThat(messagingService.findMessagesForPayment(executing.paymentId()))
                .containsExactly(dispatchedMsg);
    }

    // =========================================================================
    // 2. Timeout Followed by Retry Attempt (Attempt 2) Preserves Original Dispatch Reference
    // =========================================================================

    @Test
    void shouldPermitRetryAttemptAfterTimeoutWithDistinctMessageIdentityWhilePreservingPaymentExternalDispatchReference() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView executing = createExecutingPayment(
                accountId,
                Money.of("320.0000", "USD"),
                "ROUTE-RETRY-01"
        );

        // 1. Dispatch Attempt 1 -> Payment enters PENDING_EXTERNAL
        PaymentMessageView attempt1 = messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(executing.paymentId(), 1, SENDER_BIC)
        );
        String originalDispatchRef = attempt1.messageId().getValue().toString();
        assertThat(paymentService.findPayment(executing.paymentId()).orElseThrow().externalDispatchReference())
                .isEqualTo(originalDispatchRef);

        // 2. Cannot dispatch Attempt 2 while Attempt 1 is still SENT (not TIMEOUT)
        assertThatThrownBy(() -> messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(executing.paymentId(), 2, SENDER_BIC)
        )).isInstanceOf(InvalidMessageStateException.class);

        // 3. Mark Attempt 1 as TIMEOUT -> Payment stays PENDING_EXTERNAL and Banking reservation stays ACTIVE
        PaymentMessageView timedOut1 = messagingService.markMessageTimedOut(
                new TimeoutMessageCommand(attempt1.messageId(), "Peer gateway read timeout after 30s")
        );
        assertThat(timedOut1.status()).isEqualTo(PaymentMessageStatus.TIMEOUT);
        assertThat(timedOut1.timeoutReason()).isEqualTo("Peer gateway read timeout after 30s");

        // Idempotent retry of markMessageTimedOut
        assertThat(messagingService.markMessageTimedOut(
                new TimeoutMessageCommand(attempt1.messageId(), "Peer gateway read timeout after 30s")
        )).isEqualTo(timedOut1);

        PaymentView afterTimeout = paymentService.findPayment(executing.paymentId()).orElseThrow();
        assertThat(afterTimeout.status()).isEqualTo(PaymentStatus.PENDING_EXTERNAL);
        assertThat(afterTimeout.externalDispatchReference()).isEqualTo(originalDispatchRef);
        assertReservationStatus(executing.reservationToken(), "ACTIVE", new BigDecimal("320.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("680.0000"), new BigDecimal("320.0000"));

        // 4. Cannot skip Attempt 2 and jump to Attempt 3
        assertThatThrownBy(() -> messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(executing.paymentId(), 3, SENDER_BIC)
        )).isInstanceOf(InvalidMessageStateException.class);

        // 5. Dispatch Attempt 2 after Attempt 1 TIMEOUT -> succeeds with new MessageId and attemptNumber = 2!
        PaymentMessageView attempt2 = messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(executing.paymentId(), 2, SENDER_BIC)
        );
        assertThat(attempt2.messageId()).isNotEqualTo(attempt1.messageId());
        assertThat(attempt2.attemptNumber()).isEqualTo(2);
        assertThat(attempt2.idempotencyKey())
                .isEqualTo("MSG:" + executing.paymentId().getValue() + ":ATTEMPT:2");
        assertThat(attempt2.status()).isEqualTo(PaymentMessageStatus.SENT);
        assertThat(attempt2.payload()).isNotEqualTo(attempt1.payload());
        assertThat(attempt2.payloadDigest()).isNotEqualTo(attempt1.payloadDigest());

        // Payment remains PENDING_EXTERNAL and keeps its original externalDispatchReference from Attempt 1
        PaymentView afterAttempt2 = paymentService.findPayment(executing.paymentId()).orElseThrow();
        assertThat(afterAttempt2.status()).isEqualTo(PaymentStatus.PENDING_EXTERNAL);
        assertThat(afterAttempt2.externalDispatchReference()).isEqualTo(originalDispatchRef);

        assertThat(messagingService.findMessagesForPayment(executing.paymentId()))
                .containsExactly(timedOut1, attempt2);
    }

    // =========================================================================
    // 3. Idempotent Dispatch Retries & Conflicting Sender BIC Rejection
    // =========================================================================

    @Test
    void shouldReturnExistingMessageOnIdenticalDispatchRetryAndRejectConflictingSenderBic() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView executing = createExecutingPayment(
                accountId,
                Money.of("150.0000", "USD"),
                "ROUTE-IDEMP-MSG-01"
        );
        DispatchPaymentMessageCommand cmd = new DispatchPaymentMessageCommand(
                executing.paymentId(),
                1,
                SENDER_BIC
        );

        PaymentMessageView first = messagingService.dispatchPaymentMessage(cmd);
        PaymentMessageView retry = messagingService.dispatchPaymentMessage(cmd);
        assertThat(retry).isEqualTo(first);

        // Conflicting senderBic for the same (paymentId, attemptNumber) throws MessageConflictException
        DispatchPaymentMessageCommand conflictingCmd = new DispatchPaymentMessageCommand(
                executing.paymentId(),
                1,
                new BicCode("CITIUS33XXX")
        );
        assertThatThrownBy(() -> messagingService.dispatchPaymentMessage(conflictingCmd))
                .isInstanceOf(MessageConflictException.class);

        // Verify MessageConflictException does not mark a caller's shared transaction rollback-only
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            assertThatThrownBy(() -> messagingService.dispatchPaymentMessage(conflictingCmd))
                    .isInstanceOf(MessageConflictException.class);
            assertThat(status.isRollbackOnly()).isFalse();
        });

        assertThat(messagingService.findMessagesForPayment(executing.paymentId())).hasSize(1);
    }

    // =========================================================================
    // 4. Positive Acknowledgment Separation From Settlement
    // =========================================================================

    @Test
    void shouldRecordPositiveAcknowledgmentWithoutSettlingPaymentOrPostingLedgerEntriesUntilSettlementIsInvoked() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView executing = createExecutingPayment(
                accountId,
                Money.of("400.0000", "USD"),
                "ROUTE-ACK-SEP-01"
        );
        PaymentMessageView sent = messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(executing.paymentId(), 1, SENDER_BIC)
        );

        long ledgerTxCountBefore = countRows("ledger.ledger_transaction");

        // 1. Acknowledge the message (SENT -> ACKNOWLEDGED)
        PaymentMessageView acknowledged = messagingService.acknowledgeMessage(
                new AcknowledgeMessageCommand(sent.messageId(), "  PACS002-ACSC-ACK-77  ")
        );
        assertThat(acknowledged.status()).isEqualTo(PaymentMessageStatus.ACKNOWLEDGED);
        assertThat(acknowledged.acknowledgmentReference()).isEqualTo("PACS002-ACSC-ACK-77");

        // Idempotent retry with same acknowledgmentReference
        assertThat(messagingService.acknowledgeMessage(
                new AcknowledgeMessageCommand(sent.messageId(), "PACS002-ACSC-ACK-77")
        )).isEqualTo(acknowledged);

        // Conflicting acknowledgmentReference on already-ACKNOWLEDGED message is rejected
        assertThatThrownBy(() -> messagingService.acknowledgeMessage(
                new AcknowledgeMessageCommand(sent.messageId(), "PACS002-DIFFERENT-ACK")
        )).isInstanceOf(InvalidMessageStateException.class);

        // 2. Verify boundary separation: Payment is STILL PENDING_EXTERNAL, reservation is STILL ACTIVE,
        //    and ZERO Ledger transactions were posted by MessagingService!
        PaymentView paymentAfterAck = paymentService.findPayment(executing.paymentId()).orElseThrow();
        assertThat(paymentAfterAck.status()).isEqualTo(PaymentStatus.PENDING_EXTERNAL);
        assertThat(paymentAfterAck.ledgerTransactionId()).isNull();
        assertReservationStatus(executing.reservationToken(), "ACTIVE", new BigDecimal("400.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("600.0000"), new BigDecimal("400.0000"));
        assertThat(countRows("ledger.ledger_transaction")).isEqualTo(ledgerTxCountBefore);

        // 3. Subsequent separate call to SettlementService uses the verified acknowledgmentReference to settle!
        SettlementResult settlementResult = settlementService.settlePayment(new SettlePaymentCommand(
                executing.paymentId(),
                "CLEARING_SWIFT_USD_" + shortId(),
                acknowledged.acknowledgmentReference()
        ));
        assertThat(settlementResult.ledgerPostingOutcome()).isEqualTo(PostingOutcome.POSTED);
        assertThat(settlementResult.payment().status()).isEqualTo(PaymentStatus.SETTLED);
        assertReservationStatus(executing.reservationToken(), "CONSUMED", new BigDecimal("400.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("600.0000"), new BigDecimal("0.0000"));
    }

    // =========================================================================
    // 5. Negative Acknowledgment Coordinates Atomic Payment Failure & Reservation Release
    // =========================================================================

    @Test
    void shouldAtomicallyNacknowledgeMessageFailPaymentAndReleaseBankingReservation() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView executing = createExecutingPayment(
                accountId,
                Money.of("225.0000", "USD"),
                "ROUTE-NACK-01"
        );
        PaymentMessageView sent = messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(executing.paymentId(), 1, SENDER_BIC)
        );
        assertAccountBalances(accountId, new BigDecimal("775.0000"), new BigDecimal("225.0000"));

        PaymentMessageView nacked = messagingService.nacknowledgeMessage(new NacknowledgeMessageCommand(
                sent.messageId(),
                "AC04",
                "Closed account number at beneficiary bank"
        ));

        assertThat(nacked.status()).isEqualTo(PaymentMessageStatus.NACKNOWLEDGED);
        assertThat(nacked.peerRejectionCode()).isEqualTo("AC04");
        assertThat(nacked.peerRejectionReason()).isEqualTo("Closed account number at beneficiary bank");

        // Verify Payment transitioned PENDING_EXTERNAL -> FAILED (EXTERNAL_NETWORK_REJECTED)
        PaymentView failedPayment = paymentService.findPayment(executing.paymentId()).orElseThrow();
        assertThat(failedPayment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failedPayment.statusReasonCode()).isEqualTo(PaymentReasonCode.EXTERNAL_NETWORK_REJECTED);
        assertThat(failedPayment.statusReasonDetail())
                .isEqualTo("Peer NACK [AC04]: Closed account number at beneficiary bank");

        // Verify Banking reservation was released atomically and available_balance was restored to 1000.0000
        assertReservationStatus(executing.reservationToken(), "RELEASED", new BigDecimal("225.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("1000.0000"), new BigDecimal("0.0000"));

        // Idempotent retry with identical NACK parameters returns the same view
        assertThat(messagingService.nacknowledgeMessage(new NacknowledgeMessageCommand(
                sent.messageId(),
                "AC04",
                "Closed account number at beneficiary bank"
        ))).isEqualTo(nacked);

        // Conflicting NACK code on already-NACKNOWLEDGED message is rejected
        assertThatThrownBy(() -> messagingService.nacknowledgeMessage(new NacknowledgeMessageCommand(
                sent.messageId(),
                "AM04",
                "Different code"
        ))).isInstanceOf(InvalidMessageStateException.class);
    }

    // =========================================================================
    // 6. Optimistic-Lock Failure & Rollback Atomicity Across Messaging, Payment, and Banking
    // =========================================================================

    @Test
    void shouldRollBackPaymentFailureAndReservationReleaseWhenNacknowledgeMessageFailsWithOptimisticLock() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView executing = createExecutingPayment(
                accountId,
                Money.of("190.0000", "USD"),
                "ROUTE-OPT-LOCK-01"
        );
        PaymentMessageView sent = messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(executing.paymentId(), 1, SENDER_BIC)
        );

        TransactionTemplate requiresNewTx = new TransactionTemplate(
                transactionManager,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        );

        assertThatThrownBy(() -> requiresNewTx.executeWithoutResult(status -> {
            // 1. Prime current transaction's JPA persistence context with the SENT PaymentMessageEntity
            messagingService.findMessage(sent.messageId());

            // 2. Concurrent transaction bumps PaymentMessageEntity's version in PostgreSQL
            requiresNewTx.executeWithoutResult(innerStatus ->
                    jdbcTemplate.update(
                            "UPDATE messaging.payment_message SET version = version + 1 WHERE message_id = ?",
                            sent.messageId().getValue()
                    )
            );

            // 3. nacknowledgeMessage calls paymentService.failPayment (releasing the Banking reservation
            //    and marking Payment FAILED) and then updates the stale PaymentMessageEntity ->
            //    at flush/commit, ObjectOptimisticLockingFailureException is thrown!
            messagingService.nacknowledgeMessage(new NacknowledgeMessageCommand(
                    sent.messageId(),
                    "AC04",
                    "Stale NACK with optimistic lock conflict"
            ));
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Verify ALL THREE modules rolled back atomically:
        // 1. PaymentMessage is still SENT
        assertThat(messagingService.findMessage(sent.messageId()).orElseThrow().status())
                .isEqualTo(PaymentMessageStatus.SENT);
        // 2. Payment is still PENDING_EXTERNAL (not FAILED)
        assertThat(paymentService.findPayment(executing.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.PENDING_EXTERNAL);
        // 3. Banking reservation is still ACTIVE and reserved_amount is still 190.0000
        assertReservationStatus(executing.reservationToken(), "ACTIVE", new BigDecimal("190.0000"), "USD");
        assertAccountBalances(accountId, new BigDecimal("810.0000"), new BigDecimal("190.0000"));
    }

    @Test
    void shouldRollBackInsertedMessageRowWhenInitialDispatchFailsToMarkPaymentDispatched() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView executing = createExecutingPayment(
                accountId,
                Money.of("140.0000", "USD"),
                "ROUTE-DISP-RB-01"
        );

        TransactionTemplate requiresNewTx = new TransactionTemplate(
                transactionManager,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        );

        assertThatThrownBy(() -> requiresNewTx.executeWithoutResult(status -> {
            // 1. Prime current transaction's JPA persistence context with the EXECUTING PaymentEntity
            paymentService.findPayment(executing.paymentId());

            // 2. Concurrent transaction bumps PaymentEntity's version in PostgreSQL
            requiresNewTx.executeWithoutResult(innerStatus ->
                    jdbcTemplate.update(
                            "UPDATE payment.payment SET version = version + 1 WHERE payment_id = ?",
                            executing.paymentId().getValue()
                    )
            );

            // 3. dispatchPaymentMessage inserts messaging.payment_message row and calls paymentService.markDispatched
            //    -> flush at commit fails with ObjectOptimisticLockingFailureException!
            messagingService.dispatchPaymentMessage(
                    new DispatchPaymentMessageCommand(executing.paymentId(), 1, SENDER_BIC)
            );
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Verify the inserted messaging.payment_message row was rolled back completely!
        assertThat(messagingService.findMessagesForPayment(executing.paymentId())).isEmpty();
        assertThat(paymentService.findPayment(executing.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.EXECUTING);
    }

    // =========================================================================
    // 7. Concurrent Dispatch Race (8 Threads)
    // =========================================================================

    @Test
    void shouldCreateSingleMessageAttemptAndSingleMessageIdUnderConcurrentIdenticalDispatchRequests() throws Exception {
        int threads = 8;
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView executing = createExecutingPayment(
                accountId,
                Money.of("210.0000", "USD"),
                "ROUTE-CONC-MSG-01"
        );
        DispatchPaymentMessageCommand sharedCmd = new DispatchPaymentMessageCommand(
                executing.paymentId(),
                1,
                SENDER_BIC
        );

        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Callable<PaymentMessageView>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    readyLatch.countDown();
                    assertThat(startLatch.await(10, TimeUnit.SECONDS)).isTrue();
                    return messagingService.dispatchPaymentMessage(sharedCmd);
                });
            }

            List<Future<PaymentMessageView>> futures = new ArrayList<>(threads);
            for (Callable<PaymentMessageView> task : tasks) {
                futures.add(pool.submit(task));
            }

            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            List<PaymentMessageView> views = new ArrayList<>(threads);
            for (Future<PaymentMessageView> future : futures) {
                views.add(future.get(20, TimeUnit.SECONDS));
            }

            Set<MessageId> distinctMessageIds = Set.copyOf(
                    views.stream().map(PaymentMessageView::messageId).toList()
            );
            assertThat(distinctMessageIds).hasSize(1);
            assertThat(views).allMatch(v -> v.status() == PaymentMessageStatus.SENT && v.attemptNumber() == 1);

            List<PaymentMessageView> persistedAttempts = messagingService.findMessagesForPayment(executing.paymentId());
            assertThat(persistedAttempts).hasSize(1);

            PaymentView updatedPayment = paymentService.findPayment(executing.paymentId()).orElseThrow();
            assertThat(updatedPayment.status()).isEqualTo(PaymentStatus.PENDING_EXTERNAL);
            assertThat(updatedPayment.externalDispatchReference())
                    .isEqualTo(distinctMessageIds.iterator().next().getValue().toString());
        } finally {
            pool.shutdownNow();
        }
    }

    // =========================================================================
    // 8. Invalid Payment States & Missing Entities Rejected Without Side Effects
    // =========================================================================

    @Test
    void shouldRejectDispatchAndOutcomesForInvalidPaymentStatesAndMissingIds() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView initiated = paymentService.initiatePayment(new InitiatePaymentCommand(
                accountId,
                PaymentReference.of("PAY-MSG-INV-" + shortId()),
                new BeneficiaryDetails("Siemens AG", "DE89370400440532013000", new BicCode("DEUTDEFFXXX")),
                Money.of("100.0000", "USD")
        ));

        // 1. Cannot dispatch Attempt 1 while Payment is in COMPLIANCE_HOLD or ROUTING (not EXECUTING)
        assertThatThrownBy(() -> messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(initiated.paymentId(), 1, SENDER_BIC)
        )).isInstanceOf(InvalidPaymentStateException.class);

        paymentService.approveCompliance(initiated.paymentId());
        assertThatThrownBy(() -> messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(initiated.paymentId(), 1, SENDER_BIC)
        )).isInstanceOf(InvalidPaymentStateException.class);
        assertThat(messagingService.findMessagesForPayment(initiated.paymentId())).isEmpty();

        // 2. Null & missing entity checks
        PaymentId missingPaymentId = PaymentId.generate();
        MessageId missingMessageId = MessageId.generate();

        assertThatThrownBy(() -> messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(missingPaymentId, 1, SENDER_BIC)
        )).isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> messagingService.acknowledgeMessage(
                new AcknowledgeMessageCommand(missingMessageId, "ACK-1")
        )).isInstanceOf(MessageNotFoundException.class);
        assertThatThrownBy(() -> messagingService.nacknowledgeMessage(
                new NacknowledgeMessageCommand(missingMessageId, "AC04", "Reason")
        )).isInstanceOf(MessageNotFoundException.class);
        assertThatThrownBy(() -> messagingService.markMessageTimedOut(
                new TimeoutMessageCommand(missingMessageId, "Timeout")
        )).isInstanceOf(MessageNotFoundException.class);

        assertThatThrownBy(() -> messagingService.dispatchPaymentMessage(null))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> messagingService.acknowledgeMessage(null))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> messagingService.nacknowledgeMessage(null))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> messagingService.markMessageTimedOut(null))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> messagingService.findMessage(null))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> messagingService.findByPaymentAndAttempt(null, 1))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> messagingService.findByPaymentAndAttempt(initiated.paymentId(), 0))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> messagingService.findMessagesForPayment(null))
                .isInstanceOf(MessagingValidationException.class);
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private PaymentView createExecutingPayment(AccountId sourceAccountId, Money amount, String routeReference) {
        PaymentView initiated = paymentService.initiatePayment(new InitiatePaymentCommand(
                sourceAccountId,
                PaymentReference.of("PAY-MSG-" + shortId()),
                new BeneficiaryDetails(
                        "Deutsche Telekom AG",
                        "DE89370400440532013000",
                        new BicCode("DEUTDEFFXXX")
                ),
                amount
        ));
        paymentService.approveCompliance(initiated.paymentId());
        return paymentService.commitToExecution(
                new CommitPaymentExecutionCommand(initiated.paymentId(), routeReference)
        );
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

    private long countRows(String tableName) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return count != null ? count : 0L;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
