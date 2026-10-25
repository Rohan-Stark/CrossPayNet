package com.crosspaynet.payment.contracts;

/**
 * Business lifecycle states of a {@code Payment} aggregate.
 */
public enum PaymentStatus {
    /**
     * Initial state when a payment instruction is constructed or claimed transaction-locally
     * prior to completing fund reservation.
     */
    VALIDATING,

    /**
     * Funds have been reserved in Banking; awaiting compliance evaluation.
     */
    COMPLIANCE_HOLD,

    /**
     * Compliance cleared; awaiting route selection and financial commitment.
     */
    ROUTING,

    /**
     * Financial Commitment Point reached; route selected and committed for execution.
     */
    EXECUTING,

    /**
     * Payment message dispatched externally; awaiting correspondent acknowledgement and settlement.
     */
    PENDING_EXTERNAL,

    /**
     * Terminal success state after final settlement outcome is recorded.
     */
    SETTLED,

    /**
     * Terminal pre-commitment business rejection state.
     */
    REJECTED,

    /**
     * Terminal pre-commitment customer cancellation state.
     */
    CANCELLED,

    /**
     * Terminal post-commitment confirmed pre-settlement failure state.
     */
    FAILED;

    public boolean isPreCommitment() {
        return this == VALIDATING || this == COMPLIANCE_HOLD || this == ROUTING;
    }

    public boolean isPostCommitmentActive() {
        return this == EXECUTING || this == PENDING_EXTERNAL;
    }

    public boolean isTerminal() {
        return this == SETTLED || this == REJECTED || this == CANCELLED || this == FAILED;
    }
}
