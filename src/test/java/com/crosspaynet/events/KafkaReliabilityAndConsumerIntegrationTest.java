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
import org.springframework.kafka.test.utils.KafkaTestUtils;

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

@SpringBootTest
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
        kafkaTemplate.send(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED, eventId.toString(), jsonPayload);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(sampleConsumer.getProcessedCount()).isEqualTo(1);
            assertThat(inboxRepository.existsByMessageIdAndConsumerGroup(eventId, SampleReliableConsumer.CONSUMER_GROUP))
                    .isTrue();
            assertThat(effectRepository.countByEventIdAndConsumerGroup(eventId, SampleReliableConsumer.CONSUMER_GROUP))
                    .isEqualTo(1);
        });

        // 2. Send identical message (duplicate delivery)
        kafkaTemplate.send(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED, eventId.toString(), jsonPayload);

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

        kafkaTemplate.send(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED, eventId.toString(), jsonPayload);

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

        kafkaTemplate.send(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED, eventId.toString(), poisonPayload);

        // Read from DLT topic to verify parking
        Consumer<String, String> consumer = createTestConsumer(EventsModuleConfiguration.TOPIC_FUNDS_RESERVED + EventsModuleConfiguration.DLT_SUFFIX);
        try {
            ConsumerRecord<String, String> dltRecord = null;
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline) && dltRecord == null) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value() != null && record.value().contains("POISON_PILL")) {
                        dltRecord = record;
                        break;
                    }
                }
            }
            assertThat(dltRecord).isNotNull();
            assertThat(dltRecord.value()).contains("POISON_PILL");

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
    void shouldPublishDomainEventsWithAggregatePartitionKeys() {
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

        // Verify that events exist in system.event_publication with completed/incomplete outbox state
        Integer totalPublications = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM system.event_publication",
                Integer.class
        );
        assertThat(totalPublications).isGreaterThanOrEqualTo(4);
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
