package com.crosspaynet.payment.contracts;

import com.crosspaynet.common.money.Money;
import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain event published when a payment instruction is received, operational funds
 * are reserved, and the payment is accepted into the evaluation lifecycle.
 */
@Externalized("crosspaynet.payment.instruction-received.v1::#{#this.paymentId()}")
public record PaymentInstructionReceived(
        UUID eventId,
        UUID paymentId,
        UUID sourceAccountId,
        String paymentReference,
        Money amount,
        BeneficiaryDetails beneficiary,
        Instant occurredAt,
        String correlationId,
        String causationId
) {

    public PaymentInstructionReceived {
        Objects.requireNonNull(eventId, "eventId cannot be null.");
        Objects.requireNonNull(paymentId, "paymentId cannot be null.");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId cannot be null.");
        Objects.requireNonNull(paymentReference, "paymentReference cannot be null.");
        Objects.requireNonNull(amount, "amount cannot be null.");
        Objects.requireNonNull(beneficiary, "beneficiary cannot be null.");
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
