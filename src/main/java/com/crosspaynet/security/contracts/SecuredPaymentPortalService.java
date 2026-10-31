package com.crosspaynet.security.contracts;

import com.crosspaynet.banking.contracts.CustomerAccountView;
import com.crosspaynet.payment.contracts.CancelPaymentCommand;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.PagedPayments;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentView;

import java.util.List;

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

    /**
     * Finds all accounts owned by the authenticated customer.
     * Enforces {@code ROLE_CUSTOMER}.
     */
    List<CustomerAccountView> findCustomerAccounts();

    /**
     * Retrieves paginated payment history for all accounts owned by the authenticated customer.
     * Enforces {@code ROLE_CUSTOMER}.
     */
    PagedPayments findCustomerPayments(int page, int size);
}
