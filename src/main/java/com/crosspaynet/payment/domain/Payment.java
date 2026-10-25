package com.crosspaynet.payment.domain;

import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.payment.contracts.InvalidPaymentStateException;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentInstruction;
import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentValidationException;
import com.crosspaynet.payment.contracts.PaymentView;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate root of the Payment module orchestrating the cross-border payment state machine,
 * financial commitment point, and correlated references.
 */
public final class Payment {

    private final PaymentId paymentId;
    private final PaymentInstruction instruction;
    private final InstructionFingerprint instructionFingerprint;
    private PaymentStatus status;
    private ReservationToken reservationToken;
    private String routeReference;
    private String externalDispatchReference;
    private UUID ledgerTransactionId;
    private PaymentReasonCode statusReasonCode;
    private String statusReasonDetail;
    private final Instant createdAt;
    private Instant updatedAt;

    private Payment(
            PaymentId paymentId,
            PaymentInstruction instruction,
            InstructionFingerprint instructionFingerprint,
            PaymentStatus status,
            ReservationToken reservationToken,
            String routeReference,
            String externalDispatchReference,
            UUID ledgerTransactionId,
            PaymentReasonCode statusReasonCode,
            String statusReasonDetail,
            Instant createdAt,
            Instant updatedAt) {
        this.paymentId = paymentId;
        this.instruction = instruction;
        this.instructionFingerprint = instructionFingerprint;
        this.status = status;
        this.reservationToken = reservationToken;
        this.routeReference = routeReference;
        this.externalDispatchReference = externalDispatchReference;
        this.ledgerTransactionId = ledgerTransactionId;
        this.statusReasonCode = statusReasonCode;
        this.statusReasonDetail = statusReasonDetail;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Initiates a new Payment in {@link PaymentStatus#VALIDATING} state with a generated {@link PaymentId}
     * and current timestamp.
     */
    public static Payment initiate(PaymentInstruction instruction) {
        return initiate(PaymentId.generate(), instruction, Instant.now());
    }

    /**
     * Initiates a new Payment in {@link PaymentStatus#VALIDATING} state.
     */
    public static Payment initiate(PaymentId paymentId, PaymentInstruction instruction, Instant createdAt) {
        if (paymentId == null) {
            throw new PaymentValidationException("paymentId cannot be null.");
        }
        if (instruction == null) {
            throw new PaymentValidationException("instruction cannot be null.");
        }
        if (createdAt == null) {
            throw new PaymentValidationException("createdAt cannot be null.");
        }
        PaymentSupportedCurrencyPolicy.requireSupported(instruction.amount().getCurrency());
        InstructionFingerprint fingerprint = InstructionFingerprint.compute(instruction);

        return new Payment(
                paymentId,
                instruction,
                fingerprint,
                PaymentStatus.VALIDATING,
                null,
                null,
                null,
                null,
                null,
                null,
                createdAt,
                createdAt
        );
    }

    /**
     * Restores a persisted {@code Payment} aggregate and strictly verifies all state-specific
     * field invariants and fingerprint integrity.
     *
     * @throws PersistedPaymentStateException if any persisted attribute or state combination is corrupted
     */
    public static Payment restore(
            PaymentId paymentId,
            PaymentInstruction instruction,
            InstructionFingerprint instructionFingerprint,
            PaymentStatus status,
            ReservationToken reservationToken,
            String routeReference,
            String externalDispatchReference,
            UUID ledgerTransactionId,
            PaymentReasonCode statusReasonCode,
            String statusReasonDetail,
            Instant createdAt,
            Instant updatedAt) {
        if (paymentId == null) {
            throw new PersistedPaymentStateException("Restored payment cannot have null paymentId.");
        }
        if (instruction == null) {
            throw new PersistedPaymentStateException("Restored payment cannot have null instruction.");
        }
        if (instructionFingerprint == null) {
            throw new PersistedPaymentStateException("Restored payment cannot have null instructionFingerprint.");
        }
        if (status == null) {
            throw new PersistedPaymentStateException("Restored payment cannot have null status.");
        }
        if (createdAt == null) {
            throw new PersistedPaymentStateException("Restored payment cannot have null createdAt.");
        }
        if (updatedAt == null) {
            throw new PersistedPaymentStateException("Restored payment cannot have null updatedAt.");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new PersistedPaymentStateException("Restored payment updatedAt cannot be before createdAt.");
        }
        if (!PaymentSupportedCurrencyPolicy.isSupported(instruction.amount().getCurrency())) {
            throw new PersistedPaymentStateException(
                    "Restored payment has unsupported currency: " + instruction.amount().getCurrency()
            );
        }

        InstructionFingerprint expectedFingerprint = InstructionFingerprint.compute(instruction);
        if (!expectedFingerprint.equals(instructionFingerprint)) {
            throw new PersistedPaymentStateException(
                    "Restored payment instructionFingerprint (" + instructionFingerprint
                            + ") does not match computed fingerprint (" + expectedFingerprint + ")."
            );
        }

        validateRestoredOptionalText(
                routeReference,
                "routeReference",
                PaymentLimits.MAX_ROUTE_REFERENCE_LENGTH
        );
        validateRestoredOptionalText(
                externalDispatchReference,
                "externalDispatchReference",
                PaymentLimits.MAX_EXTERNAL_DISPATCH_REFERENCE_LENGTH
        );
        validateRestoredOptionalText(
                statusReasonDetail,
                "statusReasonDetail",
                PaymentLimits.MAX_REASON_DETAIL_LENGTH
        );

        validateRestoredStateInvariants(
                status,
                reservationToken,
                routeReference,
                externalDispatchReference,
                ledgerTransactionId,
                statusReasonCode,
                statusReasonDetail
        );

        return new Payment(
                paymentId,
                instruction,
                instructionFingerprint,
                status,
                reservationToken,
                routeReference,
                externalDispatchReference,
                ledgerTransactionId,
                statusReasonCode,
                statusReasonDetail,
                createdAt,
                updatedAt
        );
    }

    /**
     * Records that Banking successfully reserved funds ({@code VALIDATING -> COMPLIANCE_HOLD}).
     */
    public void confirmFundsReserved(ReservationToken token) {
        confirmFundsReserved(token, Instant.now());
    }

    public void confirmFundsReserved(ReservationToken token, Instant timestamp) {
        if (token == null) {
            throw new PaymentValidationException("ReservationToken cannot be null.");
        }
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentStatus.COMPLIANCE_HOLD) {
            if (token.equals(this.reservationToken)) {
                return;
            }
            throw new InvalidPaymentStateException(
                    "Payment already has a different ReservationToken in COMPLIANCE_HOLD."
            );
        }
        if (this.status != PaymentStatus.VALIDATING) {
            throw new InvalidPaymentStateException(
                    "Cannot confirm funds reserved when payment is in state: " + this.status
            );
        }

        this.reservationToken = token;
        this.status = PaymentStatus.COMPLIANCE_HOLD;
        this.updatedAt = timestamp;
    }

    /**
     * Transitions {@code VALIDATING -> REJECTED} when Banking rejects the initial reservation request
     * with a pre-write business exception ({@code INSUFFICIENT_FUNDS}, {@code INVALID_ACCOUNT_STATE},
     * or {@code CURRENCY_MISMATCH}).
     */
    public void rejectOnReservationFailure(PaymentReasonCode reasonCode, String reasonDetail) {
        rejectOnReservationFailure(reasonCode, reasonDetail, Instant.now());
    }

    public void rejectOnReservationFailure(
            PaymentReasonCode reasonCode,
            String reasonDetail,
            Instant timestamp) {
        if (reasonCode == null || !reasonCode.isReservationFailureReason()) {
            throw new PaymentValidationException(
                    "rejectOnReservationFailure requires INSUFFICIENT_FUNDS, INVALID_ACCOUNT_STATE, "
                            + "or CURRENCY_MISMATCH, but got: " + reasonCode
            );
        }
        String normalizedDetail = normalizeOptionalReasonDetail(reasonDetail);
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentStatus.REJECTED) {
            if (this.reservationToken == null && this.statusReasonCode == reasonCode) {
                return;
            }
            throw new InvalidPaymentStateException(
                    "Payment is already REJECTED with different reservation/reason state."
            );
        }
        if (this.status != PaymentStatus.VALIDATING) {
            throw new InvalidPaymentStateException(
                    "Cannot reject on reservation failure when payment is in state: " + this.status
            );
        }

        this.status = PaymentStatus.REJECTED;
        this.statusReasonCode = reasonCode;
        this.statusReasonDetail = normalizedDetail;
        this.updatedAt = timestamp;
    }

    /**
     * Records compliance clearance ({@code COMPLIANCE_HOLD -> ROUTING}).
     */
    public void approveCompliance() {
        approveCompliance(Instant.now());
    }

    public void approveCompliance(Instant timestamp) {
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentStatus.ROUTING) {
            return;
        }
        if (this.status != PaymentStatus.COMPLIANCE_HOLD) {
            throw new InvalidPaymentStateException(
                    "Cannot approve compliance when payment is in state: " + this.status
            );
        }

        this.status = PaymentStatus.ROUTING;
        this.updatedAt = timestamp;
    }

    /**
     * Commits the payment to execution ({@code ROUTING -> EXECUTING}) at the Financial Commitment Point.
     */
    public void commitToExecution(String routeReference) {
        commitToExecution(routeReference, Instant.now());
    }

    public void commitToExecution(String routeReference, Instant timestamp) {
        String normalizedRouteRef = validateRequiredReference(
                routeReference,
                "routeReference",
                PaymentLimits.MAX_ROUTE_REFERENCE_LENGTH
        );
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentStatus.EXECUTING) {
            if (normalizedRouteRef.equals(this.routeReference)) {
                return;
            }
            throw new InvalidPaymentStateException(
                    "Payment is already committed in EXECUTING with a different routeReference."
            );
        }
        if (this.status != PaymentStatus.ROUTING) {
            throw new InvalidPaymentStateException(
                    "Cannot commit to execution when payment is in state: " + this.status
            );
        }

        this.routeReference = normalizedRouteRef;
        this.status = PaymentStatus.EXECUTING;
        this.updatedAt = timestamp;
    }

    /**
     * Marks a committed payment as dispatched to the external network ({@code EXECUTING -> PENDING_EXTERNAL}).
     */
    public void markDispatched(String externalDispatchReference) {
        markDispatched(externalDispatchReference, Instant.now());
    }

    public void markDispatched(String externalDispatchReference, Instant timestamp) {
        String normalizedDispatchRef = validateRequiredReference(
                externalDispatchReference,
                "externalDispatchReference",
                PaymentLimits.MAX_EXTERNAL_DISPATCH_REFERENCE_LENGTH
        );
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentStatus.PENDING_EXTERNAL) {
            if (normalizedDispatchRef.equals(this.externalDispatchReference)) {
                return;
            }
            throw new InvalidPaymentStateException(
                    "Payment is already in PENDING_EXTERNAL with a different externalDispatchReference."
            );
        }
        if (this.status != PaymentStatus.EXECUTING) {
            throw new InvalidPaymentStateException(
                    "Cannot mark dispatched when payment is in state: " + this.status
            );
        }

        this.externalDispatchReference = normalizedDispatchRef;
        this.status = PaymentStatus.PENDING_EXTERNAL;
        this.updatedAt = timestamp;
    }

    /**
     * Records a confirmed settlement outcome ({@code PENDING_EXTERNAL -> SETTLED}).
     *
     * <p>{@code EXECUTING -> SETTLED} is strictly forbidden; only {@code PENDING_EXTERNAL} may transition
     * to {@code SETTLED}.
     */
    public void recordSettlement(UUID ledgerTransactionId) {
        recordSettlement(ledgerTransactionId, Instant.now());
    }

    public void recordSettlement(UUID ledgerTransactionId, Instant timestamp) {
        if (ledgerTransactionId == null) {
            throw new PaymentValidationException("ledgerTransactionId cannot be null.");
        }
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentStatus.SETTLED) {
            if (ledgerTransactionId.equals(this.ledgerTransactionId)) {
                return;
            }
            throw new InvalidPaymentStateException(
                    "Payment is already SETTLED with a different ledgerTransactionId."
            );
        }
        if (this.status != PaymentStatus.PENDING_EXTERNAL) {
            throw new InvalidPaymentStateException(
                    "Cannot record settlement when payment is in state: " + this.status
                            + " (must be PENDING_EXTERNAL)."
            );
        }

        this.ledgerTransactionId = ledgerTransactionId;
        this.status = PaymentStatus.SETTLED;
        this.updatedAt = timestamp;
    }

    /**
     * Cancels a payment prior to the Financial Commitment Point
     * ({@code VALIDATING}, {@code COMPLIANCE_HOLD}, or {@code ROUTING} -> {@code CANCELLED}).
     *
     * <p>Idempotent no-op if already {@code CANCELLED}. Strictly forbidden at or after {@code EXECUTING}.
     */
    public void cancel(String reasonDetail) {
        cancel(reasonDetail, Instant.now());
    }

    public void cancel(String reasonDetail, Instant timestamp) {
        String normalizedDetail = normalizeOptionalReasonDetail(reasonDetail);
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentStatus.CANCELLED) {
            return;
        }
        if (!this.status.isPreCommitment()) {
            throw new InvalidPaymentStateException(
                    "Cannot cancel payment in state: " + this.status
                            + " (cancellation is only permitted before the Financial Commitment Point)."
            );
        }

        this.status = PaymentStatus.CANCELLED;
        this.statusReasonCode = PaymentReasonCode.CUSTOMER_CANCELLED;
        this.statusReasonDetail = normalizedDetail;
        this.updatedAt = timestamp;
    }

    /**
     * Rejects a reserved payment prior to the Financial Commitment Point
     * ({@code COMPLIANCE_HOLD} or {@code ROUTING} -> {@code REJECTED}) due to compliance or routing rules.
     */
    public void rejectBeforeCommitment(PaymentReasonCode reasonCode, String reasonDetail) {
        rejectBeforeCommitment(reasonCode, reasonDetail, Instant.now());
    }

    public void rejectBeforeCommitment(
            PaymentReasonCode reasonCode,
            String reasonDetail,
            Instant timestamp) {
        if (reasonCode == null || !reasonCode.isPreCommitmentRejectionReason()) {
            throw new PaymentValidationException(
                    "rejectBeforeCommitment requires COMPLIANCE_REJECTED or NO_ROUTE_AVAILABLE, "
                            + "but got: " + reasonCode
            );
        }
        String normalizedDetail = normalizeOptionalReasonDetail(reasonDetail);
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentStatus.REJECTED) {
            if (this.reservationToken != null && this.statusReasonCode == reasonCode) {
                return;
            }
            throw new InvalidPaymentStateException(
                    "Payment is already REJECTED with a different reasonCode or pre-reservation state."
            );
        }
        if (this.status != PaymentStatus.COMPLIANCE_HOLD && this.status != PaymentStatus.ROUTING) {
            throw new InvalidPaymentStateException(
                    "Cannot reject before commitment when payment is in state: " + this.status
                            + " (must be COMPLIANCE_HOLD or ROUTING)."
            );
        }

        this.status = PaymentStatus.REJECTED;
        this.statusReasonCode = reasonCode;
        this.statusReasonDetail = normalizedDetail;
        this.updatedAt = timestamp;
    }

    /**
     * Marks a payment as failed after the Financial Commitment Point but before financial settlement
     * ({@code EXECUTING} or {@code PENDING_EXTERNAL} -> {@code FAILED}).
     *
     * <p>Only for confirmed unrecoverable failures before settlement. Ambiguous or unknown external
     * outcomes must remain in {@code PENDING_EXTERNAL}.
     */
    public void failBeforeSettlement(PaymentReasonCode reasonCode, String reasonDetail) {
        failBeforeSettlement(reasonCode, reasonDetail, Instant.now());
    }

    public void failBeforeSettlement(
            PaymentReasonCode reasonCode,
            String reasonDetail,
            Instant timestamp) {
        if (reasonCode == null || !reasonCode.isPostCommitmentFailureReason()) {
            throw new PaymentValidationException(
                    "failBeforeSettlement requires EXTERNAL_NETWORK_REJECTED or OPERATIONAL_FAILURE, "
                            + "but got: " + reasonCode
            );
        }
        String normalizedDetail = normalizeOptionalReasonDetail(reasonDetail);
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentStatus.FAILED) {
            if (this.statusReasonCode == reasonCode) {
                return;
            }
            throw new InvalidPaymentStateException(
                    "Payment has already FAILED with a different reasonCode: " + this.statusReasonCode
            );
        }
        if (!this.status.isPostCommitmentActive()) {
            throw new InvalidPaymentStateException(
                    "Cannot fail before settlement when payment is in state: " + this.status
                            + " (must be EXECUTING or PENDING_EXTERNAL)."
            );
        }

        this.status = PaymentStatus.FAILED;
        this.statusReasonCode = reasonCode;
        this.statusReasonDetail = normalizedDetail;
        this.updatedAt = timestamp;
    }

    public PaymentView toView() {
        return new PaymentView(
                paymentId,
                instruction,
                status,
                reservationToken,
                routeReference,
                externalDispatchReference,
                ledgerTransactionId,
                statusReasonCode,
                statusReasonDetail,
                createdAt,
                updatedAt
        );
    }

    public PaymentId getId() {
        return paymentId;
    }

    public PaymentInstruction getInstruction() {
        return instruction;
    }

    public InstructionFingerprint getInstructionFingerprint() {
        return instructionFingerprint;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Optional<ReservationToken> getReservationToken() {
        return Optional.ofNullable(reservationToken);
    }

    public Optional<String> getRouteReference() {
        return Optional.ofNullable(routeReference);
    }

    public Optional<String> getExternalDispatchReference() {
        return Optional.ofNullable(externalDispatchReference);
    }

    public Optional<UUID> getLedgerTransactionId() {
        return Optional.ofNullable(ledgerTransactionId);
    }

    public Optional<PaymentReasonCode> getStatusReasonCode() {
        return Optional.ofNullable(statusReasonCode);
    }

    public Optional<String> getStatusReasonDetail() {
        return Optional.ofNullable(statusReasonDetail);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void requireValidTransitionTimestamp(Instant timestamp) {
        if (timestamp == null) {
            throw new PaymentValidationException("Transition timestamp cannot be null.");
        }
        if (timestamp.isBefore(this.updatedAt)) {
            throw new PaymentValidationException(
                    "Transition timestamp (" + timestamp + ") cannot be before current updatedAt ("
                            + this.updatedAt + ")."
            );
        }
    }

    private static String validateRequiredReference(String raw, String fieldName, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw new PaymentValidationException(fieldName + " cannot be null or blank.");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new PaymentValidationException(
                    fieldName + " length (" + trimmed.length() + ") exceeds maximum of " + maxLength + " characters."
            );
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new PaymentValidationException(fieldName + " cannot contain ISO control characters.");
        }
        return trimmed;
    }

    private static String normalizeOptionalReasonDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String trimmed = detail.trim();
        if (trimmed.length() > PaymentLimits.MAX_REASON_DETAIL_LENGTH) {
            throw new PaymentValidationException(
                    "reasonDetail length (" + trimmed.length() + ") exceeds maximum of "
                            + PaymentLimits.MAX_REASON_DETAIL_LENGTH + " characters."
            );
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new PaymentValidationException("reasonDetail cannot contain ISO control characters.");
        }
        return trimmed;
    }

    private static void validateRestoredOptionalText(String value, String fieldName, int maxLength) {
        if (value == null) {
            return;
        }
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new PersistedPaymentStateException(
                    "Restored payment " + fieldName + " cannot be blank or have leading/trailing whitespace."
            );
        }
        if (value.length() > maxLength) {
            throw new PersistedPaymentStateException(
                    "Restored payment " + fieldName + " exceeds maximum length of " + maxLength + "."
            );
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new PersistedPaymentStateException(
                    "Restored payment " + fieldName + " contains ISO control characters."
            );
        }
    }

    private static void validateRestoredStateInvariants(
            PaymentStatus status,
            ReservationToken reservationToken,
            String routeReference,
            String externalDispatchReference,
            UUID ledgerTransactionId,
            PaymentReasonCode statusReasonCode,
            String statusReasonDetail) {
        switch (status) {
            case VALIDATING -> {
                if (reservationToken != null
                        || routeReference != null
                        || externalDispatchReference != null
                        || ledgerTransactionId != null
                        || statusReasonCode != null
                        || statusReasonDetail != null) {
                    throw new PersistedPaymentStateException(
                            "Restored VALIDATING payment must have null reservationToken, routeReference, "
                                    + "externalDispatchReference, ledgerTransactionId, statusReasonCode, and statusReasonDetail."
                    );
                }
            }
            case COMPLIANCE_HOLD, ROUTING -> {
                if (reservationToken == null
                        || routeReference != null
                        || externalDispatchReference != null
                        || ledgerTransactionId != null
                        || statusReasonCode != null
                        || statusReasonDetail != null) {
                    throw new PersistedPaymentStateException(
                            "Restored " + status + " payment must have non-null reservationToken and null "
                                    + "routeReference, externalDispatchReference, ledgerTransactionId, statusReasonCode, and statusReasonDetail."
                    );
                }
            }
            case EXECUTING -> {
                if (reservationToken == null
                        || routeReference == null
                        || externalDispatchReference != null
                        || ledgerTransactionId != null
                        || statusReasonCode != null
                        || statusReasonDetail != null) {
                    throw new PersistedPaymentStateException(
                            "Restored EXECUTING payment must have non-null reservationToken and routeReference, "
                                    + "and null externalDispatchReference, ledgerTransactionId, statusReasonCode, and statusReasonDetail."
                    );
                }
            }
            case PENDING_EXTERNAL -> {
                if (reservationToken == null
                        || routeReference == null
                        || externalDispatchReference == null
                        || ledgerTransactionId != null
                        || statusReasonCode != null
                        || statusReasonDetail != null) {
                    throw new PersistedPaymentStateException(
                            "Restored PENDING_EXTERNAL payment must have non-null reservationToken, routeReference, "
                                    + "and externalDispatchReference, and null ledgerTransactionId, statusReasonCode, and statusReasonDetail."
                    );
                }
            }
            case SETTLED -> {
                if (reservationToken == null
                        || routeReference == null
                        || externalDispatchReference == null
                        || ledgerTransactionId == null
                        || statusReasonCode != null
                        || statusReasonDetail != null) {
                    throw new PersistedPaymentStateException(
                            "Restored SETTLED payment must have non-null reservationToken, routeReference, "
                                    + "externalDispatchReference, and ledgerTransactionId, and null statusReasonCode and statusReasonDetail."
                    );
                }
            }
            case REJECTED -> {
                if (routeReference != null
                        || externalDispatchReference != null
                        || ledgerTransactionId != null
                        || statusReasonCode == null) {
                    throw new PersistedPaymentStateException(
                            "Restored REJECTED payment must have null routeReference, externalDispatchReference, "
                                    + "and ledgerTransactionId, and non-null statusReasonCode."
                    );
                }
                boolean validPreReservationRejection =
                        reservationToken == null && statusReasonCode.isReservationFailureReason();
                boolean validPostReservationRejection =
                        reservationToken != null && statusReasonCode.isPreCommitmentRejectionReason();
                if (!validPreReservationRejection && !validPostReservationRejection) {
                    throw new PersistedPaymentStateException(
                            "Restored REJECTED payment has inconsistent reservationToken (" + reservationToken
                                    + ") and statusReasonCode (" + statusReasonCode + ")."
                    );
                }
            }
            case CANCELLED -> {
                if (routeReference != null
                        || externalDispatchReference != null
                        || ledgerTransactionId != null
                        || statusReasonCode != PaymentReasonCode.CUSTOMER_CANCELLED) {
                    throw new PersistedPaymentStateException(
                            "Restored CANCELLED payment must have null routeReference, externalDispatchReference, "
                                    + "and ledgerTransactionId, and statusReasonCode == CUSTOMER_CANCELLED."
                    );
                }
            }
            case FAILED -> {
                if (reservationToken == null
                        || routeReference == null
                        || ledgerTransactionId != null
                        || statusReasonCode == null
                        || !statusReasonCode.isPostCommitmentFailureReason()) {
                    throw new PersistedPaymentStateException(
                            "Restored FAILED payment must have non-null reservationToken and routeReference, "
                                    + "null ledgerTransactionId, and a post-commitment failure statusReasonCode."
                    );
                }
            }
        }
    }
}
