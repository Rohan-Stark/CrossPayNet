package com.crosspaynet.security.contracts;

import com.crosspaynet.payment.contracts.CancelPaymentCommand;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentView;

/**
 * Public secured portal facade enforcing customer ownership and role-based access control (RBAC).
 */
public interface SecuredPaymentPortalService {

    /**
     * Initiates a payment on behalf of an authenticated customer.
     * Enforces {@code ROLE_CUSTOMER} and verifies the source account belongs to the caller.
     */
    PaymentView initiatePayment(InitiatePaymentCommand command);

    /**
     * Finds a payment.
     * Enforces {@code ROLE_CUSTOMER} (must own source account) or {@code ROLE_OPERATIONS} / {@code ROLE_TELLER}.
     */
    PaymentView findPayment(PaymentId paymentId);

    /**
     * Cancels an eligible uncommitted payment on behalf of an authenticated customer.
     * Enforces {@code ROLE_CUSTOMER} and verifies the source account belongs to the caller.
     */
    PaymentView cancelPayment(CancelPaymentCommand command);
}
