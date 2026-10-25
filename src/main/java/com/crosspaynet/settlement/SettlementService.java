package com.crosspaynet.settlement;

import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.ledger.EntryDirection;
import com.crosspaynet.ledger.LedgerAccountRef;
import com.crosspaynet.ledger.LedgerAccountType;
import com.crosspaynet.ledger.LedgerEntryLine;
import com.crosspaynet.ledger.LedgerPostingKey;
import com.crosspaynet.ledger.LedgerPostingResult;
import com.crosspaynet.ledger.LedgerService;
import com.crosspaynet.ledger.PostLedgerTransactionCommand;
import com.crosspaynet.ledger.PostingOutcome;
import com.crosspaynet.payment.PaymentService;
import com.crosspaynet.payment.contracts.InvalidPaymentStateException;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentNotFoundException;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.payment.settlement.PaymentSettlementPort;
import com.crosspaynet.payment.settlement.RecordPaymentSettlementCommand;
import com.crosspaynet.settlement.contracts.SettlePaymentCommand;
import com.crosspaynet.settlement.contracts.SettlementConflictException;
import com.crosspaynet.settlement.contracts.SettlementResult;
import com.crosspaynet.settlement.contracts.SettlementValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.List;
import java.util.Objects;

/**
 * Transactional orchestration service for CrossPayNet financial settlement.
 *
 * <p>Coordinates {@link BankingService#consumeReservation}, {@link LedgerService#postTransaction},
 * and {@link PaymentSettlementPort#recordSettlement} within a single ACID transaction so that
 * operational fund consumption, immutable double-entry ledger posting, and payment state
 * finalization succeed or roll back atomically.
 */
@Service
public class SettlementService {

    public static final String SETTLEMENT_POSTING_KEY_PREFIX = "PAYMENT_SETTLEMENT:";

    private final PaymentService paymentService;
    private final PaymentSettlementPort paymentSettlementPort;
    private final BankingService bankingService;
    private final LedgerService ledgerService;

    public SettlementService(
            PaymentService paymentService,
            PaymentSettlementPort paymentSettlementPort,
            BankingService bankingService,
            LedgerService ledgerService) {
        this.paymentService = Objects.requireNonNull(paymentService, "paymentService cannot be null.");
        this.paymentSettlementPort = Objects.requireNonNull(
                paymentSettlementPort, "paymentSettlementPort cannot be null."
        );
        this.bankingService = Objects.requireNonNull(bankingService, "bankingService cannot be null.");
        this.ledgerService = Objects.requireNonNull(ledgerService, "ledgerService cannot be null.");
    }

    /**
     * Derives the deterministic Ledger idempotency posting key for a payment settlement:
     * {@code PAYMENT_SETTLEMENT:{paymentId}}.
     */
    public static LedgerPostingKey postingKeyFor(PaymentId paymentId) {
        if (paymentId == null) {
            throw new SettlementValidationException("paymentId cannot be null.");
        }
        return LedgerPostingKey.of(SETTLEMENT_POSTING_KEY_PREFIX + paymentId.getValue());
    }

    /**
     * Atomically settles a dispatched payment ({@code PENDING_EXTERNAL -> SETTLED}) by:
     * <ol>
     *   <li>Validating that the payment exists and is in {@link PaymentStatus#PENDING_EXTERNAL}
     *       (or verifying idempotent replay if already in {@link PaymentStatus#SETTLED}).</li>
     *   <li>Consuming the active operational fund reservation in {@link BankingService}.</li>
     *   <li>Posting a balanced double-entry {@code STANDARD} transaction in {@link LedgerService}
     *       using key {@code PAYMENT_SETTLEMENT:{paymentId}}.</li>
     *   <li>Recording the resulting {@code ledgerTransactionId} on the payment via
     *       {@link PaymentSettlementPort#recordSettlement}.</li>
     * </ol>
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public SettlementResult settlePayment(SettlePaymentCommand command) {
        if (command == null) {
            throw new SettlementValidationException("SettlePaymentCommand cannot be null.");
        }

        PaymentView payment = paymentService.findPayment(command.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(command.paymentId()));

        if (payment.status() == PaymentStatus.SETTLED) {
            return verifyAlreadySettledReplay(payment, command);
        }

        if (payment.status() != PaymentStatus.PENDING_EXTERNAL) {
            throw new InvalidPaymentStateException(
                    "Cannot settle payment in state: " + payment.status()
                            + " (must be PENDING_EXTERNAL)."
            );
        }

        ReservationToken reservationToken = payment.reservationToken();
        if (reservationToken == null) {
            markCurrentTransactionRollbackOnly();
            throw new InvalidPaymentStateException(
                    "PENDING_EXTERNAL payment '" + payment.paymentId() + "' is missing reservationToken."
            );
        }

        PostLedgerTransactionCommand postCommand = buildPostLedgerTransactionCommand(payment, command);

        try {
            bankingService.consumeReservation(payment.instruction().sourceAccountId(), reservationToken);

            LedgerPostingResult ledgerResult = ledgerService.postTransaction(postCommand);

            PaymentView settledPayment = paymentSettlementPort.recordSettlement(
                    new RecordPaymentSettlementCommand(
                            payment.paymentId(),
                            ledgerResult.transactionId().getValue()
                    )
            );

            return new SettlementResult(
                    settledPayment.paymentId(),
                    reservationToken,
                    ledgerResult.transactionId(),
                    ledgerResult.outcome(),
                    settledPayment,
                    settledPayment.updatedAt()
            );
        } catch (RuntimeException | Error ex) {
            markCurrentTransactionRollbackOnly();
            throw ex;
        }
    }

    private SettlementResult verifyAlreadySettledReplay(PaymentView payment, SettlePaymentCommand command) {
        ReservationToken reservationToken = payment.reservationToken();
        if (reservationToken == null || payment.ledgerTransactionId() == null) {
            markCurrentTransactionRollbackOnly();
            throw new SettlementConflictException(
                    "SETTLED payment '" + payment.paymentId()
                            + "' is missing reservationToken or ledgerTransactionId."
            );
        }

        PostLedgerTransactionCommand postCommand = buildPostLedgerTransactionCommand(payment, command);

        try {
            // Verifies the existing Ledger posting key fingerprint against clearingAccountReference
            // and settlementReference; throws LedgerPostingKeyConflictException on mismatch.
            LedgerPostingResult ledgerResult = ledgerService.postTransaction(postCommand);

            if (ledgerResult.outcome() != PostingOutcome.ALREADY_POSTED
                    || !ledgerResult.transactionId().getValue().equals(payment.ledgerTransactionId())) {
                markCurrentTransactionRollbackOnly();
                throw new SettlementConflictException(
                        "Payment '" + payment.paymentId()
                                + "' is already SETTLED with ledgerTransactionId '"
                                + payment.ledgerTransactionId()
                                + "', which does not match existing settlement posting '"
                                + ledgerResult.transactionId().getValue()
                                + "' (outcome=" + ledgerResult.outcome() + ")."
                );
            }

            // Ensure the operational reservation is in CONSUMED state (idempotent no-op when already CONSUMED)
            bankingService.consumeReservation(payment.instruction().sourceAccountId(), reservationToken);

            PaymentView verifiedPayment = paymentSettlementPort.recordSettlement(
                    new RecordPaymentSettlementCommand(
                            payment.paymentId(),
                            ledgerResult.transactionId().getValue()
                    )
            );

            return new SettlementResult(
                    verifiedPayment.paymentId(),
                    reservationToken,
                    ledgerResult.transactionId(),
                    PostingOutcome.ALREADY_POSTED,
                    verifiedPayment,
                    verifiedPayment.updatedAt()
            );
        } catch (RuntimeException | Error ex) {
            markCurrentTransactionRollbackOnly();
            throw ex;
        }
    }

    private static PostLedgerTransactionCommand buildPostLedgerTransactionCommand(
            PaymentView payment,
            SettlePaymentCommand command) {
        LedgerAccountRef sourceBankingAccount = LedgerAccountRef.of(
                LedgerAccountType.BANKING_ACCOUNT,
                payment.instruction().sourceAccountId().getValue().toString()
        );
        LedgerAccountRef clearingAccount = command.clearingAccount();

        List<LedgerEntryLine> entries = List.of(
                new LedgerEntryLine(
                        sourceBankingAccount,
                        EntryDirection.DEBIT,
                        payment.instruction().amount()
                ),
                new LedgerEntryLine(
                        clearingAccount,
                        EntryDirection.CREDIT,
                        payment.instruction().amount()
                )
        );

        return new PostLedgerTransactionCommand(
                postingKeyFor(payment.paymentId()),
                payment.instruction().amount().getCurrency(),
                entries,
                command.settlementReference(),
                "Settlement for payment " + payment.paymentId().getValue()
        );
    }

    private static void markCurrentTransactionRollbackOnly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
            // No active Spring-managed transaction to mark
        }
    }
}
