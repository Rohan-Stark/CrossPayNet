package com.crosspaynet.payment;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentInstruction;
import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.domain.InstructionFingerprint;
import com.crosspaynet.payment.domain.Payment;
import com.crosspaynet.payment.domain.PersistedPaymentStateException;
import com.crosspaynet.payment.infrastructure.persistence.PaymentEntity;
import com.crosspaynet.payment.infrastructure.persistence.PaymentMapper;
import com.crosspaynet.payment.infrastructure.persistence.PaymentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, PaymentMapper.class})
class PaymentPersistenceIntegrationTest {

    private static final Currency USD = new Currency("USD");
    private static final String VALID_DUMMY_FINGERPRINT = "a".repeat(64);
    private static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-25T10:01:00Z");
    private static final Instant T2 = Instant.parse("2026-10-25T10:02:00Z");
    private static final Instant T3 = Instant.parse("2026-10-25T10:03:00Z");
    private static final Instant T4 = Instant.parse("2026-10-25T10:04:00Z");
    private static final Instant T5 = Instant.parse("2026-10-25T10:05:00Z");

    @Autowired
    private PaymentRepository repository;

    @Autowired
    private PaymentMapper mapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // =========================================================================
    // A. Persistence & Restoration Round-Trip Across All 9 Statuses & Variants
    // =========================================================================

    @Test
    void shouldPersistAndRestoreAllNinePaymentStatusesAndPermittedNullabilityVariants() {
        // 1. VALIDATING
        Payment validating = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-RT-VALIDATING"), T0);
        assertRoundTrip(validating);

        // 2. COMPLIANCE_HOLD
        Payment complianceHold = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-RT-COMPLIANCE"), T0);
        complianceHold.confirmFundsReserved(new ReservationToken(UUID.randomUUID()), T1);
        assertRoundTrip(complianceHold);

        // 3. ROUTING
        Payment routing = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-RT-ROUTING"), T0);
        routing.confirmFundsReserved(new ReservationToken(UUID.randomUUID()), T1);
        routing.approveCompliance(T2);
        assertRoundTrip(routing);

        // 4. EXECUTING
        Payment executing = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-RT-EXECUTING"), T0);
        executing.confirmFundsReserved(new ReservationToken(UUID.randomUUID()), T1);
        executing.approveCompliance(T2);
        executing.commitToExecution("ROUTE-SWIFT-001", T3);
        assertRoundTrip(executing);

        // 5. PENDING_EXTERNAL
        Payment pendingExternal = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-RT-PENDING"), T0);
        pendingExternal.confirmFundsReserved(new ReservationToken(UUID.randomUUID()), T1);
        pendingExternal.approveCompliance(T2);
        pendingExternal.commitToExecution("ROUTE-SWIFT-002", T3);
        pendingExternal.markDispatched("EXT-MSG-002", T4);
        assertRoundTrip(pendingExternal);

        // 6. SETTLED
        Payment settled = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-RT-SETTLED"), T0);
        settled.confirmFundsReserved(new ReservationToken(UUID.randomUUID()), T1);
        settled.approveCompliance(T2);
        settled.commitToExecution("ROUTE-SWIFT-003", T3);
        settled.markDispatched("EXT-MSG-003", T4);
        settled.recordSettlement(UUID.randomUUID(), T5);
        assertRoundTrip(settled);

        // 7a. REJECTED pre-reservation (reservation_token IS NULL) across all 3 reservation failure reasons
        for (PaymentReasonCode reason : List.of(
                PaymentReasonCode.INSUFFICIENT_FUNDS,
                PaymentReasonCode.INVALID_ACCOUNT_STATE,
                PaymentReasonCode.CURRENCY_MISMATCH)) {
            Payment rejectedPreRes = Payment.initiate(
                    PaymentId.generate(),
                    sampleInstruction("REF-RT-REJ-PRE-" + reason.name()),
                    T0
            );
            rejectedPreRes.rejectOnReservationFailure(reason, "Banking rejected: " + reason.name(), T1);
            assertRoundTrip(rejectedPreRes);
        }

        // 7b. REJECTED post-reservation (reservation_token IS NOT NULL) across both pre-commitment rejection reasons
        for (PaymentReasonCode reason : List.of(
                PaymentReasonCode.COMPLIANCE_REJECTED,
                PaymentReasonCode.NO_ROUTE_AVAILABLE)) {
            Payment rejectedPostRes = Payment.initiate(
                    PaymentId.generate(),
                    sampleInstruction("REF-RT-REJ-POST-" + reason.name()),
                    T0
            );
            rejectedPostRes.confirmFundsReserved(new ReservationToken(UUID.randomUUID()), T1);
            rejectedPostRes.rejectBeforeCommitment(reason, "Pre-commitment rejection: " + reason.name(), T2);
            assertRoundTrip(rejectedPostRes);
        }

        // 8a. CANCELLED without reservation_token (cancelled from VALIDATING)
        Payment cancelledNoToken = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-RT-CANC-NOTOK"), T0);
        cancelledNoToken.cancel("Customer cancelled in VALIDATING", T1);
        assertRoundTrip(cancelledNoToken);

        // 8b. CANCELLED with reservation_token (cancelled from ROUTING, null reasonDetail)
        Payment cancelledWithToken = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-RT-CANC-TOK"), T0);
        cancelledWithToken.confirmFundsReserved(new ReservationToken(UUID.randomUUID()), T1);
        cancelledWithToken.approveCompliance(T2);
        cancelledWithToken.cancel(null, T3);
        assertRoundTrip(cancelledWithToken);

        // 9a. FAILED without external_dispatch_reference (failed in EXECUTING before dispatch)
        Payment failedNoDispatch = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-RT-FAIL-NODISP"), T0);
        failedNoDispatch.confirmFundsReserved(new ReservationToken(UUID.randomUUID()), T1);
        failedNoDispatch.approveCompliance(T2);
        failedNoDispatch.commitToExecution("ROUTE-FAIL-001", T3);
        failedNoDispatch.failBeforeSettlement(PaymentReasonCode.OPERATIONAL_FAILURE, "Dispatch adapter error", T4);
        assertRoundTrip(failedNoDispatch);

        // 9b. FAILED with external_dispatch_reference (failed in PENDING_EXTERNAL after dispatch)
        Payment failedWithDispatch = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-RT-FAIL-DISP"), T0);
        failedWithDispatch.confirmFundsReserved(new ReservationToken(UUID.randomUUID()), T1);
        failedWithDispatch.approveCompliance(T2);
        failedWithDispatch.commitToExecution("ROUTE-FAIL-002", T3);
        failedWithDispatch.markDispatched("EXT-NACK-002", T4);
        failedWithDispatch.failBeforeSettlement(PaymentReasonCode.EXTERNAL_NETWORK_REJECTED, "SWIFT NACK RJCT", T5);
        assertRoundTrip(failedWithDispatch);
    }

    @Test
    void shouldUpdateManagedEntityInPlaceThroughLifecycleAndIncrementVersion() {
        Payment payment = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-LIFECYCLE-UPD"), T0);
        PaymentEntity saved = repository.saveAndFlush(mapper.toEntity(payment));
        assertThat(saved.getVersion()).isEqualTo(0L);

        UUID tokenUuid = UUID.randomUUID();
        payment.confirmFundsReserved(new ReservationToken(tokenUuid), T1);
        mapper.updateEntity(payment, saved);
        repository.saveAndFlush(saved);
        assertThat(saved.getVersion()).isEqualTo(1L);

        payment.approveCompliance(T2);
        payment.commitToExecution("ROUTE-INPLACE-1", T3);
        payment.markDispatched("EXT-INPLACE-1", T4);
        UUID ledgerTxId = UUID.randomUUID();
        payment.recordSettlement(ledgerTxId, T5);
        mapper.updateEntity(payment, saved);
        repository.saveAndFlush(saved);
        assertThat(saved.getVersion()).isEqualTo(2L);

        entityManager.clear();
        Optional<PaymentEntity> found = repository.findBySourceAccountIdAndPaymentReference(
                payment.getInstruction().sourceAccountId().getValue(),
                payment.getInstruction().paymentReference().getValue()
        );
        assertThat(found).isPresent();
        Payment restored = mapper.toDomain(found.get());
        assertThat(restored.getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(restored.getReservationToken()).contains(new ReservationToken(tokenUuid));
        assertThat(restored.getRouteReference()).contains("ROUTE-INPLACE-1");
        assertThat(restored.getExternalDispatchReference()).contains("EXT-INPLACE-1");
        assertThat(restored.getLedgerTransactionId()).contains(ledgerTxId);
    }

    // =========================================================================
    // B. Mandatory NULL-Reason-Code & CANCELLED Reason-Code Constraint Tests
    // =========================================================================

    @Test
    void shouldRejectRejectedStatusWithNullReasonCodeAtDatabaseConstraint() {
        // Pre-reservation REJECTED with NULL status_reason_code
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-REJ-NULL-PRE",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("100.0000"),
                        "USD",
                        "Beneficiary One",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "REJECTED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        T0,
                        T1
                ),
                "23514",
                "chk_payment_state_invariants"
        );

        // Post-reservation REJECTED with NULL status_reason_code
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-REJ-NULL-POST",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("100.0000"),
                        "USD",
                        "Beneficiary One",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "REJECTED",
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        T0,
                        T1
                ),
                "23514",
                "chk_payment_state_invariants"
        );
    }

    @Test
    void shouldRejectCancelledStatusWithNullReasonCodeAtDatabaseConstraint() {
        // CANCELLED without reservation_token and NULL status_reason_code
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-CANC-NULL-NOTOK",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("100.0000"),
                        "USD",
                        "Beneficiary One",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "CANCELLED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        T0,
                        T1
                ),
                "23514",
                "chk_payment_state_invariants"
        );

        // CANCELLED with reservation_token and NULL status_reason_code
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-CANC-NULL-TOK",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("100.0000"),
                        "USD",
                        "Beneficiary One",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "CANCELLED",
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        T0,
                        T1
                ),
                "23514",
                "chk_payment_state_invariants"
        );
    }

    @Test
    void shouldRejectFailedStatusWithNullReasonCodeAtDatabaseConstraint() {
        // FAILED without external_dispatch_reference and NULL status_reason_code
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-FAIL-NULL-NODISP",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("100.0000"),
                        "USD",
                        "Beneficiary One",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "FAILED",
                        UUID.randomUUID(),
                        "ROUTE-001",
                        null,
                        null,
                        null,
                        null,
                        T0,
                        T1
                ),
                "23514",
                "chk_payment_state_invariants"
        );

        // FAILED with external_dispatch_reference and NULL status_reason_code
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-FAIL-NULL-DISP",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("100.0000"),
                        "USD",
                        "Beneficiary One",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "FAILED",
                        UUID.randomUUID(),
                        "ROUTE-001",
                        "EXT-DISP-001",
                        null,
                        null,
                        null,
                        T0,
                        T1
                ),
                "23514",
                "chk_payment_state_invariants"
        );
    }

    @Test
    void shouldAcceptCustomerCancelledAndRejectAllOtherReasonCodesForCancelledAtDatabaseConstraint() {
        // Positive proof: CANCELLED with CUSTOMER_CANCELLED succeeds (both with and without reservation_token)
        insertRawPayment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "REF-CANC-VALID-1",
                VALID_DUMMY_FINGERPRINT,
                new BigDecimal("100.0000"),
                "USD",
                "Beneficiary One",
                "DE89370400440532013000",
                "DEUTDEFFXXX",
                "CANCELLED",
                null,
                null,
                null,
                null,
                "CUSTOMER_CANCELLED",
                "Customer requested cancellation",
                T0,
                T1
        );
        insertRawPayment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "REF-CANC-VALID-2",
                VALID_DUMMY_FINGERPRINT,
                new BigDecimal("100.0000"),
                "USD",
                "Beneficiary One",
                "DE89370400440532013000",
                "DEUTDEFFXXX",
                "CANCELLED",
                UUID.randomUUID(),
                null,
                null,
                null,
                "CUSTOMER_CANCELLED",
                null,
                T0,
                T1
        );

        // Negative proof: every non-CUSTOMER_CANCELLED PaymentReasonCode is rejected for CANCELLED
        for (PaymentReasonCode reasonCode : PaymentReasonCode.values()) {
            if (reasonCode == PaymentReasonCode.CUSTOMER_CANCELLED) {
                continue;
            }
            assertSqlFailureInSavepoint(
                    () -> insertRawPayment(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "REF-CANC-BAD-" + reasonCode.name(),
                            VALID_DUMMY_FINGERPRINT,
                            new BigDecimal("100.0000"),
                            "USD",
                            "Beneficiary One",
                            "DE89370400440532013000",
                            "DEUTDEFFXXX",
                            "CANCELLED",
                            UUID.randomUUID(),
                            null,
                            null,
                            null,
                            reasonCode.name(),
                            null,
                            T0,
                            T1
                    ),
                    "23514",
                    "chk_payment_state_invariants"
            );
        }
    }

    // =========================================================================
    // C. State/Column Invariant Rejections Across All 9 Statuses
    // =========================================================================

    @Test
    void shouldRejectInvalidStateColumnCombinationsAcrossAllNineStatusesAtDatabaseConstraint() {
        // 1. VALIDATING with non-null reservation_token
        assertStateInvariantFailure("VALIDATING", UUID.randomUUID(), null, null, null, null, null);
        // 1b. VALIDATING with non-null status_reason_code
        assertStateInvariantFailure("VALIDATING", null, null, null, null, "INSUFFICIENT_FUNDS", null);

        // 2. COMPLIANCE_HOLD with null reservation_token
        assertStateInvariantFailure("COMPLIANCE_HOLD", null, null, null, null, null, null);
        // 2b. COMPLIANCE_HOLD with non-null route_reference
        assertStateInvariantFailure("COMPLIANCE_HOLD", UUID.randomUUID(), "ROUTE-1", null, null, null, null);

        // 3. ROUTING with null reservation_token
        assertStateInvariantFailure("ROUTING", null, null, null, null, null, null);
        // 3b. ROUTING with non-null route_reference
        assertStateInvariantFailure("ROUTING", UUID.randomUUID(), "ROUTE-1", null, null, null, null);

        // 4. EXECUTING with null route_reference
        assertStateInvariantFailure("EXECUTING", UUID.randomUUID(), null, null, null, null, null);
        // 4b. EXECUTING with non-null external_dispatch_reference
        assertStateInvariantFailure("EXECUTING", UUID.randomUUID(), "ROUTE-1", "EXT-1", null, null, null);
        // 4c. EXECUTING with non-null ledger_transaction_id
        assertStateInvariantFailure("EXECUTING", UUID.randomUUID(), "ROUTE-1", null, UUID.randomUUID(), null, null);

        // 5. PENDING_EXTERNAL with null external_dispatch_reference
        assertStateInvariantFailure("PENDING_EXTERNAL", UUID.randomUUID(), "ROUTE-1", null, null, null, null);
        // 5b. PENDING_EXTERNAL with non-null ledger_transaction_id
        assertStateInvariantFailure("PENDING_EXTERNAL", UUID.randomUUID(), "ROUTE-1", "EXT-1", UUID.randomUUID(), null, null);

        // 6. SETTLED with null ledger_transaction_id
        assertStateInvariantFailure("SETTLED", UUID.randomUUID(), "ROUTE-1", "EXT-1", null, null, null);
        // 6b. SETTLED with null external_dispatch_reference (EXECUTING -> SETTLED skip attempt)
        assertStateInvariantFailure("SETTLED", UUID.randomUUID(), "ROUTE-1", null, UUID.randomUUID(), null, null);
        // 6c. SETTLED with non-null status_reason_code
        assertStateInvariantFailure("SETTLED", UUID.randomUUID(), "ROUTE-1", "EXT-1", UUID.randomUUID(), "OPERATIONAL_FAILURE", null);

        // 7. REJECTED with null reservation_token but COMPLIANCE_REJECTED (requires reservation_token IS NOT NULL)
        assertStateInvariantFailure("REJECTED", null, null, null, null, "COMPLIANCE_REJECTED", null);
        // 7b. REJECTED with non-null reservation_token but INSUFFICIENT_FUNDS (requires reservation_token IS NULL)
        assertStateInvariantFailure("REJECTED", UUID.randomUUID(), null, null, null, "INSUFFICIENT_FUNDS", null);
        // 7c. REJECTED with non-null route_reference
        assertStateInvariantFailure("REJECTED", UUID.randomUUID(), "ROUTE-1", null, null, "NO_ROUTE_AVAILABLE", null);

        // 8. CANCELLED with non-null route_reference
        assertStateInvariantFailure("CANCELLED", UUID.randomUUID(), "ROUTE-1", null, null, "CUSTOMER_CANCELLED", null);
        // 8b. CANCELLED with non-null ledger_transaction_id
        assertStateInvariantFailure("CANCELLED", UUID.randomUUID(), null, null, UUID.randomUUID(), "CUSTOMER_CANCELLED", null);

        // 9. FAILED with null reservation_token
        assertStateInvariantFailure("FAILED", null, "ROUTE-1", "EXT-1", null, "EXTERNAL_NETWORK_REJECTED", null);
        // 9b. FAILED with null route_reference
        assertStateInvariantFailure("FAILED", UUID.randomUUID(), null, "EXT-1", null, "OPERATIONAL_FAILURE", null);
        // 9c. FAILED with non-null ledger_transaction_id
        assertStateInvariantFailure("FAILED", UUID.randomUUID(), "ROUTE-1", "EXT-1", UUID.randomUUID(), "EXTERNAL_NETWORK_REJECTED", null);
        // 9d. FAILED with pre-commitment reason code
        assertStateInvariantFailure("FAILED", UUID.randomUUID(), "ROUTE-1", "EXT-1", null, "COMPLIANCE_REJECTED", null);
    }

    // =========================================================================
    // D. Numeric Scale/Range, Currency, Format & Timestamp Check Constraints
    // =========================================================================

    @Test
    void shouldRejectScaleGreaterThanFourAndOutOfBoundsAmountsWithoutSilentRounding() {
        // 1. Scale > 4 (5 decimal places: 10.12345) rejected without silent rounding
        assertSqlFailureInSavepoint(
                () -> insertRawPaymentWithAmount(new BigDecimal("10.12345")),
                "23514",
                "chk_payment_amount"
        );

        // 2. Zero, negative, and > MAX_AMOUNT rejected
        for (String badAmount : List.of("0", "0.0000", "-0.0001", "-100.0000", "1000000000000000.0000")) {
            assertSqlFailureInSavepoint(
                    () -> insertRawPaymentWithAmount(new BigDecimal(badAmount)),
                    "23514",
                    "chk_payment_amount"
            );
        }

        // 3. Positive boundary proof: MAX_AMOUNT (999999999999999.9999) and insignificant trailing zeros (10.120000) pass
        insertRawPaymentWithAmount(new BigDecimal("999999999999999.9999"));
        insertRawPaymentWithAmount(new BigDecimal("10.120000"));
    }

    @Test
    void shouldEnforceCurrencyBicFingerprintTextAndTimestampCheckConstraints() {
        // Unsupported currency
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-BAD-CURR",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("50.0000"),
                        "CAD",
                        "Beneficiary",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "VALIDATING",
                        null, null, null, null, null, null,
                        T0, T0
                ),
                "23514",
                "chk_payment_currency_supported"
        );

        // Invalid BIC format
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-BAD-BIC",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("50.0000"),
                        "USD",
                        "Beneficiary",
                        "DE89370400440532013000",
                        "bad_bic_12",
                        "VALIDATING",
                        null, null, null, null, null, null,
                        T0, T0
                ),
                "23514",
                "chk_payment_beneficiary_bic"
        );

        // Invalid instruction_fingerprint format
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-BAD-FP",
                        "NOT_HEX_64",
                        new BigDecimal("50.0000"),
                        "USD",
                        "Beneficiary",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "VALIDATING",
                        null, null, null, null, null, null,
                        T0, T0
                ),
                "23514",
                "chk_payment_instruction_fingerprint_format"
        );

        // Untrimmed payment_reference rejected by chk_payment_reference_non_blank
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        " UNTRIMMED-REF ",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("50.0000"),
                        "USD",
                        "Beneficiary",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "VALIDATING",
                        null, null, null, null, null, null,
                        T0, T0
                ),
                "23514",
                "chk_payment_reference_non_blank"
        );

        // updated_at < created_at rejected by chk_payment_timestamps
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-BAD-TS",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("50.0000"),
                        "USD",
                        "Beneficiary",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "VALIDATING",
                        null, null, null, null, null, null,
                        T2, T1
                ),
                "23514",
                "chk_payment_timestamps"
        );
    }

    @Test
    void shouldEnforceUtf16SupplementaryUnicodeLengthAlignmentInDatabaseConstraints() {
        String emoji = "\uD83D\uDCB0"; // U+1F4B0 MONEY BAG: 1 Unicode code point, 2 UTF-16 code units
        assertThat(emoji.codePointCount(0, emoji.length())).isEqualTo(1);
        assertThat(emoji.length()).isEqualTo(2);

        // 1. payment_reference: 254 ASCII + 1 supplementary = 255 code points, 256 UTF-16 code units -> rejected
        String ref256Utf16 = "R".repeat(254) + emoji;
        assertThat(ref256Utf16.codePointCount(0, ref256Utf16.length())).isEqualTo(255);
        assertThat(ref256Utf16.length()).isEqualTo(256);
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ref256Utf16,
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("50.0000"),
                        "USD",
                        "Beneficiary",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "VALIDATING",
                        null, null, null, null, null, null,
                        T0, T0
                ),
                "23514",
                "chk_payment_reference_non_blank"
        );

        // 2. beneficiary_name: 139 ASCII + 1 supplementary = 140 code points, 141 UTF-16 code units -> rejected
        String name141Utf16 = "N".repeat(139) + emoji;
        assertThat(name141Utf16.codePointCount(0, name141Utf16.length())).isEqualTo(140);
        assertThat(name141Utf16.length()).isEqualTo(141);
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-UTF16-NAME",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("50.0000"),
                        "USD",
                        name141Utf16,
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "VALIDATING",
                        null, null, null, null, null, null,
                        T0, T0
                ),
                "23514",
                "chk_payment_beneficiary_name"
        );

        // 3. beneficiary_account_number: 33 ASCII + 1 supplementary = 34 code points, 35 UTF-16 code units -> rejected
        String acct35Utf16 = "A".repeat(33) + emoji;
        assertThat(acct35Utf16.codePointCount(0, acct35Utf16.length())).isEqualTo(34);
        assertThat(acct35Utf16.length()).isEqualTo(35);
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-UTF16-ACCT",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("50.0000"),
                        "USD",
                        "Beneficiary",
                        acct35Utf16,
                        "DEUTDEFFXXX",
                        "VALIDATING",
                        null, null, null, null, null, null,
                        T0, T0
                ),
                "23514",
                "chk_payment_beneficiary_account"
        );

        // 4. route_reference: 99 ASCII + 1 supplementary = 100 code points, 101 UTF-16 code units -> rejected
        String route101Utf16 = "R".repeat(99) + emoji;
        assertThat(route101Utf16.codePointCount(0, route101Utf16.length())).isEqualTo(100);
        assertThat(route101Utf16.length()).isEqualTo(101);
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-UTF16-ROUTE",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("50.0000"),
                        "USD",
                        "Beneficiary",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "EXECUTING",
                        UUID.randomUUID(), route101Utf16, null, null, null, null,
                        T0, T1
                ),
                "23514",
                "chk_payment_route_reference"
        );

        // 5. external_dispatch_reference: 99 ASCII + 1 supplementary = 100 code points, 101 UTF-16 code units -> rejected
        String disp101Utf16 = "D".repeat(99) + emoji;
        assertThat(disp101Utf16.codePointCount(0, disp101Utf16.length())).isEqualTo(100);
        assertThat(disp101Utf16.length()).isEqualTo(101);
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-UTF16-DISP",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("50.0000"),
                        "USD",
                        "Beneficiary",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "PENDING_EXTERNAL",
                        UUID.randomUUID(), "ROUTE-OK", disp101Utf16, null, null, null,
                        T0, T1
                ),
                "23514",
                "chk_payment_external_dispatch_reference"
        );

        // 6. status_reason_detail: 499 ASCII + 1 supplementary = 500 code points, 501 UTF-16 code units -> rejected
        String detail501Utf16 = "M".repeat(499) + emoji;
        assertThat(detail501Utf16.codePointCount(0, detail501Utf16.length())).isEqualTo(500);
        assertThat(detail501Utf16.length()).isEqualTo(501);
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-UTF16-DETAIL",
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("50.0000"),
                        "USD",
                        "Beneficiary",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        "CANCELLED",
                        null, null, null, null, "CUSTOMER_CANCELLED", detail501Utf16,
                        T0, T1
                ),
                "23514",
                "chk_payment_reason_detail"
        );

        // 7. Positive boundary proof: exact UTF-16 maximums with supplementary Unicode persist and restore cleanly
        String ref255Utf16 = "R".repeat(253) + emoji;
        String name140Utf16 = "N".repeat(138) + emoji;
        String acct34Utf16 = "A".repeat(32) + emoji;
        String route100Utf16 = "R".repeat(98) + emoji;
        String disp100Utf16 = "D".repeat(98) + emoji;
        String detail500Utf16 = "M".repeat(498) + emoji;
        assertThat(ref255Utf16.length()).isEqualTo(255);
        assertThat(name140Utf16.length()).isEqualTo(140);
        assertThat(acct34Utf16.length()).isEqualTo(34);
        assertThat(route100Utf16.length()).isEqualTo(100);
        assertThat(disp100Utf16.length()).isEqualTo(100);
        assertThat(detail500Utf16.length()).isEqualTo(500);

        Payment unicodeBoundaryPayment = Payment.initiate(
                PaymentId.generate(),
                new PaymentInstruction(
                        new AccountId(UUID.randomUUID()),
                        new PaymentReference(ref255Utf16),
                        new BeneficiaryDetails(name140Utf16, acct34Utf16, new BicCode("DEUTDEFFXXX")),
                        Money.of("100.0000", "USD")
                ),
                T0
        );
        unicodeBoundaryPayment.confirmFundsReserved(new ReservationToken(UUID.randomUUID()), T1);
        unicodeBoundaryPayment.approveCompliance(T2);
        unicodeBoundaryPayment.commitToExecution(route100Utf16, T3);
        unicodeBoundaryPayment.markDispatched(disp100Utf16, T4);
        unicodeBoundaryPayment.failBeforeSettlement(
                PaymentReasonCode.EXTERNAL_NETWORK_REJECTED,
                detail500Utf16,
                T5
        );
        assertRoundTrip(unicodeBoundaryPayment);
    }

    // =========================================================================
    // E. Conflict-Safe Idempotent Claim, Optimistic Locking & Corruption Detection
    // =========================================================================

    @Test
    void shouldClaimIdempotentlyOnConflictDoNothingWithoutAbortingPostgresTransaction() {
        UUID sourceAccountId = UUID.randomUUID();
        String paymentRef = "REF-CLAIM-IDEMPOTENT-001";
        Payment firstCandidate = Payment.initiate(
                PaymentId.generate(),
                new PaymentInstruction(
                        new AccountId(sourceAccountId),
                        new PaymentReference(paymentRef),
                        new BeneficiaryDetails("Alice", "DE89370400440532013000", new BicCode("DEUTDEFFXXX")),
                        Money.of("250.0000", "USD")
                ),
                T0
        );

        int firstInserted = insertCandidateViaRepositoryClaim(firstCandidate);
        assertThat(firstInserted).isEqualTo(1);

        // Duplicate (source_account_id, payment_reference) inside the SAME active PostgreSQL transaction
        Payment duplicateCandidate = Payment.initiate(
                PaymentId.generate(),
                new PaymentInstruction(
                        new AccountId(sourceAccountId),
                        new PaymentReference(paymentRef),
                        new BeneficiaryDetails("Bob", "GB29NWBK60161331926819", new BicCode("NWBKGB2LXXX")),
                        Money.of("999.0000", "USD")
                ),
                T1
        );

        int secondInserted = insertCandidateViaRepositoryClaim(duplicateCandidate);
        assertThat(secondInserted).isEqualTo(0);

        // Prove the PostgreSQL transaction is still healthy (not 25P02 aborted) and returns the original winner
        Optional<PaymentEntity> winnerEntity = repository.findBySourceAccountIdAndPaymentReference(
                sourceAccountId,
                paymentRef
        );
        assertThat(winnerEntity).isPresent();
        Payment restoredWinner = mapper.toDomain(winnerEntity.get());
        assertThat(restoredWinner.getId()).isEqualTo(firstCandidate.getId());
        assertThat(restoredWinner.getInstructionFingerprint()).isEqualTo(firstCandidate.getInstructionFingerprint());
        assertThat(restoredWinner.getInstruction().amount()).isEqualTo(Money.of("250.0000", "USD"));
    }

    @Test
    void shouldEnforceOptimisticLockingOnConcurrentEntityModification() {
        TransactionTemplate requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        UUID paymentId = UUID.randomUUID();
        Payment initial = Payment.initiate(new PaymentId(paymentId), sampleInstruction("REF-OPTLOCK-001"), T0);

        requiresNewTx.executeWithoutResult(status -> repository.saveAndFlush(mapper.toEntity(initial)));

        try {
            // Load stale snapshot in outer transaction
            PaymentEntity staleEntity = repository.findById(paymentId).orElseThrow();
            Payment staleDomain = mapper.toDomain(staleEntity);

            // Concurrent transaction updates the row first (version 0 -> 1)
            requiresNewTx.executeWithoutResult(status -> {
                PaymentEntity concurrentEntity = repository.findById(paymentId).orElseThrow();
                Payment concurrentDomain = mapper.toDomain(concurrentEntity);
                concurrentDomain.confirmFundsReserved(new ReservationToken(UUID.randomUUID()), T1);
                mapper.updateEntity(concurrentDomain, concurrentEntity);
                repository.saveAndFlush(concurrentEntity);
            });

            // Stale snapshot attempts to update (version 0) -> must fail with ObjectOptimisticLockingFailureException
            staleDomain.cancel("Concurrent cancel on stale version", T2);
            mapper.updateEntity(staleDomain, staleEntity);

            assertThatThrownBy(() -> repository.saveAndFlush(staleEntity))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        } finally {
            requiresNewTx.executeWithoutResult(status ->
                    jdbcTemplate.update("DELETE FROM payment.payment WHERE payment_id = ?", paymentId)
            );
        }
    }

    @Test
    void shouldTranslateCorruptedPersistedRowToPersistedPaymentStateException() {
        Payment valid = Payment.initiate(PaymentId.generate(), sampleInstruction("REF-CORRUPT-FP"), T0);
        repository.saveAndFlush(mapper.toEntity(valid));

        // Tamper with instruction_fingerprint directly in PostgreSQL so it still matches hex(64) format
        // but mismatches the computed SHA-256 fingerprint of the persisted instruction columns
        jdbcTemplate.update(
                "UPDATE payment.payment SET instruction_fingerprint = ? WHERE payment_id = ?",
                VALID_DUMMY_FINGERPRINT,
                valid.getId().getValue()
        );
        entityManager.clear();

        PaymentEntity corruptedEntity = repository.findById(valid.getId().getValue()).orElseThrow();
        assertThatThrownBy(() -> mapper.toDomain(corruptedEntity))
                .isInstanceOf(PersistedPaymentStateException.class)
                .hasMessageContaining("instructionFingerprint");
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private void assertRoundTrip(Payment original) {
        repository.saveAndFlush(mapper.toEntity(original));
        entityManager.clear();

        PaymentEntity loaded = repository.findById(original.getId().getValue()).orElseThrow();
        Payment restored = mapper.toDomain(loaded);

        assertThat(restored.getId()).isEqualTo(original.getId());
        assertThat(restored.getInstruction()).isEqualTo(original.getInstruction());
        assertThat(restored.getInstructionFingerprint()).isEqualTo(original.getInstructionFingerprint());
        assertThat(restored.getStatus()).isEqualTo(original.getStatus());
        assertThat(restored.getReservationToken()).isEqualTo(original.getReservationToken());
        assertThat(restored.getRouteReference()).isEqualTo(original.getRouteReference());
        assertThat(restored.getExternalDispatchReference()).isEqualTo(original.getExternalDispatchReference());
        assertThat(restored.getLedgerTransactionId()).isEqualTo(original.getLedgerTransactionId());
        assertThat(restored.getStatusReasonCode()).isEqualTo(original.getStatusReasonCode());
        assertThat(restored.getStatusReasonDetail()).isEqualTo(original.getStatusReasonDetail());
        assertThat(restored.getCreatedAt().truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(original.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(restored.getUpdatedAt().truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(original.getUpdatedAt().truncatedTo(ChronoUnit.MICROS));
    }

    private PaymentInstruction sampleInstruction(String paymentRef) {
        return new PaymentInstruction(
                new AccountId(UUID.randomUUID()),
                new PaymentReference(paymentRef),
                new BeneficiaryDetails(
                        "Global Trade GmbH",
                        "DE89370400440532013000",
                        new BicCode("DEUTDEFFXXX")
                ),
                Money.of("125.5000", "USD")
        );
    }

    private int insertCandidateViaRepositoryClaim(Payment candidate) {
        PaymentInstruction instruction = candidate.getInstruction();
        BeneficiaryDetails beneficiary = instruction.beneficiary();
        return repository.insertClaimOnConflictDoNothing(
                candidate.getId().getValue(),
                instruction.sourceAccountId().getValue(),
                instruction.paymentReference().getValue(),
                candidate.getInstructionFingerprint().getValue(),
                instruction.amount().getAmount(),
                instruction.amount().getCurrency().getCode(),
                beneficiary.beneficiaryName(),
                beneficiary.beneficiaryAccountNumber(),
                beneficiary.beneficiaryBankBic().getValue(),
                candidate.getStatus().name(),
                candidate.getReservationToken().map(ReservationToken::getValue).orElse(null),
                candidate.getRouteReference().orElse(null),
                candidate.getExternalDispatchReference().orElse(null),
                candidate.getLedgerTransactionId().orElse(null),
                candidate.getStatusReasonCode().map(Enum::name).orElse(null),
                candidate.getStatusReasonDetail().orElse(null),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );
    }

    private void assertStateInvariantFailure(
            String status,
            UUID reservationToken,
            String routeReference,
            String externalDispatchReference,
            UUID ledgerTransactionId,
            String statusReasonCode,
            String statusReasonDetail) {
        assertSqlFailureInSavepoint(
                () -> insertRawPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REF-INV-" + UUID.randomUUID(),
                        VALID_DUMMY_FINGERPRINT,
                        new BigDecimal("100.0000"),
                        "USD",
                        "Beneficiary",
                        "DE89370400440532013000",
                        "DEUTDEFFXXX",
                        status,
                        reservationToken,
                        routeReference,
                        externalDispatchReference,
                        ledgerTransactionId,
                        statusReasonCode,
                        statusReasonDetail,
                        T0,
                        T1
                ),
                "23514",
                "chk_payment_state_invariants"
        );
    }

    private void insertRawPaymentWithAmount(BigDecimal amount) {
        insertRawPayment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "REF-AMT-" + UUID.randomUUID(),
                VALID_DUMMY_FINGERPRINT,
                amount,
                "USD",
                "Beneficiary",
                "DE89370400440532013000",
                "DEUTDEFFXXX",
                "VALIDATING",
                null,
                null,
                null,
                null,
                null,
                null,
                T0,
                T0
        );
    }

    private void insertRawPayment(
            UUID paymentId,
            UUID sourceAccountId,
            String paymentReference,
            String instructionFingerprint,
            BigDecimal amount,
            String currency,
            String beneficiaryName,
            String beneficiaryAccountNumber,
            String beneficiaryBankBic,
            String status,
            UUID reservationToken,
            String routeReference,
            String externalDispatchReference,
            UUID ledgerTransactionId,
            String statusReasonCode,
            String statusReasonDetail,
            Instant createdAt,
            Instant updatedAt) {
        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO payment.payment (
                        payment_id,
                        version,
                        source_account_id,
                        payment_reference,
                        instruction_fingerprint,
                        amount,
                        currency,
                        beneficiary_name,
                        beneficiary_account_number,
                        beneficiary_bank_bic,
                        status,
                        reservation_token,
                        route_reference,
                        external_dispatch_reference,
                        ledger_transaction_id,
                        status_reason_code,
                        status_reason_detail,
                        created_at,
                        updated_at
                    ) VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setObject(1, paymentId);
                ps.setObject(2, sourceAccountId);
                ps.setString(3, paymentReference);
                ps.setString(4, instructionFingerprint);
                ps.setBigDecimal(5, amount);
                ps.setString(6, currency);
                ps.setString(7, beneficiaryName);
                ps.setString(8, beneficiaryAccountNumber);
                ps.setString(9, beneficiaryBankBic);
                ps.setString(10, status);
                if (reservationToken != null) {
                    ps.setObject(11, reservationToken);
                } else {
                    ps.setNull(11, Types.OTHER);
                }
                ps.setString(12, routeReference);
                ps.setString(13, externalDispatchReference);
                if (ledgerTransactionId != null) {
                    ps.setObject(14, ledgerTransactionId);
                } else {
                    ps.setNull(14, Types.OTHER);
                }
                ps.setString(15, statusReasonCode);
                ps.setString(16, statusReasonDetail);
                ps.setTimestamp(17, Timestamp.from(createdAt));
                ps.setTimestamp(18, Timestamp.from(updatedAt));
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void assertSqlFailureInSavepoint(
            Runnable statement,
            String expectedSqlState,
            String expectedMessageFragment) {
        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
            Savepoint sp = con.setSavepoint();
            try {
                assertThatThrownBy(statement::run)
                        .isInstanceOf(DataAccessException.class)
                        .satisfies(ex -> {
                            SQLException sqlEx = extractSqlException(ex);
                            assertThat((Throwable) sqlEx).isNotNull();
                            assertThat(sqlEx.getSQLState()).isEqualTo(expectedSqlState);
                            assertThat(sqlEx.getMessage()).containsIgnoringCase(expectedMessageFragment);
                        });
            } finally {
                con.rollback(sp);
                con.releaseSavepoint(sp);
            }
            return null;
        });
    }

    private static SQLException extractSqlException(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof SQLException sqlException) {
                return sqlException;
            }
            cursor = cursor.getCause();
        }
        return null;
    }
}
