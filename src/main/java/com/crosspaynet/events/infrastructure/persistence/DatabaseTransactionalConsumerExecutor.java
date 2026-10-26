package com.crosspaynet.events.infrastructure.persistence;

import com.crosspaynet.events.contracts.ConsumerExecutionResult;
import com.crosspaynet.events.contracts.EventDeduplicator;
import com.crosspaynet.events.contracts.TransactionalConsumerExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class DatabaseTransactionalConsumerExecutor implements TransactionalConsumerExecutor {

    private final EventDeduplicator eventDeduplicator;

    public DatabaseTransactionalConsumerExecutor(EventDeduplicator eventDeduplicator) {
        this.eventDeduplicator = Objects.requireNonNull(eventDeduplicator, "eventDeduplicator cannot be null.");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Throwable.class)
    public ConsumerExecutionResult executeIdempotent(
            UUID messageId,
            String consumerGroup,
            String eventType,
            ConsumerAction action
    ) throws Exception {
        Objects.requireNonNull(messageId, "messageId cannot be null.");
        Objects.requireNonNull(consumerGroup, "consumerGroup cannot be null.");
        Objects.requireNonNull(eventType, "eventType cannot be null.");
        Objects.requireNonNull(action, "action cannot be null.");

        boolean claimed = eventDeduplicator.claimProcessing(messageId, consumerGroup, eventType);
        if (!claimed) {
            return ConsumerExecutionResult.DUPLICATE_SKIPPED;
        }

        action.execute();
        return ConsumerExecutionResult.PROCESSED;
    }
}
