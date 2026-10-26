package com.crosspaynet.events.contracts;

import java.util.UUID;

/**
 * Port for conflict-safe event deduplication using the Transactional Inbox pattern.
 */
public interface EventDeduplicator {

    /**
     * Attempts to atomically claim processing for {@code (messageId, consumerGroup)}.
     *
     * @param messageId unique event ID from the incoming envelope
     * @param consumerGroup logical consumer group identifier
     * @param eventType type of the event being processed
     * @return {@code true} if this claim was successful (first delivery), {@code false} if already claimed
     */
    boolean claimProcessing(UUID messageId, String consumerGroup, String eventType);

    /**
     * Checks if a message has already been claimed/processed by the specified consumer group.
     */
    boolean isAlreadyProcessed(UUID messageId, String consumerGroup);
}
