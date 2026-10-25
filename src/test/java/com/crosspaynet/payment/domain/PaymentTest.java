package com.crosspaynet.payment.domain;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.InvalidPaymentStateException;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentInstruction;
import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentValidationException;
import com.crosspaynet.payment.contracts.PaymentView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaymentTest {

    private static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-25T10:01:00Z");
    private static final Instant T2 = Instant.parse("2026-10-25T10:02:00Z");
    private static final Instant T3 = Instant.parse("2026-10-25T10:03:00Z");
    private static final Instant T4 = Instant.parse("2026-10-25T10:04:00Z");
    private static final Instant T5 = Instant.parse("2026-10-25T10:05:00Z");

    private static PaymentInstruction sampleInstruction() {
        return new PaymentInstruction(
                AccountId.generate(),
                PaymentReference.of("PAY-2026-0001"),
                new BeneficiaryDetails("Global Beneficiary AG", "CH9300762011623852957", BicCode.of("UBSWCHZH80A")),
                new Money(new BigDecimal("1500.2500"), new Currency("CHF"))
        );
    }

    @Test
    void testFullHappyPathToSettledAndIdempotentTransitions() {
        PaymentId paymentId = PaymentId.generate();
        PaymentInstruction instruction = sampleInstruction();
        ReservationToken token = ReservationToken.generate();
        UUID ledgerTxId = UUID.randomUUID();

        Payment payment = Payment.initiate(paymentId, instruction, T0);
        assertEquals(PaymentStatus.VALIDATING, payment.getStatus());
        assertTrue(payment.getReservationToken().isEmpty());
        assertEquals(T0, payment.getCreatedAt());
        assertEquals(T0, payment.getUpdatedAt());

        // 1. VALIDATING -> COMPLIANCE_HOLD
        payment.confirmFundsReserved(token, T1);
        assertEquals(PaymentStatus.COMPLIANCE_HOLD, payment.getStatus());
        assertEquals(token, payment.getReservationToken().orElseThrow());
        assertEquals(T1, payment.getUpdatedAt());

        // Idempotent confirmFundsReserved with same token
        payment.confirmFundsReserved(token, T1);
        assertEquals(PaymentStatus.COMPLIANCE_HOLD, payment.getStatus());
        assertThrows(InvalidPaymentStateException.class, () ->
                payment.confirmFundsReserved(ReservationToken.generate(), T1));

        // 2. COMPLIANCE_HOLD -> ROUTING
        payment.approveCompliance(T2);
        assertEquals(PaymentStatus.ROUTING, payment.getStatus());
        assertEquals(T2, payment.getUpdatedAt());

        // Idempotent approveCompliance
        payment.approveCompliance(T2);
        assertEquals(PaymentStatus.ROUTING, payment.getStatus());

        // 3. ROUTING -> EXECUTING (Financial Commitment Point)
        payment.commitToExecution("  ROUTE-CH-CORR-01  ", T3);
        assertEquals(PaymentStatus.EXECUTING, payment.getStatus());
        assertEquals("ROUTE-CH-CORR-01", payment.getRouteReference().orElseThrow());
        assertEquals(T3, payment.getUpdatedAt());

        // Idempotent commitToExecution with same routeReference
        payment.commitToExecution("ROUTE-CH-CORR-01", T3);
        assertThrows(InvalidPaymentStateException.class, () ->
                payment.commitToExecution("DIFFERENT-ROUTE", T3));

        // EXECUTING -> SETTLED must be rejected! Only PENDING_EXTERNAL -> SETTLED is permitted.
        assertThrows(InvalidPaymentStateException.class, () ->
                payment.recordSettlement(ledgerTxId, T4));

        // 4. EXECUTING -> PENDING_EXTERNAL
        payment.markDispatched("  PACS008-MSG-999  ", T4);
        assertEquals(PaymentStatus.PENDING_EXTERNAL, payment.getStatus());
        assertEquals("PACS008-MSG-999", payment.getExternalDispatchReference().orElseThrow());
        assertEquals(T4, payment.getUpdatedAt());

        // Idempotent markDispatched with same externalDispatchReference
        payment.markDispatched("PACS008-MSG-999", T4);
        assertThrows(InvalidPaymentStateException.class, () ->
                payment.markDispatched("OTHER-MSG", T4));

        // 5. PENDING_EXTERNAL -> SETTLED
        payment.recordSettlement(ledgerTxId, T5);
        assertEquals(PaymentStatus.SETTLED, payment.getStatus());
        assertEquals(ledgerTxId, payment.getLedgerTransactionId().orElseThrow());
        assertEquals(T5, payment.getUpdatedAt());

        // Idempotent recordSettlement with same ledgerTransactionId
        payment.recordSettlement(ledgerTxId, T5);
        assertThrows(InvalidPaymentStateException.class, () ->
                payment.recordSettlement(UUID.randomUUID(), T5));

        PaymentView view = payment.toView();
        assertEquals(paymentId, view.paymentId());
        assertEquals(PaymentStatus.SETTLED, view.status());
        assertEquals(token, view.reservationToken());
        assertEquals("ROUTE-CH-CORR-01", view.routeReference());
        assertEquals("PACS008-MSG-999", view.externalDispatchReference());
        assertEquals(ledgerTxId, view.ledgerTransactionId());
        assertNull(view.statusReasonCode());
        assertNull(view.statusReasonDetail());
    }

    @Test
    void testRejectOnReservationFailureFromValidatingOnly() {
        Payment payment = Payment.initiate(PaymentId.generate(), sampleInstruction(), T0);

        // Non-reservation reason codes rejected
        assertThrows(PaymentValidationException.class, () ->
                payment.rejectOnReservationFailure(PaymentReasonCode.COMPLIANCE_REJECTED, "wrong", T1));

        payment.rejectOnReservationFailure(PaymentReasonCode.INSUFFICIENT_FUNDS, "  Not enough balance  ", T1);
        assertEquals(PaymentStatus.REJECTED, payment.getStatus());
        assertTrue(payment.getReservationToken().isEmpty());
        assertEquals(PaymentReasonCode.INSUFFICIENT_FUNDS, payment.getStatusReasonCode().orElseThrow());
        assertEquals("Not enough balance", payment.getStatusReasonDetail().orElseThrow());

        // Idempotent with same reason code
        payment.rejectOnReservationFailure(PaymentReasonCode.INSUFFICIENT_FUNDS, "Not enough balance", T1);

        // Different reason code or subsequent transition rejected
        assertThrows(InvalidPaymentStateException.class, () ->
                payment.rejectOnReservationFailure(PaymentReasonCode.CURRENCY_MISMATCH, "mismatch", T2));
        assertThrows(InvalidPaymentStateException.class, () ->
                payment.cancel("try cancel", T2));
    }

    @Test
    void testPreCommitmentCancellationAndPostCommitmentCancellationProhibition() {
        // 1. Cancel from COMPLIANCE_HOLD
        Payment p1 = Payment.initiate(PaymentId.generate(), sampleInstruction(), T0);
        ReservationToken token1 = ReservationToken.generate();
        p1.confirmFundsReserved(token1, T1);
        p1.cancel("  Customer changed mind  ", T2);

        assertEquals(PaymentStatus.CANCELLED, p1.getStatus());
        assertEquals(token1, p1.getReservationToken().orElseThrow()); // preserved on row for audit
        assertEquals(PaymentReasonCode.CUSTOMER_CANCELLED, p1.getStatusReasonCode().orElseThrow());
        assertEquals("Customer changed mind", p1.getStatusReasonDetail().orElseThrow());

        // Idempotent repeat of cancel
        p1.cancel("Customer changed mind", T3);
        assertEquals(PaymentStatus.CANCELLED, p1.getStatus());

        // 2. Cancel from ROUTING
        Payment p2 = Payment.initiate(PaymentId.generate(), sampleInstruction(), T0);
        p2.confirmFundsReserved(ReservationToken.generate(), T1);
        p2.approveCompliance(T2);
        p2.cancel(null, T3);
        assertEquals(PaymentStatus.CANCELLED, p2.getStatus());
        assertEquals(PaymentReasonCode.CUSTOMER_CANCELLED, p2.getStatusReasonCode().orElseThrow());
        assertTrue(p2.getStatusReasonDetail().isEmpty());

        // 3. Cancel strictly forbidden at or after EXECUTING (Financial Commitment Point)
        Payment p3 = Payment.initiate(PaymentId.generate(), sampleInstruction(), T0);
        p3.confirmFundsReserved(ReservationToken.generate(), T1);
        p3.approveCompliance(T2);
        p3.commitToExecution("ROUTE-01", T3);

        assertThrows(InvalidPaymentStateException.class, () -> p3.cancel("too late", T4));

        p3.markDispatched("MSG-01", T4);
        assertThrows(InvalidPaymentStateException.class, () -> p3.cancel("still too late", T5));

        p3.recordSettlement(UUID.randomUUID(), T5);
        assertThrows(InvalidPaymentStateException.class, () -> p3.cancel("already settled", T5));
    }

    @Test
    void testRejectBeforeCommitmentFromComplianceHoldAndRoutingOnly() {
        Payment p1 = Payment.initiate(PaymentId.generate(), sampleInstruction(), T0);

        // Cannot call rejectBeforeCommitment in VALIDATING (must have reservation first)
        assertThrows(InvalidPaymentStateException.class, () ->
                p1.rejectBeforeCommitment(PaymentReasonCode.COMPLIANCE_REJECTED, "sanctions", T1));

        ReservationToken token = ReservationToken.generate();
        p1.confirmFundsReserved(token, T1);
        p1.rejectBeforeCommitment(PaymentReasonCode.COMPLIANCE_REJECTED, "  Sanctions hit  ", T2);

        assertEquals(PaymentStatus.REJECTED, p1.getStatus());
        assertEquals(token, p1.getReservationToken().orElseThrow());
        assertEquals(PaymentReasonCode.COMPLIANCE_REJECTED, p1.getStatusReasonCode().orElseThrow());
        assertEquals("Sanctions hit", p1.getStatusReasonDetail().orElseThrow());

        // Idempotent with same reason code
        p1.rejectBeforeCommitment(PaymentReasonCode.COMPLIANCE_REJECTED, "Sanctions hit", T2);
        assertThrows(InvalidPaymentStateException.class, () ->
                p1.rejectBeforeCommitment(PaymentReasonCode.NO_ROUTE_AVAILABLE, "different reason", T3));

        // Reject from ROUTING with NO_ROUTE_AVAILABLE
        Payment p2 = Payment.initiate(PaymentId.generate(), sampleInstruction(), T0);
        p2.confirmFundsReserved(ReservationToken.generate(), T1);
        p2.approveCompliance(T2);
        p2.rejectBeforeCommitment(PaymentReasonCode.NO_ROUTE_AVAILABLE, "No correspondent for BIC", T3);
        assertEquals(PaymentStatus.REJECTED, p2.getStatus());
        assertEquals(PaymentReasonCode.NO_ROUTE_AVAILABLE, p2.getStatusReasonCode().orElseThrow());

        // Forbidden at or after EXECUTING
        Payment p3 = Payment.initiate(PaymentId.generate(), sampleInstruction(), T0);
        p3.confirmFundsReserved(ReservationToken.generate(), T1);
        p3.approveCompliance(T2);
        p3.commitToExecution("ROUTE-01", T3);
        assertThrows(InvalidPaymentStateException.class, () ->
                p3.rejectBeforeCommitment(PaymentReasonCode.NO_ROUTE_AVAILABLE, "too late", T4));
    }

    @Test
    void testConfirmedPostCommitmentFailureFromExecutingAndPendingExternalOnly() {
        Payment p1 = Payment.initiate(PaymentId.generate(), sampleInstruction(), T0);
        p1.confirmFundsReserved(ReservationToken.generate(), T1);
        p1.approveCompliance(T2);

        // Forbidden before EXECUTING
        assertThrows(InvalidPaymentStateException.class, () ->
                p1.failBeforeSettlement(PaymentReasonCode.OPERATIONAL_FAILURE, "not committed yet", T3));

        // Fail from EXECUTING
        p1.commitToExecution("ROUTE-01", T3);
        p1.failBeforeSettlement(PaymentReasonCode.OPERATIONAL_FAILURE, "  Dispatch gateway unrecoverable  ", T4);
        assertEquals(PaymentStatus.FAILED, p1.getStatus());
        assertEquals(PaymentReasonCode.OPERATIONAL_FAILURE, p1.getStatusReasonCode().orElseThrow());
        assertEquals("Dispatch gateway unrecoverable", p1.getStatusReasonDetail().orElseThrow());

        // Idempotent with same reason code
        p1.failBeforeSettlement(PaymentReasonCode.OPERATIONAL_FAILURE, "Dispatch gateway unrecoverable", T4);
        assertThrows(InvalidPaymentStateException.class, () ->
                p1.failBeforeSettlement(PaymentReasonCode.EXTERNAL_NETWORK_REJECTED, "different", T5));

        // Fail from PENDING_EXTERNAL
        Payment p2 = Payment.initiate(PaymentId.generate(), sampleInstruction(), T0);
        p2.confirmFundsReserved(ReservationToken.generate(), T1);
        p2.approveCompliance(T2);
        p2.commitToExecution("ROUTE-02", T3);
        p2.markDispatched("MSG-02", T4);
        p2.failBeforeSettlement(PaymentReasonCode.EXTERNAL_NETWORK_REJECTED, "Correspondent NACK AC04", T5);
        assertEquals(PaymentStatus.FAILED, p2.getStatus());
        assertEquals("MSG-02", p2.getExternalDispatchReference().orElseThrow());
    }

    @Test
    void testRestoreValidatesAllStateInvariantsAndFingerprintIntegrity() {
        PaymentId id = PaymentId.generate();
        PaymentInstruction instruction = sampleInstruction();
        InstructionFingerprint fp = InstructionFingerprint.compute(instruction);
        ReservationToken token = ReservationToken.generate();
        UUID ledgerTxId = UUID.randomUUID();

        // Valid restores for each state
        assertDoesNotThrow(() -> Payment.restore(
                id, instruction, fp, PaymentStatus.VALIDATING,
                null, null, null, null, null, null, T0, T0));
        assertDoesNotThrow(() -> Payment.restore(
                id, instruction, fp, PaymentStatus.COMPLIANCE_HOLD,
                token, null, null, null, null, null, T0, T1));
        assertDoesNotThrow(() -> Payment.restore(
                id, instruction, fp, PaymentStatus.ROUTING,
                token, null, null, null, null, null, T0, T2));
        assertDoesNotThrow(() -> Payment.restore(
                id, instruction, fp, PaymentStatus.EXECUTING,
                token, "ROUTE-1", null, null, null, null, T0, T3));
        assertDoesNotThrow(() -> Payment.restore(
                id, instruction, fp, PaymentStatus.PENDING_EXTERNAL,
                token, "ROUTE-1", "MSG-1", null, null, null, T0, T4));
        assertDoesNotThrow(() -> Payment.restore(
                id, instruction, fp, PaymentStatus.SETTLED,
                token, "ROUTE-1", "MSG-1", ledgerTxId, null, null, T0, T5));
        assertDoesNotThrow(() -> Payment.restore(
                id, instruction, fp, PaymentStatus.REJECTED,
                null, null, null, null, PaymentReasonCode.INSUFFICIENT_FUNDS, "low funds", T0, T1));
        assertDoesNotThrow(() -> Payment.restore(
                id, instruction, fp, PaymentStatus.REJECTED,
                token, null, null, null, PaymentReasonCode.COMPLIANCE_REJECTED, "sanctions", T0, T2));
        assertDoesNotThrow(() -> Payment.restore(
                id, instruction, fp, PaymentStatus.CANCELLED,
                token, null, null, null, PaymentReasonCode.CUSTOMER_CANCELLED, null, T0, T2));
        assertDoesNotThrow(() -> Payment.restore(
                id, instruction, fp, PaymentStatus.FAILED,
                token, "ROUTE-1", "MSG-1", null, PaymentReasonCode.EXTERNAL_NETWORK_REJECTED, "NACK", T0, T5));

        // Corrupted fingerprint rejected
        InstructionFingerprint tamperedFp = InstructionFingerprint.ofHex("0".repeat(64));
        assertThrows(PersistedPaymentStateException.class, () -> Payment.restore(
                id, instruction, tamperedFp, PaymentStatus.COMPLIANCE_HOLD,
                token, null, null, null, null, null, T0, T1));

        // Corrupted state/field combinations rejected
        // 1. COMPLIANCE_HOLD without reservationToken
        assertThrows(PersistedPaymentStateException.class, () -> Payment.restore(
                id, instruction, fp, PaymentStatus.COMPLIANCE_HOLD,
                null, null, null, null, null, null, T0, T1));

        // 2. EXECUTING without routeReference
        assertThrows(PersistedPaymentStateException.class, () -> Payment.restore(
                id, instruction, fp, PaymentStatus.EXECUTING,
                token, null, null, null, null, null, T0, T3));

        // 3. SETTLED without externalDispatchReference or ledgerTransactionId
        assertThrows(PersistedPaymentStateException.class, () -> Payment.restore(
                id, instruction, fp, PaymentStatus.SETTLED,
                token, "ROUTE-1", null, ledgerTxId, null, null, T0, T5));
        assertThrows(PersistedPaymentStateException.class, () -> Payment.restore(
                id, instruction, fp, PaymentStatus.SETTLED,
                token, "ROUTE-1", "MSG-1", null, null, null, T0, T5));

        // 4. REJECTED with mismatched reservationToken and reasonCode
        assertThrows(PersistedPaymentStateException.class, () -> Payment.restore(
                id, instruction, fp, PaymentStatus.REJECTED,
                null, null, null, null, PaymentReasonCode.COMPLIANCE_REJECTED, null, T0, T1));
        assertThrows(PersistedPaymentStateException.class, () -> Payment.restore(
                id, instruction, fp, PaymentStatus.REJECTED,
                token, null, null, null, PaymentReasonCode.INSUFFICIENT_FUNDS, null, T0, T1));

        // 5. CANCELLED with non-CUSTOMER_CANCELLED reason or with routeReference
        assertThrows(PersistedPaymentStateException.class, () -> Payment.restore(
                id, instruction, fp, PaymentStatus.CANCELLED,
                token, null, null, null, PaymentReasonCode.COMPLIANCE_REJECTED, null, T0, T2));
        assertThrows(PersistedPaymentStateException.class, () -> Payment.restore(
                id, instruction, fp, PaymentStatus.CANCELLED,
                token, "ROUTE-1", null, null, PaymentReasonCode.CUSTOMER_CANCELLED, null, T0, T2));

        // 6. FAILED without routeReference or with ledgerTransactionId
        assertThrows(PersistedPaymentStateException.class, () -> Payment.restore(
                id, instruction, fp, PaymentStatus.FAILED,
                token, null, null, null, PaymentReasonCode.OPERATIONAL_FAILURE, null, T0, T4));
        assertThrows(PersistedPaymentStateException.class, () -> Payment.restore(
                id, instruction, fp, PaymentStatus.FAILED,
                token, "ROUTE-1", "MSG-1", ledgerTxId, PaymentReasonCode.OPERATIONAL_FAILURE, null, T0, T4));
    }
}
