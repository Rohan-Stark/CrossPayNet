package com.crosspaynet.operations.contracts;

import java.util.List;
import java.util.Objects;

/**
 * Generic contract container for bounded, paginated query results.
 *
 * @param <T> content element type
 * @param content page items
 * @param page zero-based page index
 * @param size requested page size
 * @param totalElements total number of matching elements across all pages
 * @param totalPages total number of available pages
 */
public record PagedResult<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public PagedResult {
        Objects.requireNonNull(content, "content cannot be null.");
        content = List.copyOf(content);
    }
}
