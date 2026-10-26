package com.crosspaynet.banking;

import com.crosspaynet.common.money.Money;
import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Business event indicating funds have been reserved operationally.
 */
@Externalized("crosspaynet.banking.funds-reserved.v1::#{#this.accountId()}")
public record FundsReserved(
        UUID eventId,
        UUID accountId,
        UUID reservationToken,
        String paymentReference,
        Money amount,
        Instant occurredAt,
        String correlationId,
        String causationId
) {

    public FundsReserved {
        Objects.requireNonNull(eventId, "eventId cannot be null.");
        Objects.requireNonNull(accountId, "accountId cannot be null.");
        Objects.requireNonNull(reservationToken, "reservationToken cannot be null.");
        Objects.requireNonNull(paymentReference, "paymentReference cannot be null.");
        Objects.requireNonNull(amount, "amount cannot be null.");
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public FundsReserved(UUID accountId, UUID reservationToken, String paymentReference, Money amount) {
        this(UUID.randomUUID(), accountId, reservationToken, paymentReference, amount, Instant.now(), paymentReference, null);
    }
}
