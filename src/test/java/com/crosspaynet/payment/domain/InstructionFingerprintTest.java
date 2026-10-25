package com.crosspaynet.payment.domain;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentInstruction;
import com.crosspaynet.payment.contracts.PaymentValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InstructionFingerprintTest {

    private static final AccountId ACCOUNT_1 = AccountId.of("11111111-1111-1111-1111-111111111111");
    private static final AccountId ACCOUNT_2 = AccountId.of("22222222-2222-2222-2222-222222222222");

    @Test
    void testDeterministicFingerprintAndScaleNormalization() {
        PaymentInstruction instructionScale1 = new PaymentInstruction(
                ACCOUNT_1,
                PaymentReference.of("PAY-FP-001"),
                new BeneficiaryDetails("Alice Smith", "DE89370400440532013000", BicCode.of("DEUTDEFF")),
                new Money(new BigDecimal("250.5"), new Currency("EUR"))
        );
        PaymentInstruction instructionScale4 = new PaymentInstruction(
                ACCOUNT_1,
                PaymentReference.of("  PAY-FP-001  "),
                new BeneficiaryDetails("  Alice Smith  ", "  DE89370400440532013000  ", BicCode.of("  DEUTDEFF  ")),
                new Money(new BigDecimal("250.5000"), new Currency("EUR"))
        );

        InstructionFingerprint fp1 = InstructionFingerprint.compute(instructionScale1);
        InstructionFingerprint fp2 = InstructionFingerprint.compute(instructionScale4);

        assertEquals(64, fp1.getValue().length());
        assertEquals(fp1, fp2);
        assertEquals(fp1.hashCode(), fp2.hashCode());
        assertEquals(fp1, InstructionFingerprint.ofHex(fp1.getValue()));

        String canonical = InstructionFingerprint.toCanonicalString(instructionScale1);
        assertTrue(canonical.startsWith("crosspaynet-payment-fingerprint-v1\n"));
        assertTrue(canonical.contains("8:250.5000\n"));
    }

    @Test
    void testFingerprintChangesWhenAnyFieldChanges() {
        PaymentInstruction baseline = new PaymentInstruction(
                ACCOUNT_1,
                PaymentReference.of("PAY-FP-001"),
                new BeneficiaryDetails("Alice Smith", "DE89370400440532013000", BicCode.of("DEUTDEFF")),
                new Money(new BigDecimal("100.00"), new Currency("USD"))
        );
        InstructionFingerprint baseFp = InstructionFingerprint.compute(baseline);

        // Different sourceAccountId
        assertNotEquals(baseFp, InstructionFingerprint.compute(new PaymentInstruction(
                ACCOUNT_2,
                PaymentReference.of("PAY-FP-001"),
                new BeneficiaryDetails("Alice Smith", "DE89370400440532013000", BicCode.of("DEUTDEFF")),
                new Money(new BigDecimal("100.00"), new Currency("USD"))
        )));

        // Different paymentReference
        assertNotEquals(baseFp, InstructionFingerprint.compute(new PaymentInstruction(
                ACCOUNT_1,
                PaymentReference.of("PAY-FP-002"),
                new BeneficiaryDetails("Alice Smith", "DE89370400440532013000", BicCode.of("DEUTDEFF")),
                new Money(new BigDecimal("100.00"), new Currency("USD"))
        )));

        // Different currency
        assertNotEquals(baseFp, InstructionFingerprint.compute(new PaymentInstruction(
                ACCOUNT_1,
                PaymentReference.of("PAY-FP-001"),
                new BeneficiaryDetails("Alice Smith", "DE89370400440532013000", BicCode.of("DEUTDEFF")),
                new Money(new BigDecimal("100.00"), new Currency("EUR"))
        )));

        // Different amount
        assertNotEquals(baseFp, InstructionFingerprint.compute(new PaymentInstruction(
                ACCOUNT_1,
                PaymentReference.of("PAY-FP-001"),
                new BeneficiaryDetails("Alice Smith", "DE89370400440532013000", BicCode.of("DEUTDEFF")),
                new Money(new BigDecimal("100.01"), new Currency("USD"))
        )));

        // Different beneficiaryName
        assertNotEquals(baseFp, InstructionFingerprint.compute(new PaymentInstruction(
                ACCOUNT_1,
                PaymentReference.of("PAY-FP-001"),
                new BeneficiaryDetails("Bob Smith", "DE89370400440532013000", BicCode.of("DEUTDEFF")),
                new Money(new BigDecimal("100.00"), new Currency("USD"))
        )));

        // Different beneficiaryAccountNumber
        assertNotEquals(baseFp, InstructionFingerprint.compute(new PaymentInstruction(
                ACCOUNT_1,
                PaymentReference.of("PAY-FP-001"),
                new BeneficiaryDetails("Alice Smith", "DE89370400440532013001", BicCode.of("DEUTDEFF")),
                new Money(new BigDecimal("100.00"), new Currency("USD"))
        )));

        // Different beneficiaryBankBic
        assertNotEquals(baseFp, InstructionFingerprint.compute(new PaymentInstruction(
                ACCOUNT_1,
                PaymentReference.of("PAY-FP-001"),
                new BeneficiaryDetails("Alice Smith", "DE89370400440532013000", BicCode.of("CHASUS33")),
                new Money(new BigDecimal("100.00"), new Currency("USD"))
        )));
    }

    @Test
    void testOfHexValidation() {
        assertThrows(PaymentValidationException.class, () -> InstructionFingerprint.ofHex(null));
        assertThrows(PaymentValidationException.class, () -> InstructionFingerprint.ofHex("abc"));
        assertThrows(PaymentValidationException.class, () -> InstructionFingerprint.ofHex("A".repeat(64))); // uppercase rejected
        assertThrows(PaymentValidationException.class, () -> InstructionFingerprint.compute(null));
    }
}
