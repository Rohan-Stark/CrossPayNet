package com.crosspaynet.messaging.contracts;

import com.crosspaynet.payment.contracts.PaymentId;

import java.util.List;

/**
 * Public read-only query port exposed by the Messaging module for operational reconciliation.
 *
 * <p>Enables inspecting message transmission attempts and timeout history for a payment
 * without granting access to message dispatch or transmission mutation operations.
 */
public interface PaymentMessageQueryPort {

    /**
     * Lists all {@link PaymentMessageView} attempts for a given {@link PaymentId} ordered by
     * ascending {@code attemptNumber}.
     *
     * @param paymentId payment identifier (non-null)
     * @return ordered list of transmission attempts, or empty list if no messages have been generated
     */
    List<PaymentMessageView> findMessagesForPayment(PaymentId paymentId);
}
