package com.crosspaynet.events.infrastructure.kafka;

import com.crosspaynet.banking.FundsReserved;
import com.crosspaynet.events.contracts.EventEnvelope;
import com.crosspaynet.events.contracts.EventValidationException;
import com.crosspaynet.messaging.contracts.PaymentSentToNetwork;
import com.crosspaynet.payment.contracts.PaymentInstructionReceived;
import com.crosspaynet.settlement.contracts.PaymentSettledEvent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.util.backoff.FixedBackOff;

import java.io.IOException;
import java.math.BigDecimal;

@Configuration(proxyBeanMethods = false)
public class EventsModuleConfiguration {

    public static final String TOPIC_FUNDS_RESERVED = "crosspaynet.banking.funds-reserved.v1";
    public static final String TOPIC_INSTRUCTION_RECEIVED = "crosspaynet.payment.instruction-received.v1";
    public static final String TOPIC_MESSAGE_DISPATCHED = "crosspaynet.messaging.message-dispatched.v1";
    public static final String TOPIC_PAYMENT_SETTLED = "crosspaynet.settlement.payment-settled.v1";

    public static final String DLT_SUFFIX = ".DLT";

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(EventExternalizationConfiguration.annotatedAsExternalized())
                .route(FundsReserved.class, e -> RoutingTarget.forTarget(TOPIC_FUNDS_RESERVED).andKey(e.accountId().toString()))
                .route(PaymentInstructionReceived.class, e -> RoutingTarget.forTarget(TOPIC_INSTRUCTION_RECEIVED).andKey(e.paymentId().toString()))
                .route(PaymentSentToNetwork.class, e -> RoutingTarget.forTarget(TOPIC_MESSAGE_DISPATCHED).andKey(e.paymentId().toString()))
                .route(PaymentSettledEvent.class, e -> RoutingTarget.forTarget(TOPIC_PAYMENT_SETTLED).andKey(e.paymentId().toString()))
                .mapping(FundsReserved.class, e -> new EventEnvelope<>(
                        e.eventId(), "FundsReserved", 1, e.occurredAt(), e.correlationId(), e.causationId(), e
                ))
                .mapping(PaymentInstructionReceived.class, e -> new EventEnvelope<>(
                        e.eventId(), "PaymentInstructionReceived", 1, e.occurredAt(), e.correlationId(), e.causationId(), e
                ))
                .mapping(PaymentSentToNetwork.class, e -> new EventEnvelope<>(
                        e.eventId(), "PaymentSentToNetwork", 1, e.occurredAt(), e.correlationId(), e.causationId(), e
                ))
                .mapping(PaymentSettledEvent.class, e -> new EventEnvelope<>(
                        e.eventId(), "PaymentSettledEvent", 1, e.occurredAt(), e.correlationId(), e.causationId(), e
                ))
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(moneyJacksonModule());
    }

    @Bean
    public Module moneyJacksonModule() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(com.crosspaynet.common.money.Money.class, new JsonDeserializer<>() {
            @Override
            public com.crosspaynet.common.money.Money deserialize(
                    JsonParser p,
                    DeserializationContext ctxt
            ) throws IOException {
                JsonNode node = p.getCodec().readTree(p);
                BigDecimal amount = new BigDecimal(node.get("amount").asText());
                String code = "USD";
                if (node.hasNonNull("currency")) {
                    JsonNode cur = node.get("currency");
                    code = cur.isObject() && cur.hasNonNull("code") ? cur.get("code").asText() : cur.asText();
                }
                return new com.crosspaynet.common.money.Money(amount, new com.crosspaynet.common.money.Currency(code));
            }
        });
        return module;
    }

    @Bean
    public DefaultErrorHandler defaultErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaOperations,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition())
        );

        // Bounded retries: 3 attempts with 1-second interval for transient failures
        FixedBackOff backOff = new FixedBackOff(1000L, 3L);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // Unretryable / poison-pill exceptions route immediately to DLT
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                SerializationException.class,
                JsonProcessingException.class,
                EventValidationException.class,
                IllegalArgumentException.class
        );

        return errorHandler;
    }

    @Bean
    public NewTopic topicFundsReserved() {
        return TopicBuilder.name(TOPIC_FUNDS_RESERVED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic topicFundsReservedDlt() {
        return TopicBuilder.name(TOPIC_FUNDS_RESERVED + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic topicInstructionReceived() {
        return TopicBuilder.name(TOPIC_INSTRUCTION_RECEIVED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic topicInstructionReceivedDlt() {
        return TopicBuilder.name(TOPIC_INSTRUCTION_RECEIVED + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic topicMessageDispatched() {
        return TopicBuilder.name(TOPIC_MESSAGE_DISPATCHED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic topicMessageDispatchedDlt() {
        return TopicBuilder.name(TOPIC_MESSAGE_DISPATCHED + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic topicPaymentSettled() {
        return TopicBuilder.name(TOPIC_PAYMENT_SETTLED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic topicPaymentSettledDlt() {
        return TopicBuilder.name(TOPIC_PAYMENT_SETTLED + DLT_SUFFIX).partitions(1).replicas(1).build();
    }
}
