package com.crosspaynet.messaging;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.messaging.contracts.MessageId;
import com.crosspaynet.messaging.domain.PaymentMessage;
import com.crosspaynet.messaging.domain.PersistedMessageStateException;
import com.crosspaynet.messaging.infrastructure.persistence.PaymentMessageEntity;
import com.crosspaynet.messaging.infrastructure.persistence.PaymentMessageMapper;
import com.crosspaynet.messaging.infrastructure.persistence.PaymentMessageRepository;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentId;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, PaymentMessageMapper.class})
class MessagingPersistenceIntegrationTest {

    private static final BicCode SENDER_BIC = new BicCode("CHASUS33XXX");
    private static final BicCode RECEIVER_BIC = new BicCode("DEUTDEFFXXX");
    private static final Instant T0 = Instant.parse("2026-10-25T14:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-25T14:01:00Z");
    private static final Instant T2 = Instant.parse("2026-10-25T14:02:00Z");
    private static final Instant T3 = Instant.parse("2026-10-25T14:03:00Z");

    @Autowired
    private PaymentMessageRepository repository;

    @Autowired
    private PaymentMessageMapper mapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistAndRestoreAllSixPaymentMessageStatusesAndVerifyZeroCrossSchemaForeignKeys() {
        // Verify zero foreign keys exist on messaging.payment_message
        Integer fkCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'messaging'
                  AND table_name = 'payment_message'
                  AND constraint_type = 'FOREIGN KEY'
                """,
                Integer.class
        );
        assertThat(fkCount).isZero();

        // 1. GENERATED
        PaymentMessage generated = newMessage(1);
        assertRoundTrip(generated);

        // 2. SIGNED
        PaymentMessage signed = newMessage(1);
        signed.sign(T1);
        assertRoundTrip(signed);

        // 3. SENT
        PaymentMessage sent = newMessage(1);
        sent.sign(T1);
        sent.markSent(T2);
        assertRoundTrip(sent);

        // 4. ACKNOWLEDGED
        PaymentMessage acknowledged = newMessage(1);
        acknowledged.sign(T1);
        acknowledged.markSent(T2);
        acknowledged.acknowledge("PACS002-ACSC-9001", T3);
        assertRoundTrip(acknowledged);

        // 5a. NACKNOWLEDGED with reason
        PaymentMessage nacknowledgedWithReason = newMessage(1);
        nacknowledgedWithReason.sign(T1);
        nacknowledgedWithReason.markSent(T2);
        nacknowledgedWithReason.nacknowledge("AC04", "Closed account number", T3);
        assertRoundTrip(nacknowledgedWithReason);

        // 5b. NACKNOWLEDGED with null reason
        PaymentMessage nacknowledgedNullReason = newMessage(1);
        nacknowledgedNullReason.sign(T1);
        nacknowledgedNullReason.markSent(T2);
        nacknowledgedNullReason.nacknowledge("AM04", null, T3);
        assertRoundTrip(nacknowledgedNullReason);

        // 6a. TIMEOUT with reason
        PaymentMessage timeoutWithReason = newMessage(1);
        timeoutWithReason.sign(T1);
        timeoutWithReason.markSent(T2);
        timeoutWithReason.markTimeout("Peer read timeout after 30s", T3);
        assertRoundTrip(timeoutWithReason);

        // 6b. TIMEOUT with null reason
        PaymentMessage timeoutNullReason = newMessage(1);
        timeoutNullReason.sign(T1);
        timeoutNullReason.markSent(T2);
        timeoutNullReason.markTimeout(null, T3);
        assertRoundTrip(timeoutNullReason);
    }

    @Test
    void shouldEnforceDatabaseConstraintsAndUtf16SupplementaryUnicodeLengthBounds() {
        // 1. Amount scale > 4 (no silent rounding!) rejected with SQLSTATE 23514
        assertCheckViolation(
                sampleRawRow()
                        .withAmount(new BigDecimal("100.12345")),
                "23514"
        );

        // 2. Unsupported currency rejected with SQLSTATE 23514
        assertCheckViolation(
                sampleRawRow()
                        .withCurrency("CAD"),
                "23514"
        );

        // 3. Invalid BIC rejected with SQLSTATE 23514
        assertCheckViolation(
                sampleRawRow()
                        .withSenderBic("badbic"),
                "23514"
        );

        // 4. Attempt number out of bounds (0 or 101) rejected with SQLSTATE 23514
        assertCheckViolation(
                sampleRawRow()
                        .withAttemptNumber(0),
                "23514"
        );
        assertCheckViolation(
                sampleRawRow()
                        .withAttemptNumber(101),
                "23514"
        );

        // 5. State invariant violations (3VL-safe):
        //    - ACKNOWLEDGED with null acknowledgment_reference -> 23514
        assertCheckViolation(
                sampleRawRow()
                        .withStatus("ACKNOWLEDGED")
                        .withAckRef(null),
                "23514"
        );
        //    - NACKNOWLEDGED with null peer_rejection_code -> 23514
        assertCheckViolation(
                sampleRawRow()
                        .withStatus("NACKNOWLEDGED")
                        .withRejCode(null)
                        .withRejReason("Orphan reason without code"),
                "23514"
        );
        //    - SENT with non-null acknowledgment_reference -> 23514
        assertCheckViolation(
                sampleRawRow()
                        .withStatus("SENT")
                        .withAckRef("ILLEGAL-ACK-IN-SENT"),
                "23514"
        );

        // 6. UTF-16 supplementary Unicode length alignment (U+1F600 occupies 1 code point in Postgres
        //    but 2 UTF-16 code units in Java String.length()):
        String emoji = "\uD83D\uDE00"; // 2 UTF-16 code units

        // beneficiary_name max 140 UTF-16 units: 139 ASCII + 1 emoji = 140 code points, 141 UTF-16 units -> rejected!
        assertCheckViolation(
                sampleRawRow()
                        .withBeneficiaryName("A".repeat(139) + emoji),
                "23514"
        );
        // route_reference max 100 UTF-16 units: 99 ASCII + 1 emoji = 101 UTF-16 units -> rejected!
        assertCheckViolation(
                sampleRawRow()
                        .withRouteReference("R".repeat(99) + emoji),
                "23514"
        );
        // acknowledgment_reference max 100 UTF-16 units: 99 ASCII + 1 emoji = 101 UTF-16 units -> rejected!
        assertCheckViolation(
                sampleRawRow()
                        .withStatus("ACKNOWLEDGED")
                        .withAckRef("K".repeat(99) + emoji),
                "23514"
        );
        // peer_rejection_reason max 500 UTF-16 units: 499 ASCII + 1 emoji = 501 UTF-16 units -> rejected!
        assertCheckViolation(
                sampleRawRow()
                        .withStatus("NACKNOWLEDGED")
                        .withRejCode("AC04")
                        .withRejReason("E".repeat(499) + emoji),
                "23514"
        );
        // timeout_reason max 500 UTF-16 units: 499 ASCII + 1 emoji = 501 UTF-16 units -> rejected!
        assertCheckViolation(
                sampleRawRow()
                        .withStatus("TIMEOUT")
                        .withTimeoutReason("T".repeat(499) + emoji),
                "23514"
        );

        // Positive UTF-16 boundary check: 138 ASCII + 1 emoji = 140 UTF-16 units succeeds and restores cleanly!
        PaymentMessage exactBoundaryMsg = PaymentMessage.generate(
                MessageId.generate(),
                PaymentId.generate(),
                1,
                SENDER_BIC,
                RECEIVER_BIC,
                "R".repeat(98) + emoji,
                Money.of("100.0000", "USD"),
                "A".repeat(138) + emoji,
                "DE89370400440532013000",
                T0
        );
        assertRoundTrip(exactBoundaryMsg);
    }

    @Test
    void shouldDetectTamperedPersistedPayloadOrDigestOnRead() {
        PaymentMessage msg = newMessage(1);
        msg.sign(T1);
        msg.markSent(T2);

        PaymentMessageEntity saved = repository.saveAndFlush(mapper.toEntity(msg));
        entityManager.clear();

        // Tamper with the stored payload directly via SQL
        jdbcTemplate.update(
                "UPDATE messaging.payment_message SET payload = ? WHERE message_id = ?",
                msg.getPayload().replace("150.2500", "999.9900"),
                saved.getMessageId()
        );
        entityManager.clear();

        PaymentMessageEntity tamperedEntity = repository.findById(saved.getMessageId()).orElseThrow();
        assertThatThrownBy(() -> mapper.toDomain(tamperedEntity))
                .isInstanceOf(PersistedMessageStateException.class);
    }

    private void assertRoundTrip(PaymentMessage domain) {
        PaymentMessageEntity saved = repository.saveAndFlush(mapper.toEntity(domain));
        entityManager.clear();

        PaymentMessageEntity loaded = repository.findById(saved.getMessageId()).orElseThrow();
        PaymentMessage restored = mapper.toDomain(loaded);
        assertThat(restored.toView()).isEqualTo(domain.toView());
    }

    private static PaymentMessage newMessage(int attemptNumber) {
        return PaymentMessage.generate(
                MessageId.generate(),
                PaymentId.generate(),
                attemptNumber,
                SENDER_BIC,
                RECEIVER_BIC,
                "ROUTE-SWIFT-DE-01",
                Money.of("150.2500", "USD"),
                "Siemens AG",
                "DE89370400440532013000",
                T0
        );
    }

    private void assertCheckViolation(RawRow row, String expectedSqlState) {
        String actualState = jdbcTemplate.execute((ConnectionCallback<String>) con -> {
            Savepoint sp = con.setSavepoint();
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO messaging.payment_message (
                        message_id, version, payment_id, attempt_number, idempotency_key,
                        message_type, sender_bic, receiver_bic, route_reference, amount,
                        currency, beneficiary_name, beneficiary_account_number, payload,
                        payload_digest, status, acknowledgment_reference, peer_rejection_code,
                        peer_rejection_reason, timeout_reason, created_at, updated_at
                    ) VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setObject(1, row.messageId);
                ps.setObject(2, row.paymentId);
                ps.setInt(3, row.attemptNumber);
                ps.setString(4, row.idempotencyKey);
                ps.setString(5, row.messageType);
                ps.setString(6, row.senderBic);
                ps.setString(7, row.receiverBic);
                ps.setString(8, row.routeReference);
                ps.setBigDecimal(9, row.amount);
                ps.setString(10, row.currency);
                ps.setString(11, row.beneficiaryName);
                ps.setString(12, row.beneficiaryAccountNumber);
                ps.setString(13, row.payload);
                ps.setString(14, row.payloadDigest);
                ps.setString(15, row.status);
                ps.setString(16, row.ackRef);
                ps.setString(17, row.rejCode);
                ps.setString(18, row.rejReason);
                ps.setString(19, row.timeoutReason);
                ps.setTimestamp(20, Timestamp.from(T0));
                ps.setTimestamp(21, Timestamp.from(T1));
                ps.executeUpdate();
                con.rollback(sp);
                return "NO_ERROR";
            } catch (SQLException ex) {
                con.rollback(sp);
                return ex.getSQLState();
            }
        });
        assertThat(actualState).isEqualTo(expectedSqlState);
    }

    private static RawRow sampleRawRow() {
        UUID paymentId = UUID.randomUUID();
        return new RawRow(
                UUID.randomUUID(),
                paymentId,
                1,
                "MSG:" + paymentId + ":ATTEMPT:1",
                "PACS_008",
                "CHASUS33XXX",
                "DEUTDEFFXXX",
                "ROUTE-01",
                new BigDecimal("100.0000"),
                "USD",
                "Siemens AG",
                "DE89370400440532013000",
                "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\nsample",
                "a".repeat(64),
                "SENT",
                null,
                null,
                null,
                null
        );
    }

    private record RawRow(
            UUID messageId,
            UUID paymentId,
            int attemptNumber,
            String idempotencyKey,
            String messageType,
            String senderBic,
            String receiverBic,
            String routeReference,
            BigDecimal amount,
            String currency,
            String beneficiaryName,
            String beneficiaryAccountNumber,
            String payload,
            String payloadDigest,
            String status,
            String ackRef,
            String rejCode,
            String rejReason,
            String timeoutReason
    ) {
        RawRow withAmount(BigDecimal newAmount) {
            return new RawRow(messageId, paymentId, attemptNumber, idempotencyKey, messageType, senderBic, receiverBic,
                    routeReference, newAmount, currency, beneficiaryName, beneficiaryAccountNumber, payload,
                    payloadDigest, status, ackRef, rejCode, rejReason, timeoutReason);
        }

        RawRow withCurrency(String newCurrency) {
            return new RawRow(messageId, paymentId, attemptNumber, idempotencyKey, messageType, senderBic, receiverBic,
                    routeReference, amount, newCurrency, beneficiaryName, beneficiaryAccountNumber, payload,
                    payloadDigest, status, ackRef, rejCode, rejReason, timeoutReason);
        }

        RawRow withSenderBic(String newSenderBic) {
            return new RawRow(messageId, paymentId, attemptNumber, idempotencyKey, messageType, newSenderBic, receiverBic,
                    routeReference, amount, currency, beneficiaryName, beneficiaryAccountNumber, payload,
                    payloadDigest, status, ackRef, rejCode, rejReason, timeoutReason);
        }

        RawRow withAttemptNumber(int newAttemptNumber) {
            return new RawRow(messageId, paymentId, newAttemptNumber, "MSG:" + paymentId + ":ATTEMPT:" + newAttemptNumber,
                    messageType, senderBic, receiverBic, routeReference, amount, currency, beneficiaryName,
                    beneficiaryAccountNumber, payload, payloadDigest, status, ackRef, rejCode, rejReason, timeoutReason);
        }

        RawRow withBeneficiaryName(String newName) {
            return new RawRow(messageId, paymentId, attemptNumber, idempotencyKey, messageType, senderBic, receiverBic,
                    routeReference, amount, currency, newName, beneficiaryAccountNumber, payload,
                    payloadDigest, status, ackRef, rejCode, rejReason, timeoutReason);
        }

        RawRow withRouteReference(String newRouteRef) {
            return new RawRow(messageId, paymentId, attemptNumber, idempotencyKey, messageType, senderBic, receiverBic,
                    newRouteRef, amount, currency, beneficiaryName, beneficiaryAccountNumber, payload,
                    payloadDigest, status, ackRef, rejCode, rejReason, timeoutReason);
        }

        RawRow withStatus(String newStatus) {
            return new RawRow(messageId, paymentId, attemptNumber, idempotencyKey, messageType, senderBic, receiverBic,
                    routeReference, amount, currency, beneficiaryName, beneficiaryAccountNumber, payload,
                    payloadDigest, newStatus, ackRef, rejCode, rejReason, timeoutReason);
        }

        RawRow withAckRef(String newAckRef) {
            return new RawRow(messageId, paymentId, attemptNumber, idempotencyKey, messageType, senderBic, receiverBic,
                    routeReference, amount, currency, beneficiaryName, beneficiaryAccountNumber, payload,
                    payloadDigest, status, newAckRef, rejCode, rejReason, timeoutReason);
        }

        RawRow withRejCode(String newRejCode) {
            return new RawRow(messageId, paymentId, attemptNumber, idempotencyKey, messageType, senderBic, receiverBic,
                    routeReference, amount, currency, beneficiaryName, beneficiaryAccountNumber, payload,
                    payloadDigest, status, ackRef, newRejCode, rejReason, timeoutReason);
        }

        RawRow withRejReason(String newRejReason) {
            return new RawRow(messageId, paymentId, attemptNumber, idempotencyKey, messageType, senderBic, receiverBic,
                    routeReference, amount, currency, beneficiaryName, beneficiaryAccountNumber, payload,
                    payloadDigest, status, ackRef, rejCode, newRejReason, timeoutReason);
        }

        RawRow withTimeoutReason(String newTimeoutReason) {
            return new RawRow(messageId, paymentId, attemptNumber, idempotencyKey, messageType, senderBic, receiverBic,
                    routeReference, amount, currency, beneficiaryName, beneficiaryAccountNumber, payload,
                    payloadDigest, status, ackRef, rejCode, rejReason, newTimeoutReason);
        }
    }
}
