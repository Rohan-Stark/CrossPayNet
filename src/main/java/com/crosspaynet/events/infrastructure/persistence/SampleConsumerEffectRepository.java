package com.crosspaynet.events.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SampleConsumerEffectRepository extends JpaRepository<SampleConsumerEffectEntity, UUID> {

    Optional<SampleConsumerEffectEntity> findByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);

    long countByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);
}
