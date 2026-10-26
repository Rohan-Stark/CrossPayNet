package com.crosspaynet.events.contracts;

/**
 * Result of executing an idempotent consumer action via {@link TransactionalConsumerExecutor}.
 */
public enum ConsumerExecutionResult {
    PROCESSED,
    DUPLICATE_SKIPPED
}
