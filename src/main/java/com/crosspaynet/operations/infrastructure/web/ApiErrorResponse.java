package com.crosspaynet.operations.infrastructure.web;

import java.time.Instant;
import java.util.Objects;

/**
 * Standardized HTTP error payload for operational API errors.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
    public ApiErrorResponse {
        Objects.requireNonNull(timestamp, "timestamp cannot be null.");
        Objects.requireNonNull(error, "error cannot be null.");
        Objects.requireNonNull(message, "message cannot be null.");
    }
}
