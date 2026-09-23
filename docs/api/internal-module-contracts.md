# Internal Module Contracts

This document defines the conceptual, technology-neutral contracts between internal modules in the CrossPayNet Evolutionary Modular Monolith. These contracts define how boundaries enforce the interactions mapped in Phase 1.B.6.

*Note: No Java interfaces or implementation signatures are provided here. These are conceptual contracts.*

## 1. Synchronous Contracts (APIs)

Synchronous contracts are used when immediate consistency is required to satisfy a domain invariant (e.g., reserving funds before allowing a payment to proceed).

### `Banking.ReserveFunds`
* **Purpose**: Ensure sufficient liquidity exists and place a temporary hold on a customer's available balance.
* **Exposed By**: Banking Module
* **Consumed By**: Payment Module
* **Input Concepts**: 
  * `AccountNumber` (Target account)
  * `Money` (Amount & Currency)
  * `Reference` (e.g., Payment ID requesting the hold)
* **Output Concepts**:
  * `ReservationToken` (Proof of hold, required for final settlement)
* **Failure Semantics**: Throws domain exceptions (`InsufficientFundsException`, `AccountFrozenException`).
* **Behavior**: Synchronous. Automatically rolls back the surrounding database transaction if it fails.

### `Routing.SelectRoute`
* **Purpose**: Determine the valid correspondent banking path for a cross-border transfer.
* **Exposed By**: Routing Module
* **Consumed By**: Payment Module
* **Input Concepts**:
  * `DestinationBic`
  * `Currency`
* **Output Concepts**:
  * `RouteContext` (Selected correspondent BIC, path details)
* **Failure Semantics**: Throws `NoValidRouteException`.
* **Behavior**: Synchronous read-only query.

## 2. Asynchronous Contracts (Domain Events)

Asynchronous contracts (Spring Application Events in the monolith) are used when eventual consistency is acceptable, or to decouple side effects. Domain events are business facts, not Kafka topics. Any workflow-critical asynchronous event requires durable transactional publication to guarantee it is not lost in a crash.

### `PaymentInstructionReceived`
* **Purpose**: Broadcast that a new payment instruction has been accepted and requires downstream evaluation.
* **Published By**: Payment Module
* **Listened By**: Compliance Module
* **Payload Concepts**:
  * `PaymentId`
  * `BeneficiaryDetails`
  * `SourceDetails`
  * `Money` (Amount & Currency)
* **Failure Semantics**: The publisher does not care if listeners fail. Listeners must implement their own retry mechanisms (e.g., Spring Modulith Event Publication Registry).
* **Behavior**: Asynchronous fire-and-forget.

### `FundsReserved`
* **Purpose**: Fact that the Banking module successfully held operational funds for a Payment.
* **Published By**: Banking Module
* **Listened By**: Payment Module
* **Payload Concepts**:
  * `PaymentId`
  * `ReservationToken`
* **Behavior**: Asynchronous fact notification (can be used alongside the synchronous API for decoupled listeners).

### `ComplianceCleared` / `ComplianceRejected`
* **Purpose**: Notify the orchestrator of the outcome of a compliance review.
* **Published By**: Compliance Module
* **Listened By**: Payment Module
* **Payload Concepts**:
  * `PaymentId`
  * `Reason`
* **Failure Semantics**: If the Payment module fails to process this, the event mechanism retries it.
* **Behavior**: Asynchronous. The Payment Module transitions from `COMPLIANCE_HOLD` to `ROUTING` or `REJECTED`.

### `PaymentSentToNetwork`
* **Purpose**: Fact that the messaging layer physically transmitted data across the network boundary.
* **Published By**: Network/Messaging Module
* **Listened By**: Payment Module
* **Payload Concepts**:
  * `PaymentId`
  * `RouteContext`
* **Failure Semantics**: Handled asynchronously. If the network is down, the Network module handles its own internal *network retries*.

### `PaymentSettled`
* **Purpose**: Inform the orchestrator that the external network acknowledged the transaction and final settlement occurred.
* **Published By**: Network/Messaging Module
* **Listened By**: Payment Module
* **Payload Concepts**:
  * `PaymentId`
* **Behavior**: Asynchronous. Triggers the Payment state machine to move to `SETTLED`.
