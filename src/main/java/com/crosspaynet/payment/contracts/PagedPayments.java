package com.crosspaynet.payment.contracts;

import java.util.List;
import java.util.Objects;

/**
 * Bounded paginated container for payments query results.
 */
public record PagedPayments(
        List<PaymentView> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public PagedPayments {
        Objects.requireNonNull(content, "content cannot be null.");
        content = List.copyOf(content);
    }

    public static PagedPayments empty(int page, int size) {
        return new PagedPayments(List.of(), page, size, 0L, 0);
    }
}
