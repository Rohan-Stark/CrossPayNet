package com.crosspaynet.events;

import com.crosspaynet.banking.FundsReserved;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.events.contracts.EventEnvelope;
import com.crosspaynet.events.infrastructure.kafka.EventsModuleConfiguration;
import com.crosspaynet.messaging.contracts.PaymentSentToNetwork;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentInstructionReceived;
import com.crosspaynet.settlement.contracts.PaymentSettledEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventEnvelopeAndValueObjectsTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new EventsModuleConfiguration().moneyJacksonModule());

    @Test
    void testEnvelopeValidationRules() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        // Null eventId
        assertThrows(NullPointerException.class, () ->
                new EventEnvelope<>(null, "TestEvent", 1, now, "corr-1", null, "payload"));

        // Null / blank eventType
        assertThrows(IllegalArgumentException.class, () ->
                new EventEnvelope<>(eventId, null, 1, now, "corr-1", null, "payload"));
        assertThrows(IllegalArgumentException.class, () ->
                new EventEnvelope<>(eventId, "   ", 1, now, "corr-1", null, "payload"));

        // Invalid version
        assertThrows(IllegalArgumentException.class, () ->
                new EventEnvelope<>(eventId, "TestEvent", 0, now, "corr-1", null, "payload"));
        assertThrows(IllegalArgumentException.class, () ->
                new EventEnvelope<>(eventId, "TestEvent", -1, now, "corr-1", null, "payload"));

        // Null occurredAt
        assertThrows(NullPointerException.class, () ->
                new EventEnvelope<>(eventId, "TestEvent", 1, null, "corr-1", null, "payload"));

        // Null payload
        assertThrows(NullPointerException.class, () ->
                new EventEnvelope<>(eventId, "TestEvent", 1, now, "corr-1", null, null));
    }

    @Test
    void testFundsReservedEnvelopeMappingAndStableIdentity() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-26T12:00:00Z");
        Money amount = Money.of("1500.00", "USD");

        FundsReserved event = new FundsReserved(
                eventId,
                accountId,
                token,
                "PAY-REF-100",
                amount,
                occurredAt,
                "CORR-100",
                "CAUS-100"
        );

        assertEquals(accountId, event.accountId());
        assertEquals("PAY-REF-100", event.paymentReference());

        EventEnvelope<FundsReserved> envelope = new EventEnvelope<>(
                event.eventId(),
                "FundsReserved",
                1,
                event.occurredAt(),
                event.correlationId(),
                event.causationId(),
                event
        );

        assertEquals(eventId, envelope.eventId());
        assertEquals("FundsReserved", envelope.eventType());
        assertEquals(1, envelope.eventVersion());
        assertEquals(occurredAt, envelope.occurredAt());
        assertEquals("CORR-100", envelope.correlationId());
        assertEquals("CAUS-100", envelope.causationId());
        assertSame(event, envelope.payload());

        // Retransmission / multiple mapping calls preserve identical eventId
        EventEnvelope<FundsReserved> envelope2 = new EventEnvelope<>(
                event.eventId(), "FundsReserved", 1, event.occurredAt(), event.correlationId(), event.causationId(), event
        );
        assertEquals(envelope.eventId(), envelope2.eventId());

        // JSON Serialization round-trip
        String json = objectMapper.writeValueAsString(envelope);
        assertNotNull(json);
        assertTrue(json.contains(eventId.toString()));
        assertTrue(json.contains("FundsReserved"));

        EventEnvelope<FundsReserved> deserialized = objectMapper.readValue(
                json, new TypeReference<EventEnvelope<FundsReserved>>() {}
        );
        assertEquals(eventId, deserialized.eventId());
        assertEquals("FundsReserved", deserialized.eventType());
        assertEquals(event.amount(), deserialized.payload().amount());
    }

    @Test
    void testPaymentInstructionReceivedEnvelopeMapping() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID sourceAccountId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-26T12:05:00Z");
        Money amount = Money.of("25000.00", "EUR");
        BeneficiaryDetails beneficiary = new BeneficiaryDetails(
                "Acme Corp",
                "DE89370400440532013000",
                new BicCode("DEUTDEDDXXX")
        );

        PaymentInstructionReceived event = new PaymentInstructionReceived(
                eventId,
                paymentId,
                sourceAccountId,
                "PAY-EUR-001",
                amount,
                beneficiary,
                occurredAt,
                "PAY-EUR-001",
                null
        );

        EventEnvelope<PaymentInstructionReceived> envelope = new EventEnvelope<>(
                event.eventId(),
                "PaymentInstructionReceived",
                1,
                event.occurredAt(),
                event.correlationId(),
                event.causationId(),
                event
        );

        assertEquals(eventId, envelope.eventId());
        assertEquals(paymentId, envelope.payload().paymentId());

        String json = objectMapper.writeValueAsString(envelope);
        assertTrue(json.contains(paymentId.toString()));
        assertTrue(json.contains("DEUTDEDDXXX"));
    }

    @Test
    void testPaymentSentToNetworkEnvelopeMapping() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-26T12:10:00Z");

        PaymentSentToNetwork event = new PaymentSentToNetwork(
                eventId,
                paymentId,
                messageId,
                1,
                "ROUTE-EUR-CORR-1",
                occurredAt,
                paymentId.toString(),
                null
        );

        EventEnvelope<PaymentSentToNetwork> envelope = new EventEnvelope<>(
                event.eventId(),
                "PaymentSentToNetwork",
                1,
                event.occurredAt(),
                event.correlationId(),
                event.causationId(),
                event
        );

        assertEquals(eventId, envelope.eventId());
        assertEquals(messageId, envelope.payload().messageId());
        assertEquals(1, envelope.payload().attemptNumber());
    }

    @Test
    void testPaymentSettledEventEnvelopeMapping() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID ledgerTxId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-26T12:15:00Z");
        Money amount = Money.of("500.00", "GBP");

        PaymentSettledEvent event = new PaymentSettledEvent(
                eventId,
                paymentId,
                ledgerTxId,
                "SETTLE-REF-999",
                amount,
                occurredAt,
                "SETTLE-REF-999",
                null
        );

        EventEnvelope<PaymentSettledEvent> envelope = new EventEnvelope<>(
                event.eventId(),
                "PaymentSettledEvent",
                1,
                event.occurredAt(),
                event.correlationId(),
                event.causationId(),
                event
        );

        assertEquals(eventId, envelope.eventId());
        assertEquals(ledgerTxId, envelope.payload().ledgerTransactionId());
        assertEquals("SETTLE-REF-999", envelope.payload().settlementReference());
    }
}
