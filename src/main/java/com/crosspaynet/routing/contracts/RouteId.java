package com.crosspaynet.routing.contracts;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly-typed identifier of a {@code CorrespondentRoute} aggregate.
 */
public final class RouteId {

    private final UUID value;

    public RouteId(UUID value) {
        if (value == null) {
            throw new RoutingValidationException("RouteId value cannot be null.");
        }
        this.value = value;
    }

    public static RouteId generate() {
        return new RouteId(UUID.randomUUID());
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RouteId routeId = (RouteId) o;
        return Objects.equals(value, routeId.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
