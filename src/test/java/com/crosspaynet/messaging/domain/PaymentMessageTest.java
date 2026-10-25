package com.crosspaynet.messaging.domain;

import com.crosspaynet.common.money.Money;
import com.crosspaynet.messaging.contracts.AcknowledgeMessageCommand;
import com.crosspaynet.messaging.contracts.DispatchPaymentMessageCommand;
import com.crosspaynet.messaging.contracts.InvalidMessageStateException;
import com.crosspaynet.messaging.contracts.MessageId;
import com.crosspaynet.messaging.contracts.MessagingValidationException;
import com.crosspaynet.messaging.contracts.NacknowledgeMessageCommand;
import com.crosspaynet.messaging.contracts.PaymentMessageStatus;
import com.crosspaynet.messaging.contracts.PaymentMessageType;
import com.crosspaynet.messaging.contracts.TimeoutMessageCommand;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentMessageTest {

    private static final BicCode SENDER_BIC = new BicCode("CHASUS33XXX");
    private static final BicCode RECEIVER_BIC = new BicCode("DEUTDEFFXXX");
    private static final Instant T0 = Instant.parse("2026-10-25T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-25T12:00:01Z");
    private static final Instant T2 = Instant.parse("2026-10-25T12:00:02Z");
    private static final Instant T3 = Instant.parse("2026-10-25T12:00:03Z");

    @Test
    void shouldGenerateDeterministicPacs008PayloadAndDigestAndProgressThroughFullStateMachine() {
        MessageId messageId = new MessageId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        PaymentId paymentId = new PaymentId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));

        PaymentMessage msg1 = PaymentMessage.generate(
                messageId,
                paymentId,
                1,
                SENDER_BIC,
                RECEIVER_BIC,
                "ROUTE-SWIFT-EUR-01",
                Money.of("250.50", "USD"),
                "Deutsche Telekom AG",
                "DE89370400440532013000",
                T0
        );
        PaymentMessage msg2 = PaymentMessage.generate(
                messageId,
                paymentId,
                1,
                SENDER_BIC,
                RECEIVER_BIC,
                "  ROUTE-SWIFT-EUR-01  ",
                Money.of("250.5000", "USD"),
                "  Deutsche Telekom AG  ",
                "  DE89370400440532013000  ",
                T0
        );

        // Deterministic payload & SHA-256 integrity digest (250.50 vs 250.5000 produce identical canonical output)
        assertThat(msg1.getPayload()).isEqualTo(msg2.getPayload());
        assertThat(msg1.getPayloadDigest()).isEqualTo(msg2.getPayloadDigest());
        assertThat(msg1.getPayload()).startsWith(Pacs008PayloadBuilder.SCHEMA_IDENTIFIER + "\n");
        assertThat(msg1.getPayload()).contains("GrpHdr.MsgId=36:11111111-2222-3333-4444-555555555555");
        assertThat(msg1.getPayload()).contains("GrpHdr.InstgAgt.BICFI=11:CHASUS33XXX");
        assertThat(msg1.getPayload()).contains("GrpHdr.InstdAgt.BICFI=11:DEUTDEFFXXX");
        assertThat(msg1.getPayload()).contains("CdtTrfTxInf.PmtId.InstrId=36:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(msg1.getPayload()).contains("CdtTrfTxInf.PmtId.EndToEndId=38:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee:1");
        assertThat(msg1.getPayload()).contains("CdtTrfTxInf.IntrBkSttlmAmt.Ccy=3:USD");
        assertThat(msg1.getPayload()).contains("CdtTrfTxInf.IntrBkSttlmAmt.Val=8:250.5000");
        assertThat(msg1.getIdempotencyKey()).isEqualTo("MSG:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee:ATTEMPT:1");
        assertThat(msg1.getStatus()).isEqualTo(PaymentMessageStatus.GENERATED);

        // Cannot skip SIGNED -> SENT directly from GENERATED
        assertThatThrownBy(() -> msg1.markSent(T1))
                .isInstanceOf(InvalidMessageStateException.class);
        assertThatThrownBy(() -> msg1.acknowledge("ACK-1", T1))
                .isInstanceOf(InvalidMessageStateException.class);

        // GENERATED -> SIGNED (idempotent on repeat)
        msg1.sign(T1);
        assertThat(msg1.getStatus()).isEqualTo(PaymentMessageStatus.SIGNED);
        assertThat(msg1.getUpdatedAt()).isEqualTo(T1);
        msg1.sign(T1);
        assertThat(msg1.getStatus()).isEqualTo(PaymentMessageStatus.SIGNED);

        // Cannot acknowledge/nacknowledge/timeout while still SIGNED
        assertThatThrownBy(() -> msg1.acknowledge("ACK-1", T2))
                .isInstanceOf(InvalidMessageStateException.class);
        assertThatThrownBy(() -> msg1.nacknowledge("AC04", "Closed account", T2))
                .isInstanceOf(InvalidMessageStateException.class);
        assertThatThrownBy(() -> msg1.markTimeout("Timed out", T2))
                .isInstanceOf(InvalidMessageStateException.class);

        // SIGNED -> SENT (idempotent on repeat)
        msg1.markSent(T2);
        assertThat(msg1.getStatus()).isEqualTo(PaymentMessageStatus.SENT);
        assertThat(msg1.getUpdatedAt()).isEqualTo(T2);
        msg1.markSent(T2);
        assertThat(msg1.getStatus()).isEqualTo(PaymentMessageStatus.SENT);

        // SENT -> ACKNOWLEDGED (idempotent with same reference, rejects different reference or other terminal transitions)
        msg1.acknowledge("  PACS002-ACSC-001  ", T3);
        assertThat(msg1.getStatus()).isEqualTo(PaymentMessageStatus.ACKNOWLEDGED);
        assertThat(msg1.getAcknowledgmentReference()).contains("PACS002-ACSC-001");
        assertThat(msg1.getStatus().isTerminal()).isTrue();

        msg1.acknowledge("PACS002-ACSC-001", T3);
        assertThatThrownBy(() -> msg1.acknowledge("PACS002-DIFFERENT", T3))
                .isInstanceOf(InvalidMessageStateException.class);
        assertThatThrownBy(() -> msg1.nacknowledge("AC04", "Closed", T3))
                .isInstanceOf(InvalidMessageStateException.class);
        assertThatThrownBy(() -> msg1.markTimeout("Timeout", T3))
                .isInstanceOf(InvalidMessageStateException.class);
    }

    @Test
    void shouldSupportNacknowledgedAndTimeoutTerminalStatesWithIdempotentReplayAndConflictRejection() {
        // Case A: SENT -> NACKNOWLEDGED
        PaymentMessage nackMsg = sampleSentMessage(1);
        nackMsg.nacknowledge("  AC04  ", "  Beneficiary account closed  ", T3);
        assertThat(nackMsg.getStatus()).isEqualTo(PaymentMessageStatus.NACKNOWLEDGED);
        assertThat(nackMsg.getPeerRejectionCode()).contains("AC04");
        assertThat(nackMsg.getPeerRejectionReason()).contains("Beneficiary account closed");
        assertThat(nackMsg.getStatus().isTerminal()).isTrue();

        // Idempotent replay with identical code and reason
        nackMsg.nacknowledge("AC04", "Beneficiary account closed", T3);
        // Conflicting code or reason is rejected
        assertThatThrownBy(() -> nackMsg.nacknowledge("AM04", "Beneficiary account closed", T3))
                .isInstanceOf(InvalidMessageStateException.class);
        assertThatThrownBy(() -> nackMsg.nacknowledge("AC04", "Different reason", T3))
                .isInstanceOf(InvalidMessageStateException.class);
        assertThatThrownBy(() -> nackMsg.acknowledge("ACK-AFTER-NACK", T3))
                .isInstanceOf(InvalidMessageStateException.class);
        assertThatThrownBy(() -> nackMsg.markTimeout("Timeout after NACK", T3))
                .isInstanceOf(InvalidMessageStateException.class);

        // Case B: SENT -> TIMEOUT
        PaymentMessage timeoutMsg = sampleSentMessage(2);
        timeoutMsg.markTimeout("  Gateway read timeout after 30s  ", T3);
        assertThat(timeoutMsg.getStatus()).isEqualTo(PaymentMessageStatus.TIMEOUT);
        assertThat(timeoutMsg.getTimeoutReason()).contains("Gateway read timeout after 30s");
        assertThat(timeoutMsg.getStatus().isTerminal()).isTrue();

        // Idempotent replay with identical timeoutReason
        timeoutMsg.markTimeout("Gateway read timeout after 30s", T3);
        // Conflicting timeoutReason or transition to ACKNOWLEDGED/NACKNOWLEDGED is rejected
        assertThatThrownBy(() -> timeoutMsg.markTimeout("Different timeout reason", T3))
                .isInstanceOf(InvalidMessageStateException.class);
        assertThatThrownBy(() -> timeoutMsg.acknowledge("ACK-AFTER-TIMEOUT", T3))
                .isInstanceOf(InvalidMessageStateException.class);
        assertThatThrownBy(() -> timeoutMsg.nacknowledge("AC04", "NACK after timeout", T3))
                .isInstanceOf(InvalidMessageStateException.class);
    }

    @Test
    void shouldVerifyPayloadAndDigestOnRestoreAndDetectTampering() {
        PaymentMessage original = sampleSentMessage(1);
        original.acknowledge("ACK-VERIFIED-01", T3);

        PaymentMessage restored = PaymentMessage.restore(
                original.getId(),
                original.getPaymentId(),
                original.getAttemptNumber(),
                original.getIdempotencyKey(),
                original.getMessageType(),
                original.getSenderBic(),
                original.getReceiverBic(),
                original.getRouteReference(),
                original.getAmount(),
                original.getBeneficiaryName(),
                original.getBeneficiaryAccountNumber(),
                original.getPayload(),
                original.getPayloadDigest(),
                original.getStatus(),
                original.getAcknowledgmentReference().orElse(null),
                original.getPeerRejectionCode().orElse(null),
                original.getPeerRejectionReason().orElse(null),
                original.getTimeoutReason().orElse(null),
                original.getCreatedAt(),
                original.getUpdatedAt()
        );
        assertThat(restored.toView()).isEqualTo(original.toView());

        // 1. Tampered payload text with original digest -> rejected with PersistedMessageStateException
        String tamperedPayload = original.getPayload().replace("100.0000", "999.0000");
        assertThatThrownBy(() -> PaymentMessage.restore(
                original.getId(),
                original.getPaymentId(),
                original.getAttemptNumber(),
                original.getIdempotencyKey(),
                original.getMessageType(),
                original.getSenderBic(),
                original.getReceiverBic(),
                original.getRouteReference(),
                original.getAmount(),
                original.getBeneficiaryName(),
                original.getBeneficiaryAccountNumber(),
                tamperedPayload,
                original.getPayloadDigest(),
                original.getStatus(),
                original.getAcknowledgmentReference().orElse(null),
                null,
                null,
                null,
                original.getCreatedAt(),
                original.getUpdatedAt()
        )).isInstanceOf(PersistedMessageStateException.class);

        // 2. Tampered digest with original payload -> rejected with PersistedMessageStateException
        PayloadDigest forgedDigest = PayloadDigest.ofHex("a".repeat(64));
        assertThatThrownBy(() -> PaymentMessage.restore(
                original.getId(),
                original.getPaymentId(),
                original.getAttemptNumber(),
                original.getIdempotencyKey(),
                original.getMessageType(),
                original.getSenderBic(),
                original.getReceiverBic(),
                original.getRouteReference(),
                original.getAmount(),
                original.getBeneficiaryName(),
                original.getBeneficiaryAccountNumber(),
                original.getPayload(),
                forgedDigest,
                original.getStatus(),
                original.getAcknowledgmentReference().orElse(null),
                null,
                null,
                null,
                original.getCreatedAt(),
                original.getUpdatedAt()
        )).isInstanceOf(PersistedMessageStateException.class);

        // 3. Mismatched amount vs payload (even if digest was recomputed for tampered payload) -> rejected!
        PayloadDigest digestOfTamperedPayload = PayloadDigest.compute(tamperedPayload);
        assertThatThrownBy(() -> PaymentMessage.restore(
                original.getId(),
                original.getPaymentId(),
                original.getAttemptNumber(),
                original.getIdempotencyKey(),
                original.getMessageType(),
                original.getSenderBic(),
                original.getReceiverBic(),
                original.getRouteReference(),
                original.getAmount(),
                original.getBeneficiaryName(),
                original.getBeneficiaryAccountNumber(),
                tamperedPayload,
                digestOfTamperedPayload,
                original.getStatus(),
                original.getAcknowledgmentReference().orElse(null),
                null,
                null,
                null,
                original.getCreatedAt(),
                original.getUpdatedAt()
        )).isInstanceOf(PersistedMessageStateException.class);
    }

    @Test
    void shouldValidateCommandsAndValueObjectBoundaries() {
        PaymentId paymentId = PaymentId.generate();
        MessageId messageId = MessageId.generate();

        assertThatThrownBy(() -> new MessageId(null))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new DispatchPaymentMessageCommand(null, 1, SENDER_BIC))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new DispatchPaymentMessageCommand(paymentId, 0, SENDER_BIC))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new DispatchPaymentMessageCommand(paymentId, 101, SENDER_BIC))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new DispatchPaymentMessageCommand(paymentId, 1, null))
                .isInstanceOf(MessagingValidationException.class);

        assertThatThrownBy(() -> new AcknowledgeMessageCommand(null, "ACK-1"))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new AcknowledgeMessageCommand(messageId, "   "))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new AcknowledgeMessageCommand(messageId, "A".repeat(101)))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new AcknowledgeMessageCommand(messageId, "ACK\u0007"))
                .isInstanceOf(MessagingValidationException.class);

        assertThatThrownBy(() -> new NacknowledgeMessageCommand(null, "AC04", "Reason"))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new NacknowledgeMessageCommand(messageId, "BAD CODE!", "Reason"))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new NacknowledgeMessageCommand(messageId, "C".repeat(36), "Reason"))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new NacknowledgeMessageCommand(messageId, "AC04", "R".repeat(501)))
                .isInstanceOf(MessagingValidationException.class);

        assertThatThrownBy(() -> new TimeoutMessageCommand(null, "Reason"))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new TimeoutMessageCommand(messageId, "T".repeat(501)))
                .isInstanceOf(MessagingValidationException.class);
        assertThatThrownBy(() -> new TimeoutMessageCommand(messageId, "BAD\nREASON"))
                .isInstanceOf(MessagingValidationException.class);
    }

    private static PaymentMessage sampleSentMessage(int attemptNumber) {
        PaymentMessage msg = PaymentMessage.generate(
                MessageId.generate(),
                PaymentId.generate(),
                attemptNumber,
                SENDER_BIC,
                RECEIVER_BIC,
                "ROUTE-SWIFT-01",
                Money.of("100.0000", "USD"),
                "Siemens AG",
                "DE89370400440532013000",
                T0
        );
        msg.sign(T1);
        msg.markSent(T2);
        return msg;
    }
}
