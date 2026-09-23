package com.crosspaynet.banking.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "customer", schema = "banking")
public class CustomerEntity {

    @Id
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    protected CustomerEntity() {}

    public CustomerEntity(UUID customerId) {
        this.customerId = customerId;
    }

    public UUID getCustomerId() {
        return customerId;
    }
}
