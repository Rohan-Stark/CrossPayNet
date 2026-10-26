package com.crosspaynet.events.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sample_consumer_effect", schema = "system")
public class SampleConsumerEffectEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "consumer_group", nullable = false)
    private String consumerGroup;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "effect_payload", nullable = false)
    private String effectPayload;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    public SampleConsumerEffectEntity() {}

    public SampleConsumerEffectEntity(UUID id, UUID eventId, String consumerGroup, String eventType, String effectPayload, Instant appliedAt) {
        this.id = id;
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.eventType = eventType;
        this.effectPayload = effectPayload;
        this.appliedAt = appliedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEffectPayload() {
        return effectPayload;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
