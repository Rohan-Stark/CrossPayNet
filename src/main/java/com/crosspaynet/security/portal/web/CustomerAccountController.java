package com.crosspaynet.security.portal.web;

import com.crosspaynet.banking.contracts.CustomerAccountView;
import com.crosspaynet.security.contracts.SecuredPaymentPortalService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Secured REST endpoint for customer account overview.
 */
@RestController
@RequestMapping("/api/v1/portal/accounts")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerAccountController {

    private final SecuredPaymentPortalService portalService;

    public CustomerAccountController(SecuredPaymentPortalService portalService) {
        this.portalService = Objects.requireNonNull(portalService, "portalService cannot be null.");
    }

    @GetMapping
    public ResponseEntity<List<CustomerAccountResponse>> getAccounts() {
        List<CustomerAccountView> accounts = portalService.findCustomerAccounts();
        List<CustomerAccountResponse> response = accounts.stream()
                .map(CustomerAccountResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }
}
