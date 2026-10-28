package com.crosspaynet.operations.infrastructure.web;

import java.util.List;
import java.util.Objects;

/**
 * Standard pagination envelope for API responses.
 *
 * @param <T> element type
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public PagedResponse {
        Objects.requireNonNull(content, "content cannot be null.");
        content = List.copyOf(content);
    }
}
