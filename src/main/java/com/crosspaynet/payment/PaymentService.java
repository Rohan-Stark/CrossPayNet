package com.crosspaynet.payment;

import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.CurrencyMismatchException;
import com.crosspaynet.banking.contracts.InsufficientFundsException;
import com.crosspaynet.banking.contracts.InvalidAccountStateException;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.CancelPaymentCommand;
import com.crosspaynet.payment.contracts.CommitPaymentExecutionCommand;
import com.crosspaynet.payment.contracts.FailPaymentCommand;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.InvalidPaymentStateException;
import com.crosspaynet.payment.contracts.MarkPaymentDispatchedCommand;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentInstruction;
import com.crosspaynet.payment.contracts.PaymentNotFoundException;
import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentReferenceConflictException;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentValidationException;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.payment.contracts.RejectPaymentCommand;
import com.crosspaynet.payment.domain.Payment;
import com.crosspaynet.payment.domain.PaymentLimits;
import com.crosspaynet.payment.domain.PersistedPaymentStateException;
import com.crosspaynet.payment.infrastructure.persistence.PaymentEntity;
import com.crosspaynet.payment.infrastructure.persistence.PaymentMapper;
import com.crosspaynet.payment.infrastructure.persistence.PaymentRepository;
import com.crosspaynet.payment.settlement.PaymentSettlementPort;
import com.crosspaynet.payment.settlement.RecordPaymentSettlementCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * Transactional orchestration service for the CrossPayNet Payment lifecycle.
 *
 * <p>Enforces the Financial Commitment Point ({@code ROUTING -> EXECUTING}), coordinates
 * operational fund reservations with {@link BankingService} within shared local transactions,
 * and exposes {@link PaymentSettlementPort} for the future Settlement module.
 */
@Service
public class PaymentService implements PaymentSettlementPort {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final BankingService bankingService;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentMapper paymentMapper,
            BankingService bankingService) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository cannot be null.");
        this.paymentMapper = Objects.requireNonNull(paymentMapper, "paymentMapper cannot be null.");
        this.bankingService = Objects.requireNonNull(bankingService, "bankingService cannot be null.");
    }

    /**
     * Initiates a cross-border payment with conflict-safe idempotency on
     * {@code (source_account_id, payment_reference)} and synchronous fund reservation.
     *
     * <p>{@link PaymentReferenceConflictException} is exempted from marking the transaction
     * rollback-only because it is thrown exclusively on the duplicate-claim branch before
     * this invocation performs any database writes.
     */
    @Transactional(
            propagation = Propagation.REQUIRED,
            noRollbackFor = PaymentReferenceConflictException.class
    )
    public PaymentView initiatePayment(InitiatePaymentCommand command) {
        if (command == null) {
            throw new PaymentValidationException("InitiatePaymentCommand cannot be null.");
        }

        PaymentInstruction instruction = command.toInstruction();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Payment candidate = Payment.initiate(PaymentId.generate(), instruction, now);
        BeneficiaryDetails beneficiary = instruction.beneficiary();

        int inserted = paymentRepository.insertClaimOnConflictDoNothing(
                candidate.getId().getValue(),
                instruction.sourceAccountId().getValue(),
                instruction.paymentReference().getValue(),
                candidate.getInstructionFingerprint().getValue(),
                instruction.amount().getAmount(),
                instruction.amount().getCurrency().getCode(),
                beneficiary.beneficiaryName(),
                beneficiary.beneficiaryAccountNumber(),
                beneficiary.beneficiaryBankBic().getValue(),
                candidate.getStatus().name(),
                null,
                null,
                null,
                null,
                null,
                null,
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );

        if (inserted == 0) {
            PaymentEntity existingEntity = paymentRepository.findBySourceAccountIdAndPaymentReference(
                            instruction.sourceAccountId().getValue(),
                            instruction.paymentReference().getValue()
                    )
                    .orElseThrow(() -> new PersistedPaymentStateException(
                            "Payment claim conflicted on (sourceAccountId="
                                    + instruction.sourceAccountId() + ", paymentReference="
                                    + instruction.paymentReference().getValue()
                                    + "), but existing row could not be found."
                    ));

            Payment existing = paymentMapper.toDomain(existingEntity);
            if (!existing.getInstructionFingerprint().equals(candidate.getInstructionFingerprint())) {
                throw new PaymentReferenceConflictException(
                        "Payment reference '" + instruction.paymentReference().getValue()
                                + "' is already in use for sourceAccountId '"
                                + instruction.sourceAccountId()
                                + "' with different payment instruction parameters."
                );
            }
            return existing.toView();
        }

        PaymentEntity claimedEntity = paymentRepository.findById(candidate.getId().getValue())
                .orElseThrow(() -> new PersistedPaymentStateException(
                        "Newly inserted Payment claim '" + candidate.getId() + "' could not be loaded."
                ));

        try {
            ReservationToken token = bankingService.reserveFunds(
                    instruction.sourceAccountId(),
                    instruction.amount(),
                    instruction.paymentReference()
            );
            candidate.confirmFundsReserved(token, nextTimestamp(candidate));
        } catch (InsufficientFundsException ex) {
            candidate.rejectOnReservationFailure(
                    PaymentReasonCode.INSUFFICIENT_FUNDS,
                    sanitizeReasonDetail(ex.getMessage(), PaymentReasonCode.INSUFFICIENT_FUNDS),
                    nextTimestamp(candidate)
            );
        } catch (InvalidAccountStateException ex) {
            candidate.rejectOnReservationFailure(
                    PaymentReasonCode.INVALID_ACCOUNT_STATE,
                    sanitizeReasonDetail(ex.getMessage(), PaymentReasonCode.INVALID_ACCOUNT_STATE),
                    nextTimestamp(candidate)
            );
        } catch (CurrencyMismatchException ex) {
            candidate.rejectOnReservationFailure(
                    PaymentReasonCode.CURRENCY_MISMATCH,
                    sanitizeReasonDetail(ex.getMessage(), PaymentReasonCode.CURRENCY_MISMATCH),
                    nextTimestamp(candidate)
            );
        } catch (RuntimeException | Error ex) {
            markCurrentTransactionRollbackOnly();
            throw ex;
        }

        paymentMapper.updateEntity(candidate, claimedEntity);
        paymentRepository.save(claimedEntity);
        return candidate.toView();
    }

    /**
     * Approves compliance clearance ({@code COMPLIANCE_HOLD -> ROUTING}).
     * Idempotent if the payment is already in {@link PaymentStatus#ROUTING}.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentView approveCompliance(PaymentId paymentId) {
        if (paymentId == null) {
            throw new PaymentValidationException("paymentId cannot be null.");
        }

        PaymentEntity entity = loadEntityOrThrow(paymentId);
        Payment payment = paymentMapper.toDomain(entity);

        if (payment.getStatus() == PaymentStatus.ROUTING) {
            return payment.toView();
        }

        payment.approveCompliance(nextTimestamp(payment));
        paymentMapper.updateEntity(payment, entity);
        paymentRepository.save(entity);
        return payment.toView();
    }

    /**
     * Commits the payment to execution at the Financial Commitment Point ({@code ROUTING -> EXECUTING}).
     * Idempotent if the payment is already in {@link PaymentStatus#EXECUTING} with the same route reference.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentView commitToExecution(CommitPaymentExecutionCommand command) {
        if (command == null) {
            throw new PaymentValidationException("CommitPaymentExecutionCommand cannot be null.");
        }

        PaymentEntity entity = loadEntityOrThrow(command.paymentId());
        Payment payment = paymentMapper.toDomain(entity);

        if (payment.getStatus() == PaymentStatus.EXECUTING
                && Objects.equals(payment.getRouteReference().orElse(null), command.routeReference())) {
            return payment.toView();
        }

        payment.commitToExecution(command.routeReference(), nextTimestamp(payment));
        paymentMapper.updateEntity(payment, entity);
        paymentRepository.save(entity);
        return payment.toView();
    }

    /**
     * Marks a committed payment as dispatched to the external network ({@code EXECUTING -> PENDING_EXTERNAL}).
     * Idempotent if the payment is already in {@link PaymentStatus#PENDING_EXTERNAL} with the same dispatch reference.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentView markDispatched(MarkPaymentDispatchedCommand command) {
        if (command == null) {
            throw new PaymentValidationException("MarkPaymentDispatchedCommand cannot be null.");
        }

        PaymentEntity entity = loadEntityOrThrow(command.paymentId());
        Payment payment = paymentMapper.toDomain(entity);

        if (payment.getStatus() == PaymentStatus.PENDING_EXTERNAL
                && Objects.equals(
                        payment.getExternalDispatchReference().orElse(null),
                        command.externalDispatchReference())) {
            return payment.toView();
        }

        payment.markDispatched(command.externalDispatchReference(), nextTimestamp(payment));
        paymentMapper.updateEntity(payment, entity);
        paymentRepository.save(entity);
        return payment.toView();
    }

    /**
     * Cancels a payment prior to the Financial Commitment Point
     * ({@code VALIDATING}, {@code COMPLIANCE_HOLD}, or {@code ROUTING -> CANCELLED}),
     * releasing its active Banking fund reservation in the same transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentView cancelPayment(CancelPaymentCommand command) {
        if (command == null) {
            throw new PaymentValidationException("CancelPaymentCommand cannot be null.");
        }

        PaymentEntity entity = loadEntityOrThrow(command.paymentId());
        Payment payment = paymentMapper.toDomain(entity);

        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            return payment.toView();
        }

        // Validate domain transition in memory BEFORE touching Banking reservation state
        payment.cancel(command.reasonDetail(), nextTimestamp(payment));

        if (payment.getReservationToken().isPresent()) {
            bankingService.releaseReservation(
                    payment.getInstruction().sourceAccountId(),
                    payment.getReservationToken().orElseThrow()
            );
        }

        paymentMapper.updateEntity(payment, entity);
        paymentRepository.save(entity);
        return payment.toView();
    }

    /**
     * Rejects a payment prior to the Financial Commitment Point
     * ({@code COMPLIANCE_HOLD} or {@code ROUTING -> REJECTED}) due to compliance or routing rules,
     * releasing its active Banking fund reservation in the same transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentView rejectPayment(RejectPaymentCommand command) {
        if (command == null) {
            throw new PaymentValidationException("RejectPaymentCommand cannot be null.");
        }

        PaymentEntity entity = loadEntityOrThrow(command.paymentId());
        Payment payment = paymentMapper.toDomain(entity);

        if (payment.getStatus() == PaymentStatus.REJECTED) {
            if (payment.getReservationToken().isPresent()
                    && payment.getStatusReasonCode().orElse(null) == command.reasonCode()) {
                return payment.toView();
            }
            throw new InvalidPaymentStateException(
                    "Payment is already REJECTED with a different reasonCode or pre-reservation state."
            );
        }

        // Validate domain transition in memory BEFORE touching Banking reservation state
        payment.rejectBeforeCommitment(command.reasonCode(), command.reasonDetail(), nextTimestamp(payment));

        bankingService.releaseReservation(
                payment.getInstruction().sourceAccountId(),
                payment.getReservationToken().orElseThrow()
        );

        paymentMapper.updateEntity(payment, entity);
        paymentRepository.save(entity);
        return payment.toView();
    }

    /**
     * Marks a payment as failed after the Financial Commitment Point but prior to settlement
     * ({@code EXECUTING} or {@code PENDING_EXTERNAL -> FAILED}) for confirmed unrecoverable failures,
     * releasing its active Banking fund reservation in the same transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentView failPayment(FailPaymentCommand command) {
        if (command == null) {
            throw new PaymentValidationException("FailPaymentCommand cannot be null.");
        }

        PaymentEntity entity = loadEntityOrThrow(command.paymentId());
        Payment payment = paymentMapper.toDomain(entity);

        if (payment.getStatus() == PaymentStatus.FAILED) {
            if (payment.getStatusReasonCode().orElse(null) == command.reasonCode()) {
                return payment.toView();
            }
            throw new InvalidPaymentStateException(
                    "Payment has already FAILED with a different reasonCode: "
                            + payment.getStatusReasonCode().orElse(null)
            );
        }

        // Validate domain transition in memory BEFORE touching Banking reservation state
        payment.failBeforeSettlement(command.reasonCode(), command.reasonDetail(), nextTimestamp(payment));

        bankingService.releaseReservation(
                payment.getInstruction().sourceAccountId(),
                payment.getReservationToken().orElseThrow()
        );

        paymentMapper.updateEntity(payment, entity);
        paymentRepository.save(entity);
        return payment.toView();
    }

    /**
     * Records a confirmed settlement outcome ({@code PENDING_EXTERNAL -> SETTLED}) via
     * {@link PaymentSettlementPort}.
     *
     * <p>Final reservation consumption and double-entry posting are coordinated by the
     * future Settlement module alongside this port.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentView recordSettlement(RecordPaymentSettlementCommand command) {
        if (command == null) {
            throw new PaymentValidationException("RecordPaymentSettlementCommand cannot be null.");
        }

        PaymentEntity entity = loadEntityOrThrow(command.paymentId());
        Payment payment = paymentMapper.toDomain(entity);

        if (payment.getStatus() == PaymentStatus.SETTLED
                && Objects.equals(payment.getLedgerTransactionId().orElse(null), command.ledgerTransactionId())) {
            return payment.toView();
        }

        payment.recordSettlement(command.ledgerTransactionId(), nextTimestamp(payment));
        paymentMapper.updateEntity(payment, entity);
        paymentRepository.save(entity);
        return payment.toView();
    }

    /**
     * Looks up a payment by its unique {@link PaymentId}.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentView> findPayment(PaymentId paymentId) {
        if (paymentId == null) {
            throw new PaymentValidationException("paymentId cannot be null.");
        }
        return paymentRepository.findById(paymentId.getValue())
                .map(paymentMapper::toDomain)
                .map(Payment::toView);
    }

    /**
     * Looks up a payment by its idempotency key {@code (sourceAccountId, paymentReference)}.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentView> findBySourceAccountAndReference(
            AccountId sourceAccountId,
            PaymentReference paymentReference) {
        if (sourceAccountId == null) {
            throw new PaymentValidationException("sourceAccountId cannot be null.");
        }
        if (paymentReference == null) {
            throw new PaymentValidationException("paymentReference cannot be null.");
        }
        return paymentRepository.findBySourceAccountIdAndPaymentReference(
                        sourceAccountId.getValue(),
                        paymentReference.getValue()
                )
                .map(paymentMapper::toDomain)
                .map(Payment::toView);
    }

    private PaymentEntity loadEntityOrThrow(PaymentId paymentId) {
        return paymentRepository.findById(paymentId.getValue())
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private static Instant nextTimestamp(Payment payment) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return now.isBefore(payment.getUpdatedAt()) ? payment.getUpdatedAt() : now;
    }

    private static String sanitizeReasonDetail(String rawMessage, PaymentReasonCode fallbackCode) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return fallbackCode.name();
        }
        StringBuilder cleaned = new StringBuilder(rawMessage.length());
        rawMessage.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                cleaned.append(' ');
            } else {
                cleaned.appendCodePoint(cp);
            }
        });
        String trimmed = cleaned.toString().trim();
        if (trimmed.isEmpty()) {
            return fallbackCode.name();
        }
        if (trimmed.length() <= PaymentLimits.MAX_REASON_DETAIL_LENGTH) {
            return trimmed;
        }
        int endIndex = PaymentLimits.MAX_REASON_DETAIL_LENGTH;
        if (Character.isHighSurrogate(trimmed.charAt(endIndex - 1))) {
            endIndex--;
        }
        String truncated = trimmed.substring(0, endIndex).trim();
        return truncated.isEmpty() ? fallbackCode.name() : truncated;
    }

    private static void markCurrentTransactionRollbackOnly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
            // No active Spring-managed transaction to mark
        }
    }
}
