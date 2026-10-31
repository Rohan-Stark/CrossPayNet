package com.crosspaynet.security.portal.web;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Customer-facing request DTO for initiating a cross-border payment.
 */
public record InitiatePaymentRequest(
        UUID sourceAccountId,
        String paymentReference,
        BigDecimal amount,
        String currency,
        String beneficiaryName,
        String beneficiaryAccountNumber,
        String beneficiaryBankBic
) {
    public InitiatePaymentRequest {
        // Record constructor preserves raw input for HTTP binding
    }

    public InitiatePaymentCommand toCommand() {
        if (sourceAccountId == null) {
            throw new IllegalArgumentException("sourceAccountId cannot be null.");
        }
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new IllegalArgumentException("paymentReference cannot be null or blank.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount cannot be null.");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be strictly positive.");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency cannot be null or blank.");
        }
        if (beneficiaryName == null || beneficiaryName.isBlank()) {
            throw new IllegalArgumentException("beneficiaryName cannot be null or blank.");
        }
        if (beneficiaryAccountNumber == null || beneficiaryAccountNumber.isBlank()) {
            throw new IllegalArgumentException("beneficiaryAccountNumber cannot be null or blank.");
        }
        if (beneficiaryBankBic == null || beneficiaryBankBic.isBlank()) {
            throw new IllegalArgumentException("beneficiaryBankBic cannot be null or blank.");
        }

        Currency curr;
        try {
            curr = new Currency(currency.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid currency code: " + currency);
        }

        Money money;
        try {
            money = new Money(amount, curr);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid monetary amount: " + ex.getMessage());
        }

        BicCode bic = BicCode.of(beneficiaryBankBic.trim());

        BeneficiaryDetails beneficiary = new BeneficiaryDetails(
                beneficiaryName.trim(),
                beneficiaryAccountNumber.trim(),
                bic
        );

        return new InitiatePaymentCommand(
                new AccountId(sourceAccountId),
                PaymentReference.of(paymentReference.trim()),
                beneficiary,
                money
        );
    }
}
