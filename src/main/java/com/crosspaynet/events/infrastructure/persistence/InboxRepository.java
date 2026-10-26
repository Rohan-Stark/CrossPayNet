package com.crosspaynet.events.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface InboxRepository extends JpaRepository<InboxEntity, InboxEntityId> {

    @Modifying
    @Query(value = """
            INSERT INTO system.event_inbox (message_id, consumer_group, event_type, received_at, processed_at)
            VALUES (:messageId, :consumerGroup, :eventType, :receivedAt, :processedAt)
            ON CONFLICT (message_id, consumer_group) DO NOTHING
            """, nativeQuery = true)
    int insertClaimOnConflictDoNothing(
            @Param("messageId") UUID messageId,
            @Param("consumerGroup") String consumerGroup,
            @Param("eventType") String eventType,
            @Param("receivedAt") Instant receivedAt,
            @Param("processedAt") Instant processedAt
    );

    boolean existsByMessageIdAndConsumerGroup(UUID messageId, String consumerGroup);
}
