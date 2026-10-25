package com.crosspaynet.payment.contracts;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.common.money.Money;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Immutable value object representing the validated customer intent to transfer funds.
 */
public record PaymentInstruction(
        AccountId sourceAccountId,
        PaymentReference paymentReference,
        BeneficiaryDetails beneficiary,
        Money amount
) {

    public static final int MAX_SCALE = 4;
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.9999");

    private static final Set<String> SUPPORTED_CURRENCY_CODES = Set.of(
            "USD",
            "EUR",
            "GBP",
            "INR",
            "JPY",
            "CHF",
            "SGD",
            "AED"
    );

    public PaymentInstruction {
        if (sourceAccountId == null) {
            throw new PaymentValidationException("sourceAccountId cannot be null.");
        }
        if (paymentReference == null) {
            throw new PaymentValidationException("paymentReference cannot be null.");
        }
        if (paymentReference.getValue().codePoints().anyMatch(Character::isISOControl)) {
            throw new PaymentValidationException("paymentReference cannot contain ISO control characters.");
        }
        if (beneficiary == null) {
            throw new PaymentValidationException("beneficiary cannot be null.");
        }
        if (amount == null) {
            throw new PaymentValidationException("amount cannot be null.");
        }
        if (!SUPPORTED_CURRENCY_CODES.contains(amount.getCurrency().getCode())) {
            throw new PaymentValidationException("Unsupported payment currency: " + amount.getCurrency().getCode());
        }
        if (!amount.isPositive()) {
            throw new PaymentValidationException("Payment amount must be strictly positive.");
        }
        if (amount.getAmount().stripTrailingZeros().scale() > MAX_SCALE) {
            throw new PaymentValidationException(
                    "Payment amount scale cannot exceed " + MAX_SCALE + " decimal places."
            );
        }
        if (amount.getAmount().compareTo(MAX_AMOUNT) > 0) {
            throw new PaymentValidationException(
                    "Payment amount exceeds maximum allowed value of " + MAX_AMOUNT + "."
            );
        }
    }
}
