package com.crosspaynet.banking.domain;

public class Customer {
    private final CustomerId customerId;

    public Customer(CustomerId customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("CustomerId cannot be null.");
        }
        this.customerId = customerId;
    }

    public CustomerId getCustomerId() {
        return customerId;
    }
}
