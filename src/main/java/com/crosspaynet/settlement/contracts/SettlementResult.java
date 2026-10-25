package com.crosspaynet.settlement.contracts;

import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.ledger.LedgerTransactionId;
import com.crosspaynet.ledger.PostingOutcome;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentView;

import java.time.Instant;

/**
 * Immutable result returned by {@code SettlementService.settlePayment(...)}.
 */
public record SettlementResult(
        PaymentId paymentId,
        ReservationToken reservationToken,
        LedgerTransactionId ledgerTransactionId,
        PostingOutcome ledgerPostingOutcome,
        PaymentView payment,
        Instant settledAt
) {

    public SettlementResult {
        if (paymentId == null) {
            throw new SettlementValidationException("paymentId cannot be null.");
        }
        if (reservationToken == null) {
            throw new SettlementValidationException("reservationToken cannot be null.");
        }
        if (ledgerTransactionId == null) {
            throw new SettlementValidationException("ledgerTransactionId cannot be null.");
        }
        if (ledgerPostingOutcome == null) {
            throw new SettlementValidationException("ledgerPostingOutcome cannot be null.");
        }
        if (payment == null) {
            throw new SettlementValidationException("payment cannot be null.");
        }
        if (settledAt == null) {
            throw new SettlementValidationException("settledAt cannot be null.");
        }
    }
}
