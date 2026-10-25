package com.crosspaynet.payment.contracts;

/**
 * Standardized machine-readable reason codes for terminal non-settled Payment states
 * ({@link PaymentStatus#REJECTED}, {@link PaymentStatus#CANCELLED}, and {@link PaymentStatus#FAILED}).
 */
public enum PaymentReasonCode {
    // Pre-reservation Banking business rejection reasons (reservation_token IS NULL)
    INSUFFICIENT_FUNDS,
    INVALID_ACCOUNT_STATE,
    CURRENCY_MISMATCH,

    // Post-reservation / pre-commitment business rejection reasons (reservation_token IS NOT NULL)
    COMPLIANCE_REJECTED,
    NO_ROUTE_AVAILABLE,

    // Pre-commitment customer cancellation reason
    CUSTOMER_CANCELLED,

    // Post-commitment confirmed pre-settlement failure reasons (status = FAILED)
    EXTERNAL_NETWORK_REJECTED,
    OPERATIONAL_FAILURE;

    public boolean isReservationFailureReason() {
        return this == INSUFFICIENT_FUNDS
                || this == INVALID_ACCOUNT_STATE
                || this == CURRENCY_MISMATCH;
    }

    public boolean isPreCommitmentRejectionReason() {
        return this == COMPLIANCE_REJECTED
                || this == NO_ROUTE_AVAILABLE;
    }

    public boolean isPostCommitmentFailureReason() {
        return this == EXTERNAL_NETWORK_REJECTED
                || this == OPERATIONAL_FAILURE;
    }
}
