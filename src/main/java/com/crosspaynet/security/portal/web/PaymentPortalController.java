package com.crosspaynet.security.portal.web;

import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.PagedPayments;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentNotFoundException;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.security.contracts.SecuredPaymentPortalService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Secured REST API for customer payment lifecycle operations.
 *
 * <p>Enforces {@code ROLE_CUSTOMER} on all operations and delegates strictly to
 * {@link SecuredPaymentPortalService} to enforce customer debit account ownership.</p>
 */
@RestController
@RequestMapping("/api/v1/portal/payments")
@PreAuthorize("hasRole('CUSTOMER')")
public class PaymentPortalController {

    private final SecuredPaymentPortalService portalService;

    public PaymentPortalController(SecuredPaymentPortalService portalService) {
        this.portalService = Objects.requireNonNull(portalService, "portalService cannot be null.");
    }

    /**
     * Initiates a cross-border payment on behalf of the authenticated customer.
     *
     * <p>Enforces customer account ownership and conflict-safe idempotency.</p>
     */
    @PostMapping
    public ResponseEntity<CustomerPaymentResponse> initiatePayment(@RequestBody InitiatePaymentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("InitiatePaymentRequest cannot be null.");
        }

        InitiatePaymentCommand command = request.toCommand();
        PaymentView payment = portalService.initiatePayment(command);

        URI location = URI.create("/api/v1/portal/payments/" + payment.paymentId().getValue());

        return ResponseEntity.created(location).body(CustomerPaymentResponse.from(payment));
    }

    /**
     * Retrieves paginated payment history for the authenticated customer.
     *
     * <p>Orders payments deterministically by {@code createdAt DESC, paymentId DESC} and bounds
     * page size between 1 and 100.</p>
     */
    @GetMapping
    public ResponseEntity<PagedResponse<CustomerPaymentResponse>> getPaymentHistory(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index must be non-negative.");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100.");
        }

        PagedPayments paged = portalService.findCustomerPayments(page, size);
        List<CustomerPaymentResponse> items = paged.content().stream()
                .map(CustomerPaymentResponse::from)
                .toList();

        PagedResponse<CustomerPaymentResponse> response = new PagedResponse<>(
                items,
                paged.page(),
                paged.size(),
                paged.totalElements(),
                paged.totalPages()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves an individual payment by identifier.
     *
     * <p>Enforces customer ownership: payments belonging to other customers return 404 Not Found
     * to prevent enumeration of UUIDs.</p>
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<CustomerPaymentResponse> getPayment(@PathVariable("paymentId") UUID paymentId) {
        PaymentId id = PaymentId.of(paymentId);
        try {
            PaymentView payment = portalService.findPayment(id);
            return ResponseEntity.ok(CustomerPaymentResponse.from(payment));
        } catch (AccessDeniedException ex) {
            // Customer ownership privacy policy: treat inaccessible payments as 404 Not Found
            // to prevent enumeration of other customers' payment UUIDs
            throw new PaymentNotFoundException(id);
        }
    }
}
