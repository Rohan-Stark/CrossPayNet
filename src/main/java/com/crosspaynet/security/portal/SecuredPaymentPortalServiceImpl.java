package com.crosspaynet.security.portal;

import com.crosspaynet.banking.BankingService;
import com.crosspaynet.payment.PaymentService;
import com.crosspaynet.payment.contracts.CancelPaymentCommand;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentNotFoundException;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.security.SecurityRoles;
import com.crosspaynet.security.contracts.SecuredPaymentPortalService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

@Service
public class SecuredPaymentPortalServiceImpl implements SecuredPaymentPortalService {

    private final PaymentService paymentService;
    private final BankingService bankingService;

    public SecuredPaymentPortalServiceImpl(PaymentService paymentService, BankingService bankingService) {
        this.paymentService = Objects.requireNonNull(paymentService, "paymentService cannot be null.");
        this.bankingService = Objects.requireNonNull(bankingService, "bankingService cannot be null.");
    }

    @Override
    @PreAuthorize("hasRole('CUSTOMER')")
    public PaymentView initiatePayment(InitiatePaymentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("InitiatePaymentCommand cannot be null.");
        }

        UUID customerId = resolveAuthenticatedCustomerId();
        if (!bankingService.isAccountOwnedBy(command.sourceAccountId(), customerId)) {
            throw new AccessDeniedException("Customer '" + customerId + "' does not own debit account '"
                    + command.sourceAccountId() + "'.");
        }

        return paymentService.initiatePayment(command);
    }

    @Override
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPERATIONS', 'TELLER')")
    public PaymentView findPayment(PaymentId paymentId) {
        if (paymentId == null) {
            throw new IllegalArgumentException("PaymentId cannot be null.");
        }

        PaymentView payment = paymentService.findPayment(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (hasAnyRole(SecurityRoles.OPERATIONS, SecurityRoles.TELLER)) {
            return payment;
        }

        UUID customerId = resolveAuthenticatedCustomerId();
        if (!bankingService.isAccountOwnedBy(payment.instruction().sourceAccountId(), customerId)) {
            throw new AccessDeniedException("Customer '" + customerId + "' is not authorized to access payment '"
                    + paymentId + "'.");
        }

        return payment;
    }

    @Override
    @PreAuthorize("hasRole('CUSTOMER')")
    public PaymentView cancelPayment(CancelPaymentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("CancelPaymentCommand cannot be null.");
        }

        PaymentView payment = paymentService.findPayment(command.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(command.paymentId()));

        UUID customerId = resolveAuthenticatedCustomerId();
        if (!bankingService.isAccountOwnedBy(payment.instruction().sourceAccountId(), customerId)) {
            throw new AccessDeniedException("Customer '" + customerId + "' is not authorized to cancel payment '"
                    + command.paymentId() + "'.");
        }

        return paymentService.cancelPayment(command);
    }

    private UUID resolveAuthenticatedCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("User is not authenticated.");
        }

        String username = auth.getName();
        try {
            return UUID.fromString(username);
        } catch (IllegalArgumentException ex) {
            return UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (String role : roles) {
            String expected = "ROLE_" + role;
            for (GrantedAuthority authority : auth.getAuthorities()) {
                if (expected.equals(authority.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }
}
