package com.crosspaynet.payment.contracts;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.common.money.Money;

/**
 * Command to initiate a new cross-border payment.
 */
public record InitiatePaymentCommand(
        AccountId sourceAccountId,
        PaymentReference paymentReference,
        BeneficiaryDetails beneficiary,
        Money amount
) {

    public InitiatePaymentCommand {
        // Validate by constructing the canonical PaymentInstruction
        new PaymentInstruction(sourceAccountId, paymentReference, beneficiary, amount);
    }

    public PaymentInstruction toInstruction() {
        return new PaymentInstruction(sourceAccountId, paymentReference, beneficiary, amount);
    }
}
