package com.crosspaynet.routing.domain;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.routing.contracts.RouteContext;
import com.crosspaynet.routing.contracts.RouteId;
import com.crosspaynet.routing.contracts.RouteStatus;
import com.crosspaynet.routing.contracts.RoutingValidationException;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Pure-Java aggregate root of the Routing module representing a correspondent banking route
 * and its associated Nostro/clearing ledger account reference.
 */
public final class CorrespondentRoute {

    private static final Pattern ROUTE_REFERENCE_PATTERN = Pattern.compile("^[A-Za-z0-9:_.-]{1,100}$");

    private final RouteId routeId;
    private final String routeReference;
    private final BicCode destinationBic;
    private final BicCode correspondentBic;
    private final Currency currency;
    private final String clearingAccountReference;
    private final int priority;
    private RouteStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    private CorrespondentRoute(
            RouteId routeId,
            String routeReference,
            BicCode destinationBic,
            BicCode correspondentBic,
            Currency currency,
            String clearingAccountReference,
            int priority,
            RouteStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this.routeId = routeId;
        this.routeReference = routeReference;
        this.destinationBic = destinationBic;
        this.correspondentBic = correspondentBic;
        this.currency = currency;
        this.clearingAccountReference = clearingAccountReference;
        this.priority = priority;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Registers a new {@code CorrespondentRoute} in {@link RouteStatus#ACTIVE} state.
     */
    public static CorrespondentRoute register(
            RouteId routeId,
            String routeReference,
            BicCode destinationBic,
            BicCode correspondentBic,
            Currency currency,
            String clearingAccountReference,
            int priority,
            Instant createdAt) {
        if (routeId == null) {
            throw new RoutingValidationException("routeId cannot be null.");
        }
        String normalizedRouteRef = validateRouteReference(routeReference);
        if (destinationBic == null) {
            throw new RoutingValidationException("destinationBic cannot be null.");
        }
        if (correspondentBic == null) {
            throw new RoutingValidationException("correspondentBic cannot be null.");
        }
        validateCurrency(currency);
        String normalizedClearingRef = validateClearingAccountReference(clearingAccountReference);
        validatePriority(priority);
        if (createdAt == null) {
            throw new RoutingValidationException("createdAt cannot be null.");
        }

        return new CorrespondentRoute(
                routeId,
                normalizedRouteRef,
                destinationBic,
                correspondentBic,
                currency,
                normalizedClearingRef,
                priority,
                RouteStatus.ACTIVE,
                createdAt,
                createdAt
        );
    }

    /**
     * Restores a persisted {@code CorrespondentRoute} aggregate and strictly validates
     * all stored attributes and timestamp invariants.
     */
    public static CorrespondentRoute restore(
            RouteId routeId,
            String routeReference,
            BicCode destinationBic,
            BicCode correspondentBic,
            Currency currency,
            String clearingAccountReference,
            int priority,
            RouteStatus status,
            Instant createdAt,
            Instant updatedAt) {
        if (routeId == null) {
            throw new PersistedRouteStateException("Restored CorrespondentRoute cannot have null routeId.");
        }
        if (routeReference == null
                || routeReference.isBlank()
                || !routeReference.equals(routeReference.trim())
                || routeReference.length() > RoutingLimits.MAX_ROUTE_REFERENCE_LENGTH
                || routeReference.codePoints().anyMatch(Character::isISOControl)
                || !ROUTE_REFERENCE_PATTERN.matcher(routeReference).matches()) {
            throw new PersistedRouteStateException(
                    "Restored CorrespondentRoute has invalid routeReference: " + routeReference
            );
        }
        if (destinationBic == null || correspondentBic == null) {
            throw new PersistedRouteStateException(
                    "Restored CorrespondentRoute cannot have null destinationBic or correspondentBic."
            );
        }
        if (currency == null || !RoutingLimits.isSupportedCurrencyCode(currency.getCode())) {
            throw new PersistedRouteStateException(
                    "Restored CorrespondentRoute has unsupported currency: "
                            + (currency != null ? currency.getCode() : "null")
            );
        }
        if (clearingAccountReference == null
                || clearingAccountReference.isBlank()
                || !clearingAccountReference.equals(clearingAccountReference.trim())
                || clearingAccountReference.length() > RoutingLimits.MAX_CLEARING_ACCOUNT_REFERENCE_LENGTH
                || clearingAccountReference.codePoints().anyMatch(Character::isISOControl)) {
            throw new PersistedRouteStateException(
                    "Restored CorrespondentRoute has invalid clearingAccountReference: " + clearingAccountReference
            );
        }
        if (priority < RoutingLimits.MIN_PRIORITY || priority > RoutingLimits.MAX_PRIORITY) {
            throw new PersistedRouteStateException(
                    "Restored CorrespondentRoute priority out of bounds: " + priority
            );
        }
        if (status == null) {
            throw new PersistedRouteStateException("Restored CorrespondentRoute cannot have null status.");
        }
        if (createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) {
            throw new PersistedRouteStateException(
                    "Restored CorrespondentRoute has invalid createdAt/updatedAt timestamps."
            );
        }

        return new CorrespondentRoute(
                routeId,
                routeReference,
                destinationBic,
                correspondentBic,
                currency,
                clearingAccountReference,
                priority,
                status,
                createdAt,
                updatedAt
        );
    }

    /**
     * Activates an inactive route ({@code INACTIVE -> ACTIVE}). Idempotent if already {@link RouteStatus#ACTIVE}.
     */
    public void activate(Instant timestamp) {
        requireValidTransitionTimestamp(timestamp);
        if (this.status == RouteStatus.ACTIVE) {
            return;
        }
        this.status = RouteStatus.ACTIVE;
        this.updatedAt = timestamp;
    }

    /**
     * Deactivates an active route ({@code ACTIVE -> INACTIVE}). Idempotent if already {@link RouteStatus#INACTIVE}.
     */
    public void deactivate(Instant timestamp) {
        requireValidTransitionTimestamp(timestamp);
        if (this.status == RouteStatus.INACTIVE) {
            return;
        }
        this.status = RouteStatus.INACTIVE;
        this.updatedAt = timestamp;
    }

    /**
     * Returns {@code true} if this route is {@link RouteStatus#ACTIVE} and matches both
     * {@code targetDestinationBic} and {@code targetCurrency}.
     */
    public boolean isEligibleFor(BicCode targetDestinationBic, Currency targetCurrency) {
        return this.status == RouteStatus.ACTIVE
                && this.destinationBic.equals(targetDestinationBic)
                && this.currency.equals(targetCurrency);
    }

    public RouteContext toContext() {
        return new RouteContext(
                routeId,
                routeReference,
                destinationBic,
                correspondentBic,
                currency,
                clearingAccountReference,
                priority,
                status,
                createdAt,
                updatedAt
        );
    }

    public RouteId getId() {
        return routeId;
    }

    public String getRouteReference() {
        return routeReference;
    }

    public BicCode getDestinationBic() {
        return destinationBic;
    }

    public BicCode getCorrespondentBic() {
        return correspondentBic;
    }

    public Currency getCurrency() {
        return currency;
    }

    public String getClearingAccountReference() {
        return clearingAccountReference;
    }

    public int getPriority() {
        return priority;
    }

    public RouteStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void requireValidTransitionTimestamp(Instant timestamp) {
        if (timestamp == null) {
            throw new RoutingValidationException("Transition timestamp cannot be null.");
        }
        if (timestamp.isBefore(this.updatedAt)) {
            throw new RoutingValidationException(
                    "Transition timestamp (" + timestamp + ") cannot be before current updatedAt ("
                            + this.updatedAt + ")."
            );
        }
    }

    public static String validateRouteReference(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RoutingValidationException("routeReference cannot be null or blank.");
        }
        if (raw.codePoints().anyMatch(Character::isISOControl)) {
            throw new RoutingValidationException("routeReference cannot contain ISO control characters.");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > RoutingLimits.MAX_ROUTE_REFERENCE_LENGTH
                || !ROUTE_REFERENCE_PATTERN.matcher(trimmed).matches()) {
            throw new RoutingValidationException(
                    "routeReference must be 1..100 characters matching [A-Za-z0-9:_.-]."
            );
        }
        return trimmed;
    }

    private static String validateClearingAccountReference(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RoutingValidationException("clearingAccountReference cannot be null or blank.");
        }
        if (raw.codePoints().anyMatch(Character::isISOControl)) {
            throw new RoutingValidationException(
                    "clearingAccountReference cannot contain ISO control characters."
            );
        }
        String trimmed = raw.trim();
        if (trimmed.length() > RoutingLimits.MAX_CLEARING_ACCOUNT_REFERENCE_LENGTH) {
            throw new RoutingValidationException(
                    "clearingAccountReference cannot exceed "
                            + RoutingLimits.MAX_CLEARING_ACCOUNT_REFERENCE_LENGTH + " characters."
            );
        }
        return trimmed;
    }

    private static void validateCurrency(Currency currency) {
        if (currency == null) {
            throw new RoutingValidationException("currency cannot be null.");
        }
        if (!RoutingLimits.isSupportedCurrencyCode(currency.getCode())) {
            throw new RoutingValidationException("Unsupported routing currency: " + currency.getCode());
        }
    }

    private static void validatePriority(int priority) {
        if (priority < RoutingLimits.MIN_PRIORITY || priority > RoutingLimits.MAX_PRIORITY) {
            throw new RoutingValidationException(
                    "priority must be between " + RoutingLimits.MIN_PRIORITY
                            + " and " + RoutingLimits.MAX_PRIORITY + "."
            );
        }
    }
}
