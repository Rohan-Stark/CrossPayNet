package com.crosspaynet.events;

import com.crosspaynet.KafkaTestContainerConfig;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.events.infrastructure.kafka.EventsModuleConfiguration;
import com.crosspaynet.events.infrastructure.persistence.InboxRepository;
import com.crosspaynet.events.infrastructure.persistence.SampleConsumerEffectRepository;
import com.crosspaynet.events.sample.SampleReliableConsumer;
import com.crosspaynet.messaging.MessagingService;
import com.crosspaynet.messaging.contracts.DispatchPaymentMessageCommand;
import com.crosspaynet.payment.PaymentService;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.CommitPaymentExecutionCommand;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.settlement.SettlementService;
import com.crosspaynet.settlement.contracts.SettlePaymentCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "spring.modulith.events.jdbc.schema=system")
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class KafkaReliabilityAndConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private SampleReliableConsumer sampleConsumer;

    @Autowired
    private InboxRepository inboxRepository;

    @Autowired
    private SampleConsumerEffectRepository effectRepository;

    @Autowired
    private BankingService bankingService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private MessagingService messagingService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        sampleConsumer.resetCounts();
    }

    @AfterEach
    void tearDown() {
        sampleConsumer.resetCounts();
    }

    @Test
    void shouldDeduplicateDuplicateDeliveryAndRecordSingleInboxEntry() {
        UUID eventId = UUID.randomUUID();
        String jsonPayload = """
                {
                    "eventId": "%s",
                    "eventType": "FundsReserved",
                    "eventVersion": 1,
                    "occurredAt": "%s",
                    "correlationId": "DEDUP-TEST",
                    "causationId": null,
                    "payload": {
                        "amount": {"amount": "100.0000", "currency": "USD"}
                    }
                }
                """.formatted(eventId, Instant.now());

        // 1. Send first message
        kafkaTemplate.send(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED, eventId.toString(), jsonPayload.getBytes(StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(sampleConsumer.getProcessedCount()).isEqualTo(1);
            assertThat(inboxRepository.existsByMessageIdAndConsumerGroup(eventId, SampleReliableConsumer.CONSUMER_GROUP))
                    .isTrue();
            assertThat(effectRepository.countByEventIdAndConsumerGroup(eventId, SampleReliableConsumer.CONSUMER_GROUP))
                    .isEqualTo(1);
        });

        // 2. Send identical message (duplicate delivery)
        kafkaTemplate.send(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED, eventId.toString(), jsonPayload.getBytes(StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(sampleConsumer.getInvocationCount()).isGreaterThanOrEqualTo(2);
            assertThat(sampleConsumer.getDuplicateSkippedCount()).isEqualTo(1);
            // Effect applied still exactly once!
            assertThat(effectRepository.countByEventIdAndConsumerGroup(eventId, SampleReliableConsumer.CONSUMER_GROUP))
                    .isEqualTo(1);
        });
    }

    @Test
    void shouldRollbackInboxClaimWhenBusinessEffectFailsAndSucceedOnRetry() {
        UUID eventId = UUID.randomUUID();
        String jsonPayload = """
                {
                    "eventId": "%s",
                    "eventType": "FundsReserved",
                    "eventVersion": 1,
                    "occurredAt": "%s",
                    "correlationId": "RETRY-TEST",
                    "causationId": null,
                    "payload": {
                        "amount": {"amount": "200.0000", "currency": "USD"}
                    }
                }
                """.formatted(eventId, Instant.now());

        // Configure transient failure on first invocation
        sampleConsumer.setSimulateTransientFailure(true);

        kafkaTemplate.send(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED, eventId.toString(), jsonPayload.getBytes(StandardCharsets.UTF_8));

        // Bounded retry will retry after 1s backoff and succeed
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(sampleConsumer.getInvocationCount()).isGreaterThanOrEqualTo(2);
            assertThat(sampleConsumer.getProcessedCount()).isEqualTo(1);
            assertThat(inboxRepository.existsByMessageIdAndConsumerGroup(eventId, SampleReliableConsumer.CONSUMER_GROUP))
                    .isTrue();
            assertThat(effectRepository.countByEventIdAndConsumerGroup(eventId, SampleReliableConsumer.CONSUMER_GROUP))
                    .isEqualTo(1);
        });
    }

    @Test
    void shouldRoutePoisonPillToDeadLetterTopicWithDiagnosticHeaders() {
        UUID eventId = UUID.randomUUID();
        String poisonPayload = """
                {
                    "eventId": "%s",
                    "eventType": "FundsReserved",
                    "eventVersion": 1,
                    "occurredAt": "%s",
                    "correlationId": "POISON_PILL",
                    "causationId": null,
                    "payload": {}
                }
                """.formatted(eventId, Instant.now());

        kafkaTemplate.send(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED, eventId.toString(), poisonPayload.getBytes(StandardCharsets.UTF_8));

        // Read from DLT topic to verify parking
        Consumer<String, String> consumer = createTestConsumer(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED + EventsModuleConfiguration.DLT_SUFFIX);
        try {
            ConsumerRecord<String, String> dltRecord = null;
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline) && dltRecord == null) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (eventId.toString().equals(record.key())
                            && record.value() != null
                            && record.value().contains("POISON_PILL")) {
                        dltRecord = record;
                        break;
                    }
                }
            }
            assertThat(dltRecord).isNotNull();
            assertThat(dltRecord.key()).isEqualTo(eventId.toString());
            assertThat(dltRecord.value()).contains("POISON_PILL");
            assertThat(dltRecord.value()).contains(eventId.toString());

            // Verify failed processing never falsely marks the event as processed in inbox or effect tables
            assertThat(inboxRepository.existsByMessageIdAndConsumerGroup(eventId, SampleReliableConsumer.CONSUMER_GROUP))
                    .isFalse();
            assertThat(effectRepository.countByEventIdAndConsumerGroup(eventId, SampleReliableConsumer.CONSUMER_GROUP))
                    .isZero();

            // Diagnostic headers verification
            boolean hasExceptionHeader = false;
            boolean hasValidationException = false;
            for (Header header : dltRecord.headers()) {
                if (header.key().contains("exception-fqcn")) {
                    hasExceptionHeader = true;
                }
                if (header.key().contains("exception")) {
                    String val = new String(header.value(), StandardCharsets.UTF_8);
                    if (val.contains("EventValidationException")) {
                        hasValidationException = true;
                    }
                }
            }
            assertThat(hasExceptionHeader).isTrue();
            assertThat(hasValidationException).isTrue();
        } finally {
            consumer.close();
        }
    }

    @Test
    void shouldRouteMalformedRawBytesPayloadToDeadLetterTopicPreservingKeyAndPayload() {
        String key = "RAW-MALFORMED-" + UUID.randomUUID();
        byte[] malformedRawBytes = "NOT_VALID_JSON_PAYLOAD_{{{".getBytes(StandardCharsets.UTF_8);

        kafkaTemplate.send(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED, key, malformedRawBytes);

        Consumer<String, String> consumer = createTestConsumer(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED + EventsModuleConfiguration.DLT_SUFFIX);
        try {
            ConsumerRecord<String, String> dltRecord = null;
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline) && dltRecord == null) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (key.equals(record.key())) {
                        dltRecord = record;
                        break;
                    }
                }
            }
            assertThat(dltRecord).isNotNull();
            assertThat(dltRecord.key()).isEqualTo(key);
            assertThat(dltRecord.value()).isEqualTo("NOT_VALID_JSON_PAYLOAD_{{{");

            boolean hasJsonException = false;
            for (Header header : dltRecord.headers()) {
                if (header.key().contains("exception")) {
                    String val = new String(header.value(), StandardCharsets.UTF_8);
                    if (val.contains("JsonParseException") || val.contains("JsonProcessingException")) {
                        hasJsonException = true;
                    }
                }
            }
            assertThat(hasJsonException).isTrue();
        } finally {
            consumer.close();
        }
    }

    @Test
    void shouldPublishDomainEventsWithAggregatePartitionKeys() throws Exception {
        AccountId sourceAccountId = createAccount("USD", "ACTIVE", new BigDecimal("10000.0000"));
        PaymentReference ref = PaymentReference.of("PAY-TOPIC-" + shortId());
        Money amount = Money.of("150.0000", "USD");

        // 1. FundsReserved from Banking
        bankingService.reserveFunds(sourceAccountId, amount, ref);

        // 2. PaymentInstructionReceived from Payment
        PaymentView payment = paymentService.initiatePayment(new InitiatePaymentCommand(
                sourceAccountId,
                ref,
                new BeneficiaryDetails("Global Corp", "DE89370400440532013000", new BicCode("DEUTDEFFXXX")),
                amount
        ));

        // 3. PaymentSentToNetwork from Messaging
        paymentService.approveCompliance(payment.paymentId());
        paymentService.commitToExecution(new CommitPaymentExecutionCommand(payment.paymentId(), "ROUTE-TEST-KAFKA-1"));
        messagingService.dispatchPaymentMessage(new DispatchPaymentMessageCommand(
                payment.paymentId(),
                1,
                new BicCode("CPAYUS33XXX")
        ));

        // 4. PaymentSettledEvent from Settlement
        settlementService.settlePayment(new SettlePaymentCommand(
                payment.paymentId(),
                "CLEARING_USD_" + shortId(),
                "SETTLE-TOPIC-" + shortId()
        ));

        String paymentIdStr = payment.paymentId().getValue().toString();
        String accountIdStr = sourceAccountId.getValue().toString();

        // Verify that all externalized publication records for this payment/account exist AND reach completion_date IS NOT NULL
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Integer completedExternalizedCount = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*) FROM system.event_publication
                    WHERE listener_id LIKE '%EventExternalizerModuleListener%'
                      AND (serialized_event LIKE ? OR serialized_event LIKE ?)
                      AND completion_date IS NOT NULL
                    """,
                    Integer.class,
                    "%" + ref.getValue() + "%",
                    "%" + paymentIdStr + "%"
            );
            assertThat(completedExternalizedCount).isGreaterThanOrEqualTo(4);

            Integer incompleteExternalizedCount = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*) FROM system.event_publication
                    WHERE listener_id LIKE '%EventExternalizerModuleListener%'
                      AND (serialized_event LIKE ? OR serialized_event LIKE ?)
                      AND completion_date IS NULL
                    """,
                    Integer.class,
                    "%" + ref.getValue() + "%",
                    "%" + paymentIdStr + "%"
            );
            assertThat(incompleteExternalizedCount).isZero();
        });

        // Verify actual Kafka messages, EventEnvelope deserialization, and aggregate partition keys across all 4 topics
        UUID fundsReservedEventId = assertTopicContainsEnvelopeWithKey(
                EventsModuleConfiguration.TOPIC_FUNDS_RESERVED,
                accountIdStr,
                "FundsReserved",
                ref.getValue()
        );
        assertTopicContainsEnvelopeWithKey(
                EventsModuleConfiguration.TOPIC_INSTRUCTION_RECEIVED,
                paymentIdStr,
                "PaymentInstructionReceived",
                paymentIdStr
        );
        assertTopicContainsEnvelopeWithKey(
                EventsModuleConfiguration.TOPIC_MESSAGE_DISPATCHED,
                paymentIdStr,
                "PaymentSentToNetwork",
                paymentIdStr
        );
        assertTopicContainsEnvelopeWithKey(
                EventsModuleConfiguration.TOPIC_PAYMENT_SETTLED,
                paymentIdStr,
                "PaymentSettledEvent",
                paymentIdStr
        );

        // Verify SampleReliableConsumer also deserialized and processed the real externalized FundsReserved EventEnvelope
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(inboxRepository.existsByMessageIdAndConsumerGroup(
                    fundsReservedEventId,
                    SampleReliableConsumer.CONSUMER_GROUP
            )).isTrue();
            assertThat(effectRepository.countByEventIdAndConsumerGroup(
                    fundsReservedEventId,
                    SampleReliableConsumer.CONSUMER_GROUP
            )).isEqualTo(1);
        });
    }

    private UUID assertTopicContainsEnvelopeWithKey(
            String topic,
            String expectedKey,
            String expectedEventType,
            String expectedPayloadSubstring
    ) throws Exception {
        Consumer<String, String> consumer = createTestConsumer(topic);
        try {
            ConsumerRecord<String, String> matchedRecord = null;
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline) && matchedRecord == null) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (expectedKey.equals(record.key())
                            && record.value() != null
                            && record.value().contains(expectedPayloadSubstring)) {
                        matchedRecord = record;
                        break;
                    }
                }
            }
            assertThat(matchedRecord)
                    .as("Expected message on topic %s with aggregate partition key %s", topic, expectedKey)
                    .isNotNull();
            assertThat(matchedRecord.key()).isEqualTo(expectedKey);

            JsonNode envelopeNode = objectMapper.readTree(matchedRecord.value());
            assertThat(envelopeNode.hasNonNull("eventId")).isTrue();
            UUID eventId = UUID.fromString(envelopeNode.get("eventId").asText());
            assertThat(eventId).isNotNull();
            assertThat(envelopeNode.get("eventType").asText()).isEqualTo(expectedEventType);
            assertThat(envelopeNode.get("eventVersion").asInt()).isEqualTo(1);
            assertThat(envelopeNode.hasNonNull("occurredAt")).isTrue();
            assertThat(envelopeNode.hasNonNull("payload")).isTrue();
            return eventId;
        } finally {
            consumer.close();
        }
    }

    private Consumer<String, String> createTestConsumer(String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainerConfig.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        ConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        Consumer<String, String> consumer = factory.createConsumer();
        consumer.subscribe(Collections.singletonList(topic));
        return consumer;
    }

    private AccountId createAccount(String currency, String status, BigDecimal availableBalance) {
        UUID customerId = UUID.randomUUID();
        AccountId accountId = AccountId.generate();
        jdbcTemplate.update("INSERT INTO banking.customer (customer_id) VALUES (?)", customerId);
        jdbcTemplate.update(
                "INSERT INTO banking.account (account_id, customer_id, currency, status, available_balance, reserved_amount, version) "
                        + "VALUES (?, ?, ?, ?, ?, 0.0000, 0)",
                accountId.getValue(),
                customerId,
                currency,
                status,
                availableBalance
        );
        return accountId;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
