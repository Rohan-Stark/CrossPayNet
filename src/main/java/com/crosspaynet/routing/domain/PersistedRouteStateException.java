package com.crosspaynet.routing.domain;

import com.crosspaynet.routing.contracts.RoutingException;

/**
 * Thrown when a persisted {@link CorrespondentRoute} record violates domain invariants
 * upon database restoration.
 */
public class PersistedRouteStateException extends RoutingException {

    public PersistedRouteStateException(String message) {
        super(message);
    }

    public PersistedRouteStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
