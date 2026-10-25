package com.crosspaynet.payment.settlement;

import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentValidationException;

import java.util.UUID;

/**
 * Command used by the Settlement module to record a confirmed settlement outcome on a Payment
 * ({@code PENDING_EXTERNAL -> SETTLED}) without coupling Payment to Ledger internal types.
 */
public record RecordPaymentSettlementCommand(
        PaymentId paymentId,
        UUID ledgerTransactionId
) {

    public RecordPaymentSettlementCommand {
        if (paymentId == null) {
            throw new PaymentValidationException("paymentId cannot be null.");
        }
        if (ledgerTransactionId == null) {
            throw new PaymentValidationException("ledgerTransactionId cannot be null.");
        }
    }
}
