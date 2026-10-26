package com.crosspaynet.settlement.contracts;

import com.crosspaynet.common.money.Money;
import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain event published when a cross-border payment is definitively settled,
 * operational funds consumed, and balancing double-entry ledger lines posted.
 */
@Externalized("crosspaynet.settlement.payment-settled.v1::#{#this.paymentId()}")
public record PaymentSettledEvent(
        UUID eventId,
        UUID paymentId,
        UUID ledgerTransactionId,
        String settlementReference,
        Money amount,
        Instant occurredAt,
        String correlationId,
        String causationId
) {

    public PaymentSettledEvent {
        Objects.requireNonNull(eventId, "eventId cannot be null.");
        Objects.requireNonNull(paymentId, "paymentId cannot be null.");
        Objects.requireNonNull(ledgerTransactionId, "ledgerTransactionId cannot be null.");
        Objects.requireNonNull(settlementReference, "settlementReference cannot be null.");
        Objects.requireNonNull(amount, "amount cannot be null.");
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
