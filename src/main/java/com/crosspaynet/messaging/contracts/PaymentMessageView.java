package com.crosspaynet.messaging.contracts;

import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentId;

import java.time.Instant;

/**
 * Immutable read-only projection of a {@code PaymentMessage} transmission attempt exposed across module boundaries.
 */
public record PaymentMessageView(
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
        String payloadDigest,
        PaymentMessageStatus status,
        String acknowledgmentReference,
        String peerRejectionCode,
        String peerRejectionReason,
        String timeoutReason,
        Instant createdAt,
        Instant updatedAt
) {

    public PaymentMessageView {
        if (messageId == null) {
            throw new MessagingValidationException("messageId cannot be null.");
        }
        if (paymentId == null) {
            throw new MessagingValidationException("paymentId cannot be null.");
        }
        if (attemptNumber < 1 || attemptNumber > 100) {
            throw new MessagingValidationException("attemptNumber must be between 1 and 100.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MessagingValidationException("idempotencyKey cannot be null or blank.");
        }
        if (messageType == null) {
            throw new MessagingValidationException("messageType cannot be null.");
        }
        if (senderBic == null) {
            throw new MessagingValidationException("senderBic cannot be null.");
        }
        if (receiverBic == null) {
            throw new MessagingValidationException("receiverBic cannot be null.");
        }
        if (routeReference == null || routeReference.isBlank()) {
            throw new MessagingValidationException("routeReference cannot be null or blank.");
        }
        if (amount == null) {
            throw new MessagingValidationException("amount cannot be null.");
        }
        if (beneficiaryName == null || beneficiaryName.isBlank()) {
            throw new MessagingValidationException("beneficiaryName cannot be null or blank.");
        }
        if (beneficiaryAccountNumber == null || beneficiaryAccountNumber.isBlank()) {
            throw new MessagingValidationException("beneficiaryAccountNumber cannot be null or blank.");
        }
        if (payload == null || payload.isBlank()) {
            throw new MessagingValidationException("payload cannot be null or blank.");
        }
        if (payloadDigest == null || payloadDigest.isBlank()) {
            throw new MessagingValidationException("payloadDigest cannot be null or blank.");
        }
        if (status == null) {
            throw new MessagingValidationException("status cannot be null.");
        }
        if (createdAt == null) {
            throw new MessagingValidationException("createdAt cannot be null.");
        }
        if (updatedAt == null) {
            throw new MessagingValidationException("updatedAt cannot be null.");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new MessagingValidationException("updatedAt cannot be before createdAt.");
        }
    }
}
