package com.crosspaynet.payment.contracts;

import com.crosspaynet.banking.contracts.ReservationToken;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable read-only projection of a {@code Payment} exposed across module boundaries.
 */
public record PaymentView(
        PaymentId paymentId,
        PaymentInstruction instruction,
        PaymentStatus status,
        ReservationToken reservationToken,
        String routeReference,
        String externalDispatchReference,
        UUID ledgerTransactionId,
        PaymentReasonCode statusReasonCode,
        String statusReasonDetail,
        Instant createdAt,
        Instant updatedAt
) {

    public PaymentView {
        if (paymentId == null) {
            throw new PaymentValidationException("paymentId cannot be null.");
        }
        if (instruction == null) {
            throw new PaymentValidationException("instruction cannot be null.");
        }
        if (status == null) {
            throw new PaymentValidationException("status cannot be null.");
        }
        if (createdAt == null) {
            throw new PaymentValidationException("createdAt cannot be null.");
        }
        if (updatedAt == null) {
            throw new PaymentValidationException("updatedAt cannot be null.");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new PaymentValidationException("updatedAt cannot be before createdAt.");
        }
    }
}
