# Interaction Flows

This document visualizes the conceptual interactions across domain boundaries (aggregates/modules) for key scenarios in CrossPayNet. It highlights synchronous vs. asynchronous communication and branches for failure paths.

---

## Flow 1: Successful Cross-Border Payment

This represents the "Happy Path" where a customer initiates a payment that clears compliance, routes successfully, and settles.

```mermaid
sequenceDiagram
    autonumber
    actor Customer
    participant PaymentModule as Payment Aggregate
    participant BankingModule as Banking/Account
    participant LedgerModule as Ledger Aggregate
    participant ComplianceModule as Compliance
    participant RoutingModule as Routing
    participant NetworkModule as Network/Messaging
    actor ReceivingBank as Receiving Bank

    Customer->>PaymentModule: Submit Payment Instruction
    PaymentModule->>BankingModule: (Sync) Verify Account & Limits
    BankingModule-->>PaymentModule: Verified
    
    %% Reservation Point
    PaymentModule->>BankingModule: (Sync) Reserve Funds
    BankingModule-->>PaymentModule: Reservation Confirmed (Available Balance Reduced)
    
    %% Asynchronous Compliance
    PaymentModule->>ComplianceModule: (Async Event) PaymentCreated
    Note over PaymentModule: State: VALIDATING -> COMPLIANCE_HOLD
    ComplianceModule-->>PaymentModule: (Async Event) ComplianceCleared
    
    %% Routing
    PaymentModule->>RoutingModule: (Sync) Select Route
    RoutingModule-->>PaymentModule: Route Selected (Correspondent X)
    
    %% Commitment Point
    Note over PaymentModule: State: ROUTING -> EXECUTING (Committed)
    
    %% Messaging
    PaymentModule->>NetworkModule: (Async Command) Dispatch Message
    Note over PaymentModule: State: PENDING_EXTERNAL
    NetworkModule->>ReceivingBank: Send ISO 20022 Message
    ReceivingBank-->>NetworkModule: ACK (Acknowledged)
    NetworkModule-->>PaymentModule: (Async Event) MessageDelivered
    
    %% Settlement
    PaymentModule->>LedgerModule: (Sync) Execute Final Settlement
    Note over PaymentModule: State: SETTLED
    PaymentModule-->>Customer: Payment Successful
```

---

## Flow 2: Payment Rejection via Compliance Hold

This flow demonstrates what happens when a payment is rejected before the bank financially commits to it.

```mermaid
sequenceDiagram
    autonumber
    actor Customer
    participant PaymentModule as Payment Aggregate
    participant LedgerModule as Ledger Aggregate
    participant ComplianceModule as Compliance
    actor ComplianceOfficer as Compliance Officer

    Customer->>PaymentModule: Submit Payment Instruction
    PaymentModule->>BankingModule: (Sync) Reserve Funds
    
    PaymentModule->>ComplianceModule: (Async Event) PaymentCreated
    Note over PaymentModule: State: COMPLIANCE_HOLD
    
    ComplianceModule->>ComplianceModule: Rule Match (Sanctions hit)
    Note over ComplianceModule: Requires Manual Review
    
    ComplianceOfficer->>ComplianceModule: Reject Payment (True Positive)
    ComplianceModule-->>PaymentModule: (Async Event) ComplianceRejected
    
    %% Compensating Action Pre-Commitment
    Note over PaymentModule: State: REJECTED
    PaymentModule->>BankingModule: (Sync) Release Reservation
    BankingModule-->>PaymentModule: Reservation Released (Available Balance Restored)
    
    PaymentModule-->>Customer: Payment Rejected (Policy)
```

---

## Flow 3: Network Timeout and Internal Retry

This flow demonstrates how the system handles transient network failures without requiring the user to submit a new payment, distinguishing network retries from duplicate submissions.

```mermaid
sequenceDiagram
    autonumber
    participant PaymentModule as Payment Aggregate
    participant NetworkModule as Network/Messaging
    actor ReceivingBank as Receiving Bank

    Note over PaymentModule: State: EXECUTING
    PaymentModule->>NetworkModule: (Async Command) Dispatch Message (MsgId: 100)
    Note over PaymentModule: State: PENDING_EXTERNAL
    
    NetworkModule->>ReceivingBank: Send ISO 20022 Message (MsgId: 100)
    
    %% Network Failure
    Note over NetworkModule: Timeout! No ACK received.
    NetworkModule-->>PaymentModule: (Async Event) DeliveryFailed (Transient)
    
    %% Internal Retry Mechanism
    Note over PaymentModule: Retries remain > 0
    PaymentModule->>NetworkModule: (Async Command) Dispatch Message (MsgId: 101)
    Note over PaymentModule: Same Payment, New Message Identity
    
    NetworkModule->>ReceivingBank: Send ISO 20022 Message (MsgId: 101)
    ReceivingBank-->>NetworkModule: ACK
    NetworkModule-->>PaymentModule: (Async Event) MessageDelivered
    
    Note over PaymentModule: State: SETTLED
```

---

## Failure Branches Summary

*   **Scenario A: Insufficient funds**: Fails at Step 4 of Flow 1. The `Banking` aggregate rejects the synchronous reservation command. The Payment immediately transitions to `REJECTED`.
*   **Scenario E: No valid routing relationship**: Fails at Step 8 of Flow 1. The `Payment` cannot be routed. It transitions to `REJECTED`, and the synchronous release of reserved funds is triggered at the `Banking` module.
*   **Scenario I: Settlement Failure (Post-Delivery)**: If the `NetworkModule` receives an ACK, the payment is effectively `SETTLED` from the customer's perspective. If the correspondent bank later fails to clear the Nostro account, this creates a reconciliation break in the `Ledger`. The Payment itself is not rolled back; instead, an Operations Analyst must resolve the dispute manually or initiate a compensating accounting entry.
