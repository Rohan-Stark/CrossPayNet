package com.crosspaynet.payment;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.CancelPaymentCommand;
import com.crosspaynet.payment.contracts.CommitPaymentExecutionCommand;
import com.crosspaynet.payment.contracts.FailPaymentCommand;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.InvalidPaymentStateException;
import com.crosspaynet.payment.contracts.MarkPaymentDispatchedCommand;
import com.crosspaynet.payment.contracts.PaymentException;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentInstruction;
import com.crosspaynet.payment.contracts.PaymentNotFoundException;
import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentReferenceConflictException;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentValidationException;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.payment.contracts.RejectPaymentCommand;
import com.crosspaynet.payment.settlement.RecordPaymentSettlementCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaymentValueObjectsAndContractsTest {

    @Test
    void testPaymentIdCreationAndValidation() {
        UUID raw = UUID.randomUUID();
        PaymentId id1 = PaymentId.of(raw);
        PaymentId id2 = PaymentId.of("  " + raw + "  ");

        assertEquals(raw, id1.getValue());
        assertEquals(id1, id2);
        assertEquals(id1.hashCode(), id2.hashCode());
        assertEquals(raw.toString(), id1.toString());
        assertNotNull(PaymentId.generate().getValue());

        assertThrows(PaymentValidationException.class, () -> new PaymentId(null));
        assertThrows(PaymentValidationException.class, () -> PaymentId.of((String) null));
        assertThrows(PaymentValidationException.class, () -> PaymentId.of("   "));
        assertThrows(PaymentValidationException.class, () -> PaymentId.of("not-a-uuid"));
    }

    @Test
    void testBicCodeValidation() {
        BicCode bic8 = BicCode.of("  DEUTDEFF  ");
        BicCode bic11 = BicCode.of("CHASUS33XXX");

        assertEquals("DEUTDEFF", bic8.getValue());
        assertEquals("CHASUS33XXX", bic11.getValue());
        assertEquals(BicCode.of("DEUTDEFF"), bic8);
        assertEquals(BicCode.of("DEUTDEFF").hashCode(), bic8.hashCode());

        assertThrows(PaymentValidationException.class, () -> BicCode.of(null));
        assertThrows(PaymentValidationException.class, () -> BicCode.of("   "));
        assertThrows(PaymentValidationException.class, () -> BicCode.of("deutdeff")); // lowercase rejected
        assertThrows(PaymentValidationException.class, () -> BicCode.of("DEUTDEF")); // 7 chars
        assertThrows(PaymentValidationException.class, () -> BicCode.of("DEUTDEFFXX")); // 10 chars
        assertThrows(PaymentValidationException.class, () -> BicCode.of("DEUT12FF")); // digits in country code
        assertThrows(PaymentValidationException.class, () -> BicCode.of("1234US33")); // digits in bank code
    }

    @Test
    void testBeneficiaryDetailsValidationAndNormalization() {
        BeneficiaryDetails details = new BeneficiaryDetails(
                "  Acme Global Ltd  ",
                "  DE89370400440532013000  ",
                BicCode.of("DEUTDEFFXXX")
        );

        assertEquals("Acme Global Ltd", details.beneficiaryName());
        assertEquals("DE89370400440532013000", details.beneficiaryAccountNumber());
        assertEquals(BicCode.of("DEUTDEFFXXX"), details.beneficiaryBankBic());

        assertThrows(PaymentValidationException.class, () ->
                new BeneficiaryDetails(null, "ACC123", BicCode.of("DEUTDEFF")));
        assertThrows(PaymentValidationException.class, () ->
                new BeneficiaryDetails("   ", "ACC123", BicCode.of("DEUTDEFF")));
        assertThrows(PaymentValidationException.class, () ->
                new BeneficiaryDetails("A".repeat(141), "ACC123", BicCode.of("DEUTDEFF")));
        assertThrows(PaymentValidationException.class, () ->
                new BeneficiaryDetails("Bad\nName", "ACC123", BicCode.of("DEUTDEFF")));

        assertThrows(PaymentValidationException.class, () ->
                new BeneficiaryDetails("Acme", null, BicCode.of("DEUTDEFF")));
        assertThrows(PaymentValidationException.class, () ->
                new BeneficiaryDetails("Acme", "1".repeat(35), BicCode.of("DEUTDEFF")));
        assertThrows(PaymentValidationException.class, () ->
                new BeneficiaryDetails("Acme", "ACC\u0007123", BicCode.of("DEUTDEFF")));
        assertThrows(PaymentValidationException.class, () ->
                new BeneficiaryDetails("Acme", "ACC123", null));
    }

    @Test
    void testPaymentInstructionValidation() {
        AccountId accountId = AccountId.generate();
        PaymentReference reference = PaymentReference.of("PAY-REF-001");
        BeneficiaryDetails beneficiary = new BeneficiaryDetails(
                "Beneficiary Corp",
                "GB29NWBK60161331926819",
                BicCode.of("NWBKGB2L")
        );
        Money amount = new Money(new BigDecimal("1250.5000"), new Currency("GBP"));

        PaymentInstruction instruction = new PaymentInstruction(accountId, reference, beneficiary, amount);
        assertEquals(accountId, instruction.sourceAccountId());
        assertEquals(reference, instruction.paymentReference());
        assertEquals(beneficiary, instruction.beneficiary());
        assertEquals(amount, instruction.amount());

        // Null checks
        assertThrows(PaymentValidationException.class, () ->
                new PaymentInstruction(null, reference, beneficiary, amount));
        assertThrows(PaymentValidationException.class, () ->
                new PaymentInstruction(accountId, null, beneficiary, amount));
        assertThrows(PaymentValidationException.class, () ->
                new PaymentInstruction(accountId, reference, null, amount));
        assertThrows(PaymentValidationException.class, () ->
                new PaymentInstruction(accountId, reference, beneficiary, null));

        // Control chars in paymentReference
        assertThrows(PaymentValidationException.class, () ->
                new PaymentInstruction(accountId, PaymentReference.of("PAY\n001"), beneficiary, amount));

        // Unsupported currency
        Money cadAmount = new Money(new BigDecimal("100.00"), new Currency("CAD"));
        assertThrows(PaymentValidationException.class, () ->
                new PaymentInstruction(accountId, reference, beneficiary, cadAmount));

        // Zero or negative amount
        Money zeroAmount = Money.zero(new Currency("USD"));
        assertThrows(PaymentValidationException.class, () ->
                new PaymentInstruction(accountId, reference, beneficiary, zeroAmount));
        Money negativeAmount = new Money(new BigDecimal("-0.01"), new Currency("USD"));
        assertThrows(PaymentValidationException.class, () ->
                new PaymentInstruction(accountId, reference, beneficiary, negativeAmount));

        // Scale > 4 rejected at Money construction (no silent rounding), while trailing zeros are accepted
        assertThrows(IllegalArgumentException.class, () ->
                new Money(new BigDecimal("10.12345"), new Currency("USD")));
        Money trailingZerosAmount = new Money(new BigDecimal("10.123400"), new Currency("USD"));
        assertDoesNotThrow(() ->
                new PaymentInstruction(accountId, reference, beneficiary, trailingZerosAmount));

        // Exceeds MAX_AMOUNT rejected
        Money tooLargeAmount = new Money(new BigDecimal("1000000000000000.0000"), new Currency("USD"));
        assertThrows(PaymentValidationException.class, () ->
                new PaymentInstruction(accountId, reference, beneficiary, tooLargeAmount));

        // Exact MAX_AMOUNT accepted
        Money maxAmount = new Money(new BigDecimal("999999999999999.9999"), new Currency("USD"));
        assertDoesNotThrow(() ->
                new PaymentInstruction(accountId, reference, beneficiary, maxAmount));
    }

    @Test
    void testCommandsAndViewValidation() {
        AccountId accountId = AccountId.generate();
        PaymentReference reference = PaymentReference.of("PAY-CMD-001");
        BeneficiaryDetails beneficiary = new BeneficiaryDetails("Alice", "DE123456", BicCode.of("DEUTDEFF"));
        Money amount = new Money(new BigDecimal("50.00"), new Currency("EUR"));
        PaymentId paymentId = PaymentId.generate();

        InitiatePaymentCommand initiateCmd = new InitiatePaymentCommand(accountId, reference, beneficiary, amount);
        assertEquals(accountId, initiateCmd.toInstruction().sourceAccountId());

        CancelPaymentCommand cancelCmd = new CancelPaymentCommand(paymentId, "  Customer requested cancellation  ");
        assertEquals("Customer requested cancellation", cancelCmd.reasonDetail());
        assertNull(new CancelPaymentCommand(paymentId, "   ").reasonDetail());
        assertThrows(PaymentValidationException.class, () -> new CancelPaymentCommand(null, "reason"));
        assertThrows(PaymentValidationException.class, () -> new CancelPaymentCommand(paymentId, "x".repeat(501)));

        RejectPaymentCommand rejectCmd = new RejectPaymentCommand(
                paymentId,
                PaymentReasonCode.COMPLIANCE_REJECTED,
                "  Sanctions match  "
        );
        assertEquals("Sanctions match", rejectCmd.reasonDetail());
        assertThrows(PaymentValidationException.class, () ->
                new RejectPaymentCommand(paymentId, PaymentReasonCode.INSUFFICIENT_FUNDS, "wrong category"));

        CommitPaymentExecutionCommand commitCmd = new CommitPaymentExecutionCommand(paymentId, "  ROUTE-EUR-01  ");
        assertEquals("ROUTE-EUR-01", commitCmd.routeReference());
        assertThrows(PaymentValidationException.class, () ->
                new CommitPaymentExecutionCommand(paymentId, "   "));

        MarkPaymentDispatchedCommand dispatchCmd = new MarkPaymentDispatchedCommand(paymentId, "  MSG-2026-001  ");
        assertEquals("MSG-2026-001", dispatchCmd.externalDispatchReference());
        assertThrows(PaymentValidationException.class, () ->
                new MarkPaymentDispatchedCommand(paymentId, "   "));

        FailPaymentCommand failCmd = new FailPaymentCommand(
                paymentId,
                PaymentReasonCode.EXTERNAL_NETWORK_REJECTED,
                "  Terminal NACK  "
        );
        assertEquals("Terminal NACK", failCmd.reasonDetail());
        assertThrows(PaymentValidationException.class, () ->
                new FailPaymentCommand(paymentId, PaymentReasonCode.COMPLIANCE_REJECTED, "wrong category"));

        UUID ledgerTxId = UUID.randomUUID();
        RecordPaymentSettlementCommand settleCmd = new RecordPaymentSettlementCommand(paymentId, ledgerTxId);
        assertEquals(paymentId, settleCmd.paymentId());
        assertEquals(ledgerTxId, settleCmd.ledgerTransactionId());
        assertThrows(PaymentValidationException.class, () -> new RecordPaymentSettlementCommand(null, ledgerTxId));
        assertThrows(PaymentValidationException.class, () -> new RecordPaymentSettlementCommand(paymentId, null));

        Instant t1 = Instant.parse("2026-10-25T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-25T10:01:00Z");
        PaymentView view = new PaymentView(
                paymentId,
                initiateCmd.toInstruction(),
                PaymentStatus.COMPLIANCE_HOLD,
                ReservationToken.generate(),
                null,
                null,
                null,
                null,
                null,
                t1,
                t2
        );
        assertEquals(PaymentStatus.COMPLIANCE_HOLD, view.status());
        assertThrows(PaymentValidationException.class, () -> new PaymentView(
                paymentId,
                initiateCmd.toInstruction(),
                PaymentStatus.COMPLIANCE_HOLD,
                ReservationToken.generate(),
                null,
                null,
                null,
                null,
                null,
                t2,
                t1
        ));
    }

    @Test
    void testStatusAndReasonCodeClassificationAndExceptionHierarchy() {
        assertTrue(PaymentStatus.VALIDATING.isPreCommitment());
        assertTrue(PaymentStatus.COMPLIANCE_HOLD.isPreCommitment());
        assertTrue(PaymentStatus.ROUTING.isPreCommitment());
        assertFalse(PaymentStatus.EXECUTING.isPreCommitment());

        assertTrue(PaymentStatus.EXECUTING.isPostCommitmentActive());
        assertTrue(PaymentStatus.PENDING_EXTERNAL.isPostCommitmentActive());
        assertFalse(PaymentStatus.ROUTING.isPostCommitmentActive());
        assertFalse(PaymentStatus.SETTLED.isPostCommitmentActive());

        assertTrue(PaymentStatus.SETTLED.isTerminal());
        assertTrue(PaymentStatus.REJECTED.isTerminal());
        assertTrue(PaymentStatus.CANCELLED.isTerminal());
        assertTrue(PaymentStatus.FAILED.isTerminal());
        assertFalse(PaymentStatus.PENDING_EXTERNAL.isTerminal());

        assertTrue(PaymentReasonCode.INSUFFICIENT_FUNDS.isReservationFailureReason());
        assertTrue(PaymentReasonCode.INVALID_ACCOUNT_STATE.isReservationFailureReason());
        assertTrue(PaymentReasonCode.CURRENCY_MISMATCH.isReservationFailureReason());
        assertFalse(PaymentReasonCode.COMPLIANCE_REJECTED.isReservationFailureReason());

        assertTrue(PaymentReasonCode.COMPLIANCE_REJECTED.isPreCommitmentRejectionReason());
        assertTrue(PaymentReasonCode.NO_ROUTE_AVAILABLE.isPreCommitmentRejectionReason());
        assertFalse(PaymentReasonCode.CUSTOMER_CANCELLED.isPreCommitmentRejectionReason());

        assertTrue(PaymentReasonCode.EXTERNAL_NETWORK_REJECTED.isPostCommitmentFailureReason());
        assertTrue(PaymentReasonCode.OPERATIONAL_FAILURE.isPostCommitmentFailureReason());

        PaymentId id = PaymentId.generate();
        PaymentNotFoundException notFound = new PaymentNotFoundException(id);
        InvalidPaymentStateException invalidState = new InvalidPaymentStateException("bad state");
        PaymentReferenceConflictException conflict = new PaymentReferenceConflictException("conflict");

        assertInstanceOf(PaymentValidationException.class, notFound);
        assertInstanceOf(PaymentException.class, invalidState);
        assertInstanceOf(PaymentValidationException.class, conflict);
    }
}
