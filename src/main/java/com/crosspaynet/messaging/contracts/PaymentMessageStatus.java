package com.crosspaynet.messaging.contracts;

/**
 * Six-state technical lifecycle of a {@code PaymentMessage} transmission attempt:
 * {@code GENERATED -> SIGNED -> SENT -> ACKNOWLEDGED | NACKNOWLEDGED | TIMEOUT}.
 */
public enum PaymentMessageStatus {
    GENERATED,
    SIGNED,
    SENT,
    ACKNOWLEDGED,
    NACKNOWLEDGED,
    TIMEOUT;

    public boolean isTerminal() {
        return this == ACKNOWLEDGED || this == NACKNOWLEDGED || this == TIMEOUT;
    }
}
