package com.crosspaynet.events.sample;

import com.crosspaynet.events.contracts.ConsumerExecutionResult;
import com.crosspaynet.events.contracts.EventEnvelope;
import com.crosspaynet.events.contracts.EventValidationException;
import com.crosspaynet.events.contracts.TransactionalConsumerExecutor;
import com.crosspaynet.events.infrastructure.kafka.EventsModuleConfiguration;
import com.crosspaynet.events.infrastructure.persistence.SampleConsumerEffectEntity;
import com.crosspaynet.events.infrastructure.persistence.SampleConsumerEffectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Meaningful sample reliable consumer demonstrating idempotent inbox claim,
 * atomic business effect execution, transient retry recovery, and poison-pill DLT routing.
 */
@Component
public class SampleReliableConsumer {

    private static final Logger log = LoggerFactory.getLogger(SampleReliableConsumer.class);
    public static final String CONSUMER_GROUP = "crosspaynet-sample-consumer";

    private final TransactionalConsumerExecutor consumerExecutor;
    private final SampleConsumerEffectRepository effectRepository;
    private final ObjectMapper objectMapper;

    // Test instrumentation counters
    private final AtomicInteger invocationCount = new AtomicInteger(0);
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger duplicateSkippedCount = new AtomicInteger(0);
    private volatile boolean simulateTransientFailure = false;

    public SampleReliableConsumer(
            TransactionalConsumerExecutor consumerExecutor,
            SampleConsumerEffectRepository effectRepository,
            ObjectMapper objectMapper
    ) {
        this.consumerExecutor = Objects.requireNonNull(consumerExecutor, "consumerExecutor cannot be null.");
        this.effectRepository = Objects.requireNonNull(effectRepository, "effectRepository cannot be null.");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null.");
    }

    @KafkaListener(
            topics = EventsModuleConfiguration.TOPIC_FUNDS_RESERVED,
            groupId = CONSUMER_GROUP
    )
    public void onFundsReserved(String payload) throws Exception {
        invocationCount.incrementAndGet();
        log.info("SampleReliableConsumer received payload: {}", payload);

        JsonNode rootNode = objectMapper.readTree(payload);
        if (!rootNode.hasNonNull("eventId") || !rootNode.hasNonNull("eventType")) {
            throw new EventValidationException("Malformed event envelope: missing eventId or eventType.");
        }

        UUID eventId = UUID.fromString(rootNode.get("eventId").asText());
        String eventType = rootNode.get("eventType").asText();
        String correlationId = rootNode.hasNonNull("correlationId") ? rootNode.get("correlationId").asText() : "";

        if ("POISON_PILL".equals(correlationId)) {
            throw new EventValidationException("Poison pill detected in correlationId: " + correlationId);
        }

        if (simulateTransientFailure) {
            simulateTransientFailure = false; // Reset so subsequent retry succeeds
            throw new RuntimeException("Simulated transient business failure during consumer processing.");
        }

        ConsumerExecutionResult result = consumerExecutor.executeIdempotent(
                eventId,
                CONSUMER_GROUP,
                eventType,
                () -> {
                    SampleConsumerEffectEntity effect = new SampleConsumerEffectEntity(
                            UUID.randomUUID(),
                            eventId,
                            CONSUMER_GROUP,
                            eventType,
                            payload,
                            Instant.now()
                    );
                    effectRepository.save(effect);
                }
        );

        if (result == ConsumerExecutionResult.PROCESSED) {
            processedCount.incrementAndGet();
            log.info("Successfully processed event '{}' for group '{}'", eventId, CONSUMER_GROUP);
        } else {
            duplicateSkippedCount.incrementAndGet();
            log.info("Duplicate event '{}' skipped for group '{}'", eventId, CONSUMER_GROUP);
        }
    }

    public void setSimulateTransientFailure(boolean simulateTransientFailure) {
        this.simulateTransientFailure = simulateTransientFailure;
    }

    public int getInvocationCount() {
        return invocationCount.get();
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public int getDuplicateSkippedCount() {
        return duplicateSkippedCount.get();
    }

    public void resetCounts() {
        invocationCount.set(0);
        processedCount.set(0);
        duplicateSkippedCount.set(0);
        simulateTransientFailure = false;
    }
}
