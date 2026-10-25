package com.crosspaynet.messaging.domain;

import com.crosspaynet.common.money.Money;
import com.crosspaynet.messaging.contracts.InvalidMessageStateException;
import com.crosspaynet.messaging.contracts.MessageId;
import com.crosspaynet.messaging.contracts.MessagingValidationException;
import com.crosspaynet.messaging.contracts.PaymentMessageStatus;
import com.crosspaynet.messaging.contracts.PaymentMessageType;
import com.crosspaynet.messaging.contracts.PaymentMessageView;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure-Java aggregate root of the Messaging module representing a single physical
 * transmission attempt ({@code PaymentMessage}) and enforcing the six-state lifecycle:
 * {@code GENERATED -> SIGNED -> SENT -> ACKNOWLEDGED | NACKNOWLEDGED | TIMEOUT}.
 */
public final class PaymentMessage {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9:_.-]{1,200}$");
    private static final Pattern REJECTION_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,35}$");

    private final MessageId messageId;
    private final PaymentId paymentId;
    private final int attemptNumber;
    private final String idempotencyKey;
    private final PaymentMessageType messageType;
    private final BicCode senderBic;
    private final BicCode receiverBic;
    private final String routeReference;
    private final Money amount;
    private final String beneficiaryName;
    private final String beneficiaryAccountNumber;
    private final String payload;
    private final PayloadDigest payloadDigest;
    private PaymentMessageStatus status;
    private String acknowledgmentReference;
    private String peerRejectionCode;
    private String peerRejectionReason;
    private String timeoutReason;
    private final Instant createdAt;
    private Instant updatedAt;

    private PaymentMessage(
            MessageId messageId,
            PaymentId paymentId,
            int attemptNumber,
            String idempotencyKey,
            PaymentMessageType messageType,
            BicCode senderBic,
            BicCode receiverBic,
            String routeReference,
            Money amount,
            String beneficiaryName,
            String beneficiaryAccountNumber,
            String payload,
            PayloadDigest payloadDigest,
            PaymentMessageStatus status,
            String acknowledgmentReference,
            String peerRejectionCode,
            String peerRejectionReason,
            String timeoutReason,
            Instant createdAt,
            Instant updatedAt) {
        this.messageId = messageId;
        this.paymentId = paymentId;
        this.attemptNumber = attemptNumber;
        this.idempotencyKey = idempotencyKey;
        this.messageType = messageType;
        this.senderBic = senderBic;
        this.receiverBic = receiverBic;
        this.routeReference = routeReference;
        this.amount = amount;
        this.beneficiaryName = beneficiaryName;
        this.beneficiaryAccountNumber = beneficiaryAccountNumber;
        this.payload = payload;
        this.payloadDigest = payloadDigest;
        this.status = status;
        this.acknowledgmentReference = acknowledgmentReference;
        this.peerRejectionCode = peerRejectionCode;
        this.peerRejectionReason = peerRejectionReason;
        this.timeoutReason = timeoutReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Deterministically derives the per-attempt idempotency key:
     * {@code MSG:{paymentId}:ATTEMPT:{attemptNumber}}.
     */
    public static String idempotencyKeyFor(PaymentId paymentId, int attemptNumber) {
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
        return "MSG:" + paymentId.getValue() + ":ATTEMPT:" + attemptNumber;
    }

    /**
     * Generates a new {@code PaymentMessage} in {@link PaymentMessageStatus#GENERATED} state,
     * computing its canonical {@code pacs.008.001.08} payload and {@link PayloadDigest}.
     */
    public static PaymentMessage generate(
            MessageId messageId,
            PaymentId paymentId,
            int attemptNumber,
            BicCode senderBic,
            BicCode receiverBic,
            String routeReference,
            Money amount,
            String beneficiaryName,
            String beneficiaryAccountNumber,
            Instant createdAt) {
        if (messageId == null) {
            throw new MessagingValidationException("messageId cannot be null.");
        }
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
        if (senderBic == null) {
            throw new MessagingValidationException("senderBic cannot be null.");
        }
        if (receiverBic == null) {
            throw new MessagingValidationException("receiverBic cannot be null.");
        }
        String normalizedRouteRef = validateRequiredText(
                routeReference,
                "routeReference",
                MessagingLimits.MAX_ROUTE_REFERENCE_LENGTH
        );
        validateMoney(amount);
        String normalizedBeneficiaryName = validateRequiredText(
                beneficiaryName,
                "beneficiaryName",
                MessagingLimits.MAX_BENEFICIARY_NAME_LENGTH
        );
        String normalizedBeneficiaryAccount = validateRequiredText(
                beneficiaryAccountNumber,
                "beneficiaryAccountNumber",
                MessagingLimits.MAX_BENEFICIARY_ACCOUNT_LENGTH
        );
        if (createdAt == null) {
            throw new MessagingValidationException("createdAt cannot be null.");
        }

        String idempotencyKey = idempotencyKeyFor(paymentId, attemptNumber);
        String canonicalPayload = Pacs008PayloadBuilder.buildCanonicalPayload(
                messageId,
                paymentId,
                attemptNumber,
                PaymentMessageType.PACS_008,
                senderBic,
                receiverBic,
                normalizedRouteRef,
                amount,
                normalizedBeneficiaryName,
                normalizedBeneficiaryAccount
        );
        PayloadDigest digest = PayloadDigest.compute(canonicalPayload);

        return new PaymentMessage(
                messageId,
                paymentId,
                attemptNumber,
                idempotencyKey,
                PaymentMessageType.PACS_008,
                senderBic,
                receiverBic,
                normalizedRouteRef,
                amount,
                normalizedBeneficiaryName,
                normalizedBeneficiaryAccount,
                canonicalPayload,
                digest,
                PaymentMessageStatus.GENERATED,
                null,
                null,
                null,
                null,
                createdAt,
                createdAt
        );
    }

    /**
     * Restores a persisted {@code PaymentMessage} aggregate and strictly verifies all field
     * invariants, canonical payload serialization, and {@link PayloadDigest} integrity.
     *
     * @throws PersistedMessageStateException if any persisted attribute, state combination,
     *                                        or payload digest is corrupted or tampered with
     */
    public static PaymentMessage restore(
            MessageId messageId,
            PaymentId paymentId,
            int attemptNumber,
            String idempotencyKey,
            PaymentMessageType messageType,
            BicCode senderBic,
            BicCode receiverBic,
            String routeReference,
            Money amount,
            String beneficiaryName,
            String beneficiaryAccountNumber,
            String payload,
            PayloadDigest payloadDigest,
            PaymentMessageStatus status,
            String acknowledgmentReference,
            String peerRejectionCode,
            String peerRejectionReason,
            String timeoutReason,
            Instant createdAt,
            Instant updatedAt) {
        if (messageId == null) {
            throw new PersistedMessageStateException("Restored PaymentMessage cannot have null messageId.");
        }
        if (paymentId == null) {
            throw new PersistedMessageStateException("Restored PaymentMessage cannot have null paymentId.");
        }
        if (attemptNumber < MessagingLimits.MIN_ATTEMPT_NUMBER
                || attemptNumber > MessagingLimits.MAX_ATTEMPT_NUMBER) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage attemptNumber out of bounds: " + attemptNumber
            );
        }
        if (idempotencyKey == null
                || !idempotencyKey.equals(idempotencyKey.trim())
                || idempotencyKey.length() > MessagingLimits.MAX_IDEMPOTENCY_KEY_LENGTH
                || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()
                || !idempotencyKey.equals(idempotencyKeyFor(paymentId, attemptNumber))) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage has invalid or mismatched idempotencyKey: " + idempotencyKey
            );
        }
        if (messageType != PaymentMessageType.PACS_008) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage has unsupported messageType: " + messageType
            );
        }
        if (senderBic == null || receiverBic == null) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage cannot have null senderBic or receiverBic."
            );
        }
        validateRestoredRequiredText(
                routeReference,
                "routeReference",
                MessagingLimits.MAX_ROUTE_REFERENCE_LENGTH
        );
        validateRestoredMoney(amount);
        validateRestoredRequiredText(
                beneficiaryName,
                "beneficiaryName",
                MessagingLimits.MAX_BENEFICIARY_NAME_LENGTH
        );
        validateRestoredRequiredText(
                beneficiaryAccountNumber,
                "beneficiaryAccountNumber",
                MessagingLimits.MAX_BENEFICIARY_ACCOUNT_LENGTH
        );
        if (payload == null || payload.isBlank() || !payload.equals(payload.trim())) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage payload cannot be null, blank, or untrimmed."
            );
        }
        if (payloadDigest == null) {
            throw new PersistedMessageStateException("Restored PaymentMessage cannot have null payloadDigest.");
        }
        if (status == null) {
            throw new PersistedMessageStateException("Restored PaymentMessage cannot have null status.");
        }
        if (createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage has invalid createdAt/updatedAt timestamps."
            );
        }

        // Verify canonical payload matches restored attributes and verify PayloadDigest integrity
        String expectedCanonicalPayload = Pacs008PayloadBuilder.buildCanonicalPayload(
                messageId,
                paymentId,
                attemptNumber,
                messageType,
                senderBic,
                receiverBic,
                routeReference,
                amount,
                beneficiaryName,
                beneficiaryAccountNumber
        );
        if (!expectedCanonicalPayload.equals(payload)) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage payload does not match canonical pacs.008 serialization "
                            + "(tampering or attribute mismatch detected)."
            );
        }
        PayloadDigest computedDigest = PayloadDigest.compute(payload);
        if (!computedDigest.equals(payloadDigest)) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage payloadDigest (" + payloadDigest
                            + ") does not match computed digest (" + computedDigest + ")."
            );
        }

        validateRestoredOptionalText(
                acknowledgmentReference,
                "acknowledgmentReference",
                MessagingLimits.MAX_ACKNOWLEDGMENT_REFERENCE_LENGTH
        );
        validateRestoredOptionalRejectionCode(peerRejectionCode);
        validateRestoredOptionalText(
                peerRejectionReason,
                "peerRejectionReason",
                MessagingLimits.MAX_PEER_REJECTION_REASON_LENGTH
        );
        validateRestoredOptionalText(
                timeoutReason,
                "timeoutReason",
                MessagingLimits.MAX_TIMEOUT_REASON_LENGTH
        );

        validateRestoredStateInvariants(
                status,
                acknowledgmentReference,
                peerRejectionCode,
                peerRejectionReason,
                timeoutReason
        );

        return new PaymentMessage(
                messageId,
                paymentId,
                attemptNumber,
                idempotencyKey,
                messageType,
                senderBic,
                receiverBic,
                routeReference,
                amount,
                beneficiaryName,
                beneficiaryAccountNumber,
                payload,
                payloadDigest,
                status,
                acknowledgmentReference,
                peerRejectionCode,
                peerRejectionReason,
                timeoutReason,
                createdAt,
                updatedAt
        );
    }

    /**
     * Transitions {@code GENERATED -> SIGNED} after verifying that the stored {@link PayloadDigest}
     * matches the canonical payload. Idempotent if already {@link PaymentMessageStatus#SIGNED}.
     */
    public void sign(Instant timestamp) {
        requireValidTransitionTimestamp(timestamp);

        PayloadDigest recomputed = PayloadDigest.compute(this.payload);
        if (!recomputed.equals(this.payloadDigest)) {
            throw new PersistedMessageStateException(
                    "Cannot sign PaymentMessage: payloadDigest mismatch."
            );
        }

        if (this.status == PaymentMessageStatus.SIGNED) {
            return;
        }
        if (this.status != PaymentMessageStatus.GENERATED) {
            throw new InvalidMessageStateException(
                    "Cannot sign PaymentMessage in state: " + this.status + " (must be GENERATED)."
            );
        }

        this.status = PaymentMessageStatus.SIGNED;
        this.updatedAt = timestamp;
    }

    /**
     * Transitions {@code SIGNED -> SENT}. Idempotent if already {@link PaymentMessageStatus#SENT}.
     */
    public void markSent(Instant timestamp) {
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentMessageStatus.SENT) {
            return;
        }
        if (this.status != PaymentMessageStatus.SIGNED) {
            throw new InvalidMessageStateException(
                    "Cannot mark PaymentMessage as SENT when in state: " + this.status + " (must be SIGNED)."
            );
        }

        this.status = PaymentMessageStatus.SENT;
        this.updatedAt = timestamp;
    }

    /**
     * Records a positive peer acknowledgment ({@code SENT -> ACKNOWLEDGED}).
     * Idempotent if already {@link PaymentMessageStatus#ACKNOWLEDGED} with the same reference.
     */
    public void acknowledge(String acknowledgmentReference, Instant timestamp) {
        String normalizedAckRef = validateRequiredText(
                acknowledgmentReference,
                "acknowledgmentReference",
                MessagingLimits.MAX_ACKNOWLEDGMENT_REFERENCE_LENGTH
        );
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentMessageStatus.ACKNOWLEDGED) {
            if (Objects.equals(this.acknowledgmentReference, normalizedAckRef)) {
                return;
            }
            throw new InvalidMessageStateException(
                    "PaymentMessage is already ACKNOWLEDGED with a different acknowledgmentReference."
            );
        }
        if (this.status != PaymentMessageStatus.SENT) {
            throw new InvalidMessageStateException(
                    "Cannot acknowledge PaymentMessage in state: " + this.status + " (must be SENT)."
            );
        }

        this.acknowledgmentReference = normalizedAckRef;
        this.status = PaymentMessageStatus.ACKNOWLEDGED;
        this.updatedAt = timestamp;
    }

    /**
     * Records a negative peer acknowledgment ({@code SENT -> NACKNOWLEDGED}).
     * Idempotent if already {@link PaymentMessageStatus#NACKNOWLEDGED} with the same rejection code and reason.
     */
    public void nacknowledge(String peerRejectionCode, String peerRejectionReason, Instant timestamp) {
        String normalizedCode = validateRejectionCode(peerRejectionCode);
        String normalizedReason = normalizeOptionalText(
                peerRejectionReason,
                "peerRejectionReason",
                MessagingLimits.MAX_PEER_REJECTION_REASON_LENGTH
        );
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentMessageStatus.NACKNOWLEDGED) {
            if (Objects.equals(this.peerRejectionCode, normalizedCode)
                    && Objects.equals(this.peerRejectionReason, normalizedReason)) {
                return;
            }
            throw new InvalidMessageStateException(
                    "PaymentMessage is already NACKNOWLEDGED with different peer rejection parameters."
            );
        }
        if (this.status != PaymentMessageStatus.SENT) {
            throw new InvalidMessageStateException(
                    "Cannot nacknowledge PaymentMessage in state: " + this.status + " (must be SENT)."
            );
        }

        this.peerRejectionCode = normalizedCode;
        this.peerRejectionReason = normalizedReason;
        this.status = PaymentMessageStatus.NACKNOWLEDGED;
        this.updatedAt = timestamp;
    }

    /**
     * Records a transmission timeout ({@code SENT -> TIMEOUT}).
     * Idempotent if already {@link PaymentMessageStatus#TIMEOUT} with the same timeoutReason.
     */
    public void markTimeout(String timeoutReason, Instant timestamp) {
        String normalizedReason = normalizeOptionalText(
                timeoutReason,
                "timeoutReason",
                MessagingLimits.MAX_TIMEOUT_REASON_LENGTH
        );
        requireValidTransitionTimestamp(timestamp);

        if (this.status == PaymentMessageStatus.TIMEOUT) {
            if (Objects.equals(this.timeoutReason, normalizedReason)) {
                return;
            }
            throw new InvalidMessageStateException(
                    "PaymentMessage is already TIMEOUT with a different timeoutReason."
            );
        }
        if (this.status != PaymentMessageStatus.SENT) {
            throw new InvalidMessageStateException(
                    "Cannot mark PaymentMessage as TIMEOUT when in state: " + this.status + " (must be SENT)."
            );
        }

        this.timeoutReason = normalizedReason;
        this.status = PaymentMessageStatus.TIMEOUT;
        this.updatedAt = timestamp;
    }

    public PaymentMessageView toView() {
        return new PaymentMessageView(
                messageId,
                paymentId,
                attemptNumber,
                idempotencyKey,
                messageType,
                senderBic,
                receiverBic,
                routeReference,
                amount,
                beneficiaryName,
                beneficiaryAccountNumber,
                payload,
                payloadDigest.getValue(),
                status,
                acknowledgmentReference,
                peerRejectionCode,
                peerRejectionReason,
                timeoutReason,
                createdAt,
                updatedAt
        );
    }

    public MessageId getId() {
        return messageId;
    }

    public PaymentId getPaymentId() {
        return paymentId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PaymentMessageType getMessageType() {
        return messageType;
    }

    public BicCode getSenderBic() {
        return senderBic;
    }

    public BicCode getReceiverBic() {
        return receiverBic;
    }

    public String getRouteReference() {
        return routeReference;
    }

    public Money getAmount() {
        return amount;
    }

    public String getBeneficiaryName() {
        return beneficiaryName;
    }

    public String getBeneficiaryAccountNumber() {
        return beneficiaryAccountNumber;
    }

    public String getPayload() {
        return payload;
    }

    public PayloadDigest getPayloadDigest() {
        return payloadDigest;
    }

    public PaymentMessageStatus getStatus() {
        return status;
    }

    public Optional<String> getAcknowledgmentReference() {
        return Optional.ofNullable(acknowledgmentReference);
    }

    public Optional<String> getPeerRejectionCode() {
        return Optional.ofNullable(peerRejectionCode);
    }

    public Optional<String> getPeerRejectionReason() {
        return Optional.ofNullable(peerRejectionReason);
    }

    public Optional<String> getTimeoutReason() {
        return Optional.ofNullable(timeoutReason);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void requireValidTransitionTimestamp(Instant timestamp) {
        if (timestamp == null) {
            throw new MessagingValidationException("Transition timestamp cannot be null.");
        }
        if (timestamp.isBefore(this.updatedAt)) {
            throw new MessagingValidationException(
                    "Transition timestamp (" + timestamp + ") cannot be before current updatedAt ("
                            + this.updatedAt + ")."
            );
        }
    }

    private static void validateMoney(Money amount) {
        if (amount == null) {
            throw new MessagingValidationException("amount cannot be null.");
        }
        if (!MessagingLimits.isSupportedCurrencyCode(amount.getCurrency().getCode())) {
            throw new MessagingValidationException(
                    "Unsupported currency: " + amount.getCurrency().getCode()
            );
        }
        if (!amount.isPositive()) {
            throw new MessagingValidationException("amount must be strictly positive.");
        }
        if (amount.getAmount().stripTrailingZeros().scale() > MessagingLimits.MAX_SCALE) {
            throw new MessagingValidationException(
                    "amount scale cannot exceed " + MessagingLimits.MAX_SCALE + " decimal places."
            );
        }
        if (amount.getAmount().compareTo(MessagingLimits.MAX_AMOUNT) > 0) {
            throw new MessagingValidationException(
                    "amount exceeds maximum allowed value of " + MessagingLimits.MAX_AMOUNT + "."
            );
        }
    }

    private static void validateRestoredMoney(Money amount) {
        if (amount == null
                || !MessagingLimits.isSupportedCurrencyCode(amount.getCurrency().getCode())
                || !amount.isPositive()
                || amount.getAmount().stripTrailingZeros().scale() > MessagingLimits.MAX_SCALE
                || amount.getAmount().compareTo(MessagingLimits.MAX_AMOUNT) > 0) {
            throw new PersistedMessageStateException("Restored PaymentMessage has invalid amount or currency.");
        }
    }

    private static String validateRequiredText(String raw, String fieldName, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw new MessagingValidationException(fieldName + " cannot be null or blank.");
        }
        if (raw.codePoints().anyMatch(Character::isISOControl)) {
            throw new MessagingValidationException(fieldName + " cannot contain ISO control characters.");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new MessagingValidationException(
                    fieldName + " length (" + trimmed.length() + ") exceeds maximum of " + maxLength + " characters."
            );
        }
        return trimmed;
    }

    private static String validateRejectionCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new MessagingValidationException("peerRejectionCode cannot be null or blank.");
        }
        if (raw.codePoints().anyMatch(Character::isISOControl)) {
            throw new MessagingValidationException(
                    "peerRejectionCode must be 1..35 characters matching [A-Za-z0-9_.-]."
            );
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MessagingLimits.MAX_PEER_REJECTION_CODE_LENGTH
                || !REJECTION_CODE_PATTERN.matcher(trimmed).matches()) {
            throw new MessagingValidationException(
                    "peerRejectionCode must be 1..35 characters matching [A-Za-z0-9_.-]."
            );
        }
        return trimmed;
    }

    private static String normalizeOptionalText(String raw, String fieldName, int maxLength) {
        if (raw == null) {
            return null;
        }
        if (raw.codePoints().anyMatch(Character::isISOControl)) {
            throw new MessagingValidationException(fieldName + " cannot contain ISO control characters.");
        }
        if (raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new MessagingValidationException(
                    fieldName + " length (" + trimmed.length() + ") exceeds maximum of " + maxLength + " characters."
            );
        }
        return trimmed;
    }

    private static void validateRestoredRequiredText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage " + fieldName + " cannot be null, blank, or untrimmed."
            );
        }
        if (value.length() > maxLength) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage " + fieldName + " exceeds maximum length of " + maxLength + "."
            );
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage " + fieldName + " contains ISO control characters."
            );
        }
    }

    private static void validateRestoredOptionalText(String value, String fieldName, int maxLength) {
        if (value == null) {
            return;
        }
        validateRestoredRequiredText(value, fieldName, maxLength);
    }

    private static void validateRestoredOptionalRejectionCode(String value) {
        if (value == null) {
            return;
        }
        if (value.isBlank()
                || !value.equals(value.trim())
                || value.length() > MessagingLimits.MAX_PEER_REJECTION_CODE_LENGTH
                || !REJECTION_CODE_PATTERN.matcher(value).matches()) {
            throw new PersistedMessageStateException(
                    "Restored PaymentMessage peerRejectionCode is invalid: " + value
            );
        }
    }

    private static void validateRestoredStateInvariants(
            PaymentMessageStatus status,
            String acknowledgmentReference,
            String peerRejectionCode,
            String peerRejectionReason,
            String timeoutReason) {
        switch (status) {
            case GENERATED, SIGNED, SENT -> {
                if (acknowledgmentReference != null
                        || peerRejectionCode != null
                        || peerRejectionReason != null
                        || timeoutReason != null) {
                    throw new PersistedMessageStateException(
                            "Restored " + status + " PaymentMessage must have null outcome fields."
                    );
                }
            }
            case ACKNOWLEDGED -> {
                if (acknowledgmentReference == null
                        || peerRejectionCode != null
                        || peerRejectionReason != null
                        || timeoutReason != null) {
                    throw new PersistedMessageStateException(
                            "Restored ACKNOWLEDGED PaymentMessage must have non-null acknowledgmentReference "
                                    + "and null rejection/timeout fields."
                    );
                }
            }
            case NACKNOWLEDGED -> {
                if (acknowledgmentReference != null
                        || peerRejectionCode == null
                        || timeoutReason != null) {
                    throw new PersistedMessageStateException(
                            "Restored NACKNOWLEDGED PaymentMessage must have non-null peerRejectionCode "
                                    + "and null acknowledgmentReference/timeoutReason."
                    );
                }
            }
            case TIMEOUT -> {
                if (acknowledgmentReference != null
                        || peerRejectionCode != null
                        || peerRejectionReason != null) {
                    throw new PersistedMessageStateException(
                            "Restored TIMEOUT PaymentMessage must have null acknowledgmentReference "
                                    + "and rejection fields."
                    );
                }
            }
        }
    }
}
