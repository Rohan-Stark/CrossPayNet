package com.crosspaynet.events.infrastructure.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class InboxEntityId implements Serializable {

    private UUID messageId;
    private String consumerGroup;

    public InboxEntityId() {}

    public InboxEntityId(UUID messageId, String consumerGroup) {
        this.messageId = messageId;
        this.consumerGroup = consumerGroup;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public void setMessageId(UUID messageId) {
        this.messageId = messageId;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InboxEntityId that)) return false;
        return Objects.equals(messageId, that.messageId) && Objects.equals(consumerGroup, that.consumerGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, consumerGroup);
    }
}
