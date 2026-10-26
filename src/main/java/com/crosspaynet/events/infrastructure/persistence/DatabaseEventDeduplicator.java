package com.crosspaynet.events.infrastructure.persistence;

import com.crosspaynet.events.contracts.EventDeduplicator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Component
public class DatabaseEventDeduplicator implements EventDeduplicator {

    private final InboxRepository inboxRepository;

    public DatabaseEventDeduplicator(InboxRepository inboxRepository) {
        this.inboxRepository = Objects.requireNonNull(inboxRepository, "inboxRepository cannot be null.");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimProcessing(UUID messageId, String consumerGroup, String eventType) {
        Objects.requireNonNull(messageId, "messageId cannot be null.");
        Objects.requireNonNull(consumerGroup, "consumerGroup cannot be null.");
        Objects.requireNonNull(eventType, "eventType cannot be null.");

        Instant now = Instant.now();
        int inserted = inboxRepository.insertClaimOnConflictDoNothing(
                messageId,
                consumerGroup,
                eventType,
                now,
                now
        );
        return inserted > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAlreadyProcessed(UUID messageId, String consumerGroup) {
        Objects.requireNonNull(messageId, "messageId cannot be null.");
        Objects.requireNonNull(consumerGroup, "consumerGroup cannot be null.");
        return inboxRepository.existsByMessageIdAndConsumerGroup(messageId, consumerGroup);
    }
}
