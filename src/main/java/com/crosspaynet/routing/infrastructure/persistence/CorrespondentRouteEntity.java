package com.crosspaynet.routing.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping for {@code routing.correspondent_route}.
 */
@Entity
@Table(name = "correspondent_route", schema = "routing")
public class CorrespondentRouteEntity {

    @Id
    @Column(name = "route_id", nullable = false, updatable = false)
    private UUID routeId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "route_reference", nullable = false, updatable = false, length = 100)
    private String routeReference;

    @Column(name = "destination_bic", nullable = false, updatable = false, length = 11)
    private String destinationBic;

    @Column(name = "correspondent_bic", nullable = false, updatable = false, length = 11)
    private String correspondentBic;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "clearing_account_reference", nullable = false, updatable = false, length = 100)
    private String clearingAccountReference;

    @Column(name = "priority", nullable = false, updatable = false)
    private int priority;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CorrespondentRouteEntity() {
    }

    public CorrespondentRouteEntity(
            UUID routeId,
            String routeReference,
            String destinationBic,
            String correspondentBic,
            String currency,
            String clearingAccountReference,
            int priority,
            String status,
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

    public UUID getRouteId() {
        return routeId;
    }

    public long getVersion() {
        return version;
    }

    public String getRouteReference() {
        return routeReference;
    }

    public String getDestinationBic() {
        return destinationBic;
    }

    public String getCorrespondentBic() {
        return correspondentBic;
    }

    public String getCurrency() {
        return currency;
    }

    public String getClearingAccountReference() {
        return clearingAccountReference;
    }

    public int getPriority() {
        return priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
