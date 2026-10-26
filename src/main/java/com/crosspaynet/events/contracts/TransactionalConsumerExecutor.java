package com.crosspaynet.events.contracts;

import java.util.UUID;

/**
 * Service orchestrating idempotent event consumption within a local ACID transaction.
 *
 * <p>Ensures that the deduplication inbox claim and the business effect commit
 * together atomically. If the business effect fails, the inbox claim is rolled back
 * so Kafka redeliveries can reattempt processing.
 */
public interface TransactionalConsumerExecutor {

    @FunctionalInterface
    interface ConsumerAction {
        void execute() throws Exception;
    }

    ConsumerExecutionResult executeIdempotent(
            UUID messageId,
            String consumerGroup,
            String eventType,
            ConsumerAction action
    ) throws Exception;
}
