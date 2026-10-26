package com.crosspaynet.compliance.contracts;

import com.crosspaynet.payment.contracts.PaymentId;

import java.util.Objects;

public record ScreenPaymentCommand(PaymentId paymentId) {
    public ScreenPaymentCommand {
        Objects.requireNonNull(paymentId, "paymentId cannot be null.");
    }
}
