package com.crosspaynet.messaging;

import com.crosspaynet.messaging.contracts.AcknowledgeMessageCommand;
import com.crosspaynet.messaging.contracts.DispatchPaymentMessageCommand;
import com.crosspaynet.messaging.contracts.InvalidMessageStateException;
import com.crosspaynet.messaging.contracts.MessageConflictException;
import com.crosspaynet.messaging.contracts.MessageId;
import com.crosspaynet.messaging.contracts.MessageNotFoundException;
import com.crosspaynet.messaging.contracts.MessagingValidationException;
import com.crosspaynet.messaging.contracts.NacknowledgeMessageCommand;
import com.crosspaynet.messaging.contracts.PaymentMessageStatus;
import com.crosspaynet.messaging.contracts.PaymentMessageView;
import com.crosspaynet.messaging.contracts.PaymentSentToNetwork;
import com.crosspaynet.messaging.contracts.TimeoutMessageCommand;
import com.crosspaynet.messaging.domain.MessagingLimits;
import com.crosspaynet.messaging.domain.PaymentMessage;
import com.crosspaynet.messaging.domain.PersistedMessageStateException;
import com.crosspaynet.messaging.infrastructure.persistence.PaymentMessageEntity;
import com.crosspaynet.messaging.infrastructure.persistence.PaymentMessageMapper;
import com.crosspaynet.messaging.infrastructure.persistence.PaymentMessageRepository;
import com.crosspaynet.payment.PaymentService;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.FailPaymentCommand;
import com.crosspaynet.payment.contracts.InvalidPaymentStateException;
import com.crosspaynet.payment.contracts.MarkPaymentDispatchedCommand;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentNotFoundException;
import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional orchestration service for ISO 20022-inspired financial messaging (Phase 5).
 *
 * <p>Manages {@link PaymentMessage} transmission attempts ({@code GENERATED -> SIGNED -> SENT ->
 * ACKNOWLEDGED | NACKNOWLEDGED | TIMEOUT}), coordinates initial network dispatch
 * ({@code EXECUTING -> PENDING_EXTERNAL}) and confirmed negative-acknowledgment failure
 * ({@code PENDING_EXTERNAL -> FAILED}) with {@link PaymentService}, and remains strictly
 * decoupled from {@code Banking}, {@code Ledger}, and {@code Settlement}.
 */
@Service
public class MessagingService {

    private final PaymentMessageRepository messageRepository;
    private final PaymentMessageMapper messageMapper;
    private final PaymentService paymentService;
    private final ApplicationEventPublisher eventPublisher;

    public MessagingService(
            PaymentMessageRepository messageRepository,
            PaymentMessageMapper messageMapper,
            PaymentService paymentService,
            ApplicationEventPublisher eventPublisher) {
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository cannot be null.");
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper cannot be null.");
        this.paymentService = Objects.requireNonNull(paymentService, "paymentService cannot be null.");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null.");
    }

    /**
     * Generates, integrity-signs ({@code crosspaynet-message-digest-v1}), and dispatches a
     * {@code pacs.008.001.08}-inspired {@link PaymentMessage} attempt ({@code GENERATED -> SIGNED -> SENT}).
     *
     * <ul>
     *   <li>Attempt 1 transitions the underlying {@code Payment} from {@link PaymentStatus#EXECUTING}
     *       to {@link PaymentStatus#PENDING_EXTERNAL} via {@link PaymentService#markDispatched},
     *       setting the payment's initial {@code externalDispatchReference} to the attempt-1
     *       {@code messageId}.</li>
     *   <li>Subsequent attempts ({@code attemptNumber > 1}) require the payment to be in
     *       {@link PaymentStatus#PENDING_EXTERNAL} and the immediately preceding attempt
     *       ({@code attemptNumber - 1}) to have reached {@link PaymentMessageStatus#TIMEOUT},
     *       preserving the payment's original {@code externalDispatchReference}.</li>
     *   <li>Duplicate calls for the same {@code (paymentId, attemptNumber)} with matching
     *       {@code senderBic} return the existing {@link PaymentMessageView} idempotently;
     *       calls with a conflicting {@code senderBic} throw {@link MessageConflictException}.</li>
     * </ul>
     */
    @Transactional(
            propagation = Propagation.REQUIRED,
            noRollbackFor = MessageConflictException.class
    )
    public PaymentMessageView dispatchPaymentMessage(DispatchPaymentMessageCommand command) {
        if (command == null) {
            throw new MessagingValidationException("DispatchPaymentMessageCommand cannot be null.");
        }

        PaymentView payment = paymentService.findPayment(command.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(command.paymentId()));

        Optional<PaymentMessageEntity> existingOpt = messageRepository.findByPaymentIdAndAttemptNumber(
                command.paymentId().getValue(),
                command.attemptNumber()
        );
        if (existingOpt.isPresent()) {
            PaymentMessage existing = messageMapper.toDomain(existingOpt.get());
            return verifyExistingAttemptMatch(existing, command);
        }

        validateDispatchEligibilityForNewAttempt(payment, command);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        BeneficiaryDetails beneficiary = payment.instruction().beneficiary();
        PaymentMessage candidate = PaymentMessage.generate(
                MessageId.generate(),
                payment.paymentId(),
                command.attemptNumber(),
                command.senderBic(),
                beneficiary.beneficiaryBankBic(),
                payment.routeReference(),
                payment.instruction().amount(),
                beneficiary.beneficiaryName(),
                beneficiary.beneficiaryAccountNumber(),
                now
        );
        candidate.sign(now);
        candidate.markSent(now);

        int inserted = messageRepository.insertClaimOnConflictDoNothing(
                candidate.getId().getValue(),
                candidate.getPaymentId().getValue(),
                candidate.getAttemptNumber(),
                candidate.getIdempotencyKey(),
                candidate.getMessageType().name(),
                candidate.getSenderBic().getValue(),
                candidate.getReceiverBic().getValue(),
                candidate.getRouteReference(),
                candidate.getAmount().getAmount(),
                candidate.getAmount().getCurrency().getCode(),
                candidate.getBeneficiaryName(),
                candidate.getBeneficiaryAccountNumber(),
                candidate.getPayload(),
                candidate.getPayloadDigest().getValue(),
                candidate.getStatus().name(),
                null,
                null,
                null,
                null,
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );

        if (inserted == 0) {
            PaymentMessageEntity raceWinnerEntity = messageRepository.findByPaymentIdAndAttemptNumber(
                            command.paymentId().getValue(),
                            command.attemptNumber()
                    )
                    .orElseThrow(() -> new PersistedMessageStateException(
                            "PaymentMessage claim conflicted on (paymentId=" + command.paymentId()
                                    + ", attemptNumber=" + command.attemptNumber()
                                    + "), but existing row could not be found."
                    ));
            PaymentMessage raceWinner = messageMapper.toDomain(raceWinnerEntity);
            return verifyExistingAttemptMatch(raceWinner, command);
        }

        try {
            if (command.attemptNumber() == 1) {
                paymentService.markDispatched(new MarkPaymentDispatchedCommand(
                        payment.paymentId(),
                        candidate.getId().getValue().toString()
                ));
            }
            UUID eventId = UUID.randomUUID();
            eventPublisher.publishEvent(new PaymentSentToNetwork(
                    eventId,
                    candidate.getPaymentId().getValue(),
                    candidate.getId().getValue(),
                    candidate.getAttemptNumber(),
                    candidate.getRouteReference(),
                    candidate.getUpdatedAt(),
                    candidate.getPaymentId().getValue().toString(),
                    null
            ));
            return candidate.toView();
        } catch (RuntimeException | Error ex) {
            markCurrentTransactionRollbackOnly();
            throw ex;
        }
    }

    /**
     * Records a positive peer acknowledgment ({@code SENT -> ACKNOWLEDGED}) on a dispatched
     * {@link PaymentMessage}.
     *
     * <p>Does NOT transition the {@code Payment} to {@code SETTLED} or post Ledger entries;
     * final settlement remains owned by {@code SettlementService.settlePayment}.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentMessageView acknowledgeMessage(AcknowledgeMessageCommand command) {
        if (command == null) {
            throw new MessagingValidationException("AcknowledgeMessageCommand cannot be null.");
        }

        PaymentMessageEntity entity = loadEntityOrThrow(command.messageId());
        PaymentMessage message = messageMapper.toDomain(entity);

        if (message.getStatus() == PaymentMessageStatus.ACKNOWLEDGED
                && Objects.equals(
                        message.getAcknowledgmentReference().orElse(null),
                        command.acknowledgmentReference())) {
            return message.toView();
        }

        PaymentView payment = paymentService.findPayment(message.getPaymentId())
                .orElseThrow(() -> new PaymentNotFoundException(message.getPaymentId()));
        if (payment.status() != PaymentStatus.PENDING_EXTERNAL
                && payment.status() != PaymentStatus.SETTLED) {
            throw new InvalidPaymentStateException(
                    "Cannot acknowledge PaymentMessage when Payment '" + payment.paymentId()
                            + "' is in state: " + payment.status()
                            + " (must be PENDING_EXTERNAL or SETTLED)."
            );
        }

        try {
            message.acknowledge(command.acknowledgmentReference(), nextTimestamp(message));
            messageMapper.updateEntity(message, entity);
            messageRepository.save(entity);
            return message.toView();
        } catch (RuntimeException | Error ex) {
            markCurrentTransactionRollbackOnly();
            throw ex;
        }
    }

    /**
     * Records a negative peer acknowledgment ({@code SENT -> NACKNOWLEDGED}) on a dispatched
     * {@link PaymentMessage} and atomically fails the underlying {@code Payment}
     * ({@code PENDING_EXTERNAL -> FAILED} with {@link PaymentReasonCode#EXTERNAL_NETWORK_REJECTED},
     * releasing its operational Banking fund reservation in the same transaction).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentMessageView nacknowledgeMessage(NacknowledgeMessageCommand command) {
        if (command == null) {
            throw new MessagingValidationException("NacknowledgeMessageCommand cannot be null.");
        }

        PaymentMessageEntity entity = loadEntityOrThrow(command.messageId());
        PaymentMessage message = messageMapper.toDomain(entity);

        String failReasonDetail = formatFailureReasonDetail(
                command.peerRejectionCode(),
                command.peerRejectionReason()
        );

        if (message.getStatus() == PaymentMessageStatus.NACKNOWLEDGED) {
            if (Objects.equals(message.getPeerRejectionCode().orElse(null), command.peerRejectionCode())
                    && Objects.equals(message.getPeerRejectionReason().orElse(null), command.peerRejectionReason())) {
                try {
                    paymentService.failPayment(new FailPaymentCommand(
                            message.getPaymentId(),
                            PaymentReasonCode.EXTERNAL_NETWORK_REJECTED,
                            failReasonDetail
                    ));
                    return message.toView();
                } catch (RuntimeException | Error ex) {
                    markCurrentTransactionRollbackOnly();
                    throw ex;
                }
            }
            throw new InvalidMessageStateException(
                    "PaymentMessage is already NACKNOWLEDGED with different peer rejection parameters."
            );
        }

        // Validate domain transition in memory BEFORE mutating Payment/Banking state
        message.nacknowledge(
                command.peerRejectionCode(),
                command.peerRejectionReason(),
                nextTimestamp(message)
        );

        try {
            paymentService.failPayment(new FailPaymentCommand(
                    message.getPaymentId(),
                    PaymentReasonCode.EXTERNAL_NETWORK_REJECTED,
                    failReasonDetail
            ));
            messageMapper.updateEntity(message, entity);
            messageRepository.save(entity);
            return message.toView();
        } catch (RuntimeException | Error ex) {
            markCurrentTransactionRollbackOnly();
            throw ex;
        }
    }

    /**
     * Records a transmission timeout ({@code SENT -> TIMEOUT}) on a dispatched {@link PaymentMessage},
     * preserving the underlying {@code Payment} in {@link PaymentStatus#PENDING_EXTERNAL} with its
     * operational Banking reservation active so a retry attempt ({@code attemptNumber + 1}) can be dispatched.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentMessageView markMessageTimedOut(TimeoutMessageCommand command) {
        if (command == null) {
            throw new MessagingValidationException("TimeoutMessageCommand cannot be null.");
        }

        PaymentMessageEntity entity = loadEntityOrThrow(command.messageId());
        PaymentMessage message = messageMapper.toDomain(entity);

        if (message.getStatus() == PaymentMessageStatus.TIMEOUT
                && Objects.equals(message.getTimeoutReason().orElse(null), command.timeoutReason())) {
            return message.toView();
        }

        PaymentView payment = paymentService.findPayment(message.getPaymentId())
                .orElseThrow(() -> new PaymentNotFoundException(message.getPaymentId()));
        if (payment.status() != PaymentStatus.PENDING_EXTERNAL) {
            throw new InvalidPaymentStateException(
                    "Cannot mark PaymentMessage as TIMEOUT when Payment '" + payment.paymentId()
                            + "' is in state: " + payment.status()
                            + " (must be PENDING_EXTERNAL)."
            );
        }

        try {
            message.markTimeout(command.timeoutReason(), nextTimestamp(message));
            messageMapper.updateEntity(message, entity);
            messageRepository.save(entity);
            return message.toView();
        } catch (RuntimeException | Error ex) {
            markCurrentTransactionRollbackOnly();
            throw ex;
        }
    }

    /**
     * Looks up a {@link PaymentMessageView} by its unique {@link MessageId}.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentMessageView> findMessage(MessageId messageId) {
        if (messageId == null) {
            throw new MessagingValidationException("messageId cannot be null.");
        }
        return messageRepository.findById(messageId.getValue())
                .map(messageMapper::toDomain)
                .map(PaymentMessage::toView);
    }

    /**
     * Looks up a {@link PaymentMessageView} by {@code (paymentId, attemptNumber)}.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentMessageView> findByPaymentAndAttempt(PaymentId paymentId, int attemptNumber) {
        if (paymentId == null) {
            throw new MessagingValidationException("paymentId cannot be null.");
        }
        if (attemptNumber < MessagingLimits.MIN_ATTEMPT_NUMBER
                || attemptNumber > MessagingLimits.MAX_ATTEMPT_NUMBER) {
            throw new MessagingValidationException(
                    "attemptNumber must be between " + MessagingLimits.MIN_ATTEMPT_NUMBER
                            + " and " + MessagingLimits.MAX_ATTEMPT_NUMBER + "."
            );
        }
        return messageRepository.findByPaymentIdAndAttemptNumber(paymentId.getValue(), attemptNumber)
                .map(messageMapper::toDomain)
                .map(PaymentMessage::toView);
    }

    /**
     * Lists all {@link PaymentMessageView} attempts for a given {@link PaymentId} ordered by
     * ascending {@code attemptNumber}.
     */
    @Transactional(readOnly = true)
    public List<PaymentMessageView> findMessagesForPayment(PaymentId paymentId) {
        if (paymentId == null) {
            throw new MessagingValidationException("paymentId cannot be null.");
        }
        return messageRepository.findByPaymentIdOrderByAttemptNumberAsc(paymentId.getValue())
                .stream()
                .map(messageMapper::toDomain)
                .map(PaymentMessage::toView)
                .toList();
    }

    private void validateDispatchEligibilityForNewAttempt(
            PaymentView payment,
            DispatchPaymentMessageCommand command) {
        if (payment.routeReference() == null || payment.routeReference().isBlank()) {
            throw new InvalidPaymentStateException(
                    "Cannot dispatch PaymentMessage for Payment '" + payment.paymentId()
                            + "' without a committed routeReference."
            );
        }

        if (command.attemptNumber() == 1) {
            if (payment.status() != PaymentStatus.EXECUTING) {
                throw new InvalidPaymentStateException(
                        "Cannot dispatch initial PaymentMessage (attempt 1) when Payment '"
                                + payment.paymentId() + "' is in state: " + payment.status()
                                + " (must be EXECUTING)."
                );
            }
            return;
        }

        if (payment.status() != PaymentStatus.PENDING_EXTERNAL) {
            throw new InvalidPaymentStateException(
                    "Cannot dispatch retry PaymentMessage (attempt " + command.attemptNumber()
                            + ") when Payment '" + payment.paymentId() + "' is in state: "
                            + payment.status() + " (must be PENDING_EXTERNAL)."
            );
        }

        int previousAttemptNumber = command.attemptNumber() - 1;
        PaymentMessageEntity previousEntity = messageRepository.findByPaymentIdAndAttemptNumber(
                        payment.paymentId().getValue(),
                        previousAttemptNumber
                )
                .orElseThrow(() -> new InvalidMessageStateException(
                        "Cannot dispatch attempt " + command.attemptNumber() + " for Payment '"
                                + payment.paymentId() + "': preceding attempt " + previousAttemptNumber
                                + " does not exist."
                ));
        PaymentMessage previousAttempt = messageMapper.toDomain(previousEntity);
        if (previousAttempt.getStatus() != PaymentMessageStatus.TIMEOUT) {
            throw new InvalidMessageStateException(
                    "Cannot dispatch attempt " + command.attemptNumber() + " for Payment '"
                            + payment.paymentId() + "' while attempt " + previousAttemptNumber
                            + " is in state: " + previousAttempt.getStatus() + " (must be TIMEOUT)."
            );
        }
    }

    private PaymentMessageView verifyExistingAttemptMatch(
            PaymentMessage existing,
            DispatchPaymentMessageCommand command) {
        if (!existing.getSenderBic().equals(command.senderBic())) {
            throw new MessageConflictException(
                    "PaymentMessage attempt " + command.attemptNumber() + " for Payment '"
                            + command.paymentId() + "' already exists with senderBic '"
                            + existing.getSenderBic() + "', conflicting with requested senderBic '"
                            + command.senderBic() + "'."
            );
        }
        return existing.toView();
    }

    private PaymentMessageEntity loadEntityOrThrow(MessageId messageId) {
        return messageRepository.findById(messageId.getValue())
                .orElseThrow(() -> new MessageNotFoundException(messageId));
    }

    private static Instant nextTimestamp(PaymentMessage message) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return now.isBefore(message.getUpdatedAt()) ? message.getUpdatedAt() : now;
    }

    private static String formatFailureReasonDetail(String peerRejectionCode, String peerRejectionReason) {
        if (peerRejectionReason == null || peerRejectionReason.isBlank()) {
            return "Peer NACK [" + peerRejectionCode + "]";
        }
        String combined = "Peer NACK [" + peerRejectionCode + "]: " + peerRejectionReason;
        if (combined.length() <= MessagingLimits.MAX_PEER_REJECTION_REASON_LENGTH) {
            return combined;
        }
        int endIndex = MessagingLimits.MAX_PEER_REJECTION_REASON_LENGTH;
        if (Character.isHighSurrogate(combined.charAt(endIndex - 1))) {
            endIndex--;
        }
        return combined.substring(0, endIndex).trim();
    }

    private static void markCurrentTransactionRollbackOnly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
            // No active Spring-managed transaction to mark
        }
    }
}
