package com.crosspaynet.settlement.contracts;

import com.crosspaynet.ledger.LedgerAccountRef;
import com.crosspaynet.ledger.LedgerAccountType;
import com.crosspaynet.ledger.LedgerValidationException;
import com.crosspaynet.payment.contracts.PaymentId;

/**
 * Command to finalize settlement for a dispatched Payment ({@code PENDING_EXTERNAL -> SETTLED})
 * by consuming its operational Banking reservation, posting a balanced double-entry Ledger
 * transaction against an explicit internal clearing account, and recording the resulting
 * Ledger transaction identifier on the Payment aggregate.
 */
public record SettlePaymentCommand(
        PaymentId paymentId,
        String clearingAccountReference,
        String settlementReference
) {

    public static final int MAX_SETTLEMENT_REFERENCE_LENGTH = 255;

    public SettlePaymentCommand {
        if (paymentId == null) {
            throw new SettlementValidationException("paymentId cannot be null.");
        }
        if (clearingAccountReference == null) {
            throw new SettlementValidationException("clearingAccountReference cannot be null.");
        }
        try {
            LedgerAccountRef.of(LedgerAccountType.INTERNAL, clearingAccountReference);
        } catch (LedgerValidationException ex) {
            throw new SettlementValidationException(
                    "Invalid clearingAccountReference: " + ex.getMessage(),
                    ex
            );
        }
        if (settlementReference == null || settlementReference.isBlank()) {
            throw new SettlementValidationException("settlementReference cannot be null or blank.");
        }
        settlementReference = settlementReference.trim();
        if (settlementReference.length() > MAX_SETTLEMENT_REFERENCE_LENGTH) {
            throw new SettlementValidationException(
                    "settlementReference cannot exceed " + MAX_SETTLEMENT_REFERENCE_LENGTH + " characters."
            );
        }
        if (settlementReference.codePoints().anyMatch(Character::isISOControl)) {
            throw new SettlementValidationException(
                    "settlementReference must not contain ISO control characters."
            );
        }
    }

    public LedgerAccountRef clearingAccount() {
        return LedgerAccountRef.of(LedgerAccountType.INTERNAL, clearingAccountReference);
    }
}
