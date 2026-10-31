package com.crosspaynet.security.portal.web;

import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.security.DataMasker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Customer-facing response DTO for payment initiation and status retrieval.
 *
 * <p>Exposes customer-safe payment details while masking sensitive account numbers
 * via {@link DataMasker} and omitting internal ledger transactions, reservation tokens,
 * route references, and network dispatch identities.</p>
 */
public record CustomerPaymentResponse(
        UUID paymentId,
        String paymentReference,
        UUID sourceAccountId,
        BigDecimal amount,
        String currency,
        String status,
        String beneficiaryName,
        String beneficiaryAccountNumber,
        String beneficiaryBankBic,
        String statusReasonCode,
        String statusReasonDetail,
        Instant createdAt,
        Instant updatedAt
) {
    public CustomerPaymentResponse {
        Objects.requireNonNull(paymentId, "paymentId cannot be null.");
        Objects.requireNonNull(paymentReference, "paymentReference cannot be null.");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId cannot be null.");
        Objects.requireNonNull(amount, "amount cannot be null.");
        Objects.requireNonNull(currency, "currency cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(beneficiaryName, "beneficiaryName cannot be null.");
        Objects.requireNonNull(beneficiaryAccountNumber, "beneficiaryAccountNumber cannot be null.");
        Objects.requireNonNull(beneficiaryBankBic, "beneficiaryBankBic cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");
    }

    public static CustomerPaymentResponse from(PaymentView view) {
        if (view == null) return null;
        return new CustomerPaymentResponse(
                view.paymentId().getValue(),
                view.instruction().paymentReference().getValue(),
                view.instruction().sourceAccountId().getValue(),
                view.instruction().amount().getAmount(),
                view.instruction().amount().getCurrency().getCode(),
                view.status().name(),
                view.instruction().beneficiary().beneficiaryName(),
                DataMasker.maskAccountNumber(view.instruction().beneficiary().beneficiaryAccountNumber()),
                view.instruction().beneficiary().beneficiaryBankBic().getValue(),
                view.statusReasonCode() != null ? view.statusReasonCode().name() : null,
                view.statusReasonDetail(),
                view.createdAt(),
                view.updatedAt()
        );
    }
}
