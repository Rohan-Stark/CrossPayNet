package com.crosspaynet.messaging.contracts;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly-typed UUID identifier for a {@code PaymentMessage} transmission attempt.
 */
public final class MessageId {

    private final UUID value;

    public MessageId(UUID value) {
        if (value == null) {
            throw new MessagingValidationException("MessageId value cannot be null.");
        }
        this.value = value;
    }

    public static MessageId generate() {
        return new MessageId(UUID.randomUUID());
    }

    public static MessageId of(UUID value) {
        return new MessageId(value);
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageId messageId = (MessageId) o;
        return Objects.equals(value, messageId.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
