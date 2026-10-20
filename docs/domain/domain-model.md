# CrossPayNet Domain Model Refinement

This document defines the rigorous conceptual domain model for CrossPayNet, independent of any implementation technology. 

---

## 1. Entity vs. Value Object Classification

We must distinguish between concepts that have an ongoing lifecycle/identity (**Entities**) and concepts defined solely by their attributes (**Value Objects**).

### Value Objects
*Why primitive types are weaker*: A primitive string cannot guarantee it is a valid BIC. A primitive decimal cannot prevent adding USD to EUR.
* **Money**: (Amount, Currency). Immutable. Equality is based on exact value match. Arithmetic operations yield a *new* Money object.
* **Currency**: (Code, Exponent). Immutable. 
* **BIC (Bank Identifier Code)**: Encapsulates validation rules for 8 or 11-character SWIFT codes.
* **AccountNumber**: Encapsulates IBAN or domestic account validation logic.
* **PaymentReference**: A string adhering to specific network formatting rules, protecting against injection or truncation.

### Entities
*Identity matters regardless of attribute changes.*
* **Customer**: Can change their address, but remains the same Customer.
* **Account**: Balance changes, but it remains the same Account.
* **Payment**: Progresses through a state machine, maintaining the same Payment ID.
* **PaymentMessage**: Represents a specific physical transmission attempt.
* **LedgerTransaction**: An individual double-entry accounting transaction containing immutable debit and credit entries.

---

## 2. Critical Payment Distinctions

A common pitfall is conflating the intent, the orchestration, the transmission, and the accounting. CrossPayNet separates these strictly:

1. **Payment Instruction**: *What it IS*: The raw, unvalidated intent from a Customer. *What it is NOT*: A financial liability. *Who creates it*: The Customer (via API).
2. **Payment**: *What it IS*: The bank's internal orchestrator/state machine. *What it is NOT*: The message sent to another bank. *Who creates it*: The Payment Engine (after validating the Instruction).
3. **Payment Message**: *What it IS*: The physical artifact (e.g., ISO 20022 `pacs.008`) sent over the wire. *What it is NOT*: The Payment orchestration. *Why separate*: If a network link drops, we generate a *new* Message for the *same* Payment via a different route.
4. **Settlement**: *What it IS*: The inter-bank settlement obligation and process. *What it is NOT*: The customer-facing balance reservation.
5. **LedgerTransaction & LedgerEntry**: *What they ARE*: The authoritative, immutable double-entry accounting transaction and its individual `DEBIT` / `CREDIT` line items.

*Conceptual Flow*:
`Customer` -> submits -> `Instruction` -> validates into -> `Payment` -> generates -> `Message` -> results in -> `Settlement` (recorded via `LedgerTransaction` and `LedgerEntries`).

---

## 3. Aggregate Boundaries

An aggregate is a transactional consistency boundary. Things inside an aggregate must be strictly consistent immediately. Things crossing aggregates can be eventually consistent.

### The LedgerTransaction Aggregate
* **Root**: `LedgerTransaction` (There is no single global `Ledger` aggregate root, which would create a concurrency bottleneck).
* **Contains**: `LedgerEntry` (immutable child line items, 2 to 100 entries per transaction).
* **Must remain outside**: `Account` status, operational reservations, `Payment` details.
* **Invariants protected**: Every posted `LedgerTransaction` is single-currency, contains between 2 and 100 `LedgerEntry` lines, each entry has a strictly positive unsigned `Money` amount and an explicit direction (`DEBIT` or `CREDIT`), and total debits equal total credits and equal the transaction's declared `totalAmount` (`sum(DEBIT) == sum(CREDIT) == totalAmount`).
* **Why this boundary**: Ensures money is never created or destroyed mathematically while allowing independent transactions to be posted concurrently.

### The Payment Aggregate
* **Root**: `Payment`
* **Contains**: `PaymentLegs`, `PaymentStatusHistory`
* **Must remain outside**: `ComplianceCheck` rules, `PaymentMessage`.
* **Invariants protected**: A Payment cannot transition to `ROUTING` unless `COMPLIANCE` has explicitly passed. 
* **Why this boundary**: Protects the state machine of the transfer.

### The Account Aggregate
* **Root**: `Account`
* **Contains**: `AccountStatus`, `FundReservation`, `Limits`
* **Must remain outside**: The actual `LedgerEntries`.
* **Invariants protected**: An account cannot be debited or have funds reserved if its status is not `ACTIVE` (e.g., `FROZEN` or `CLOSED`). The sum of `ACTIVE` reservations must strictly equal the `reservedAmount`, and `availableBalance` and `reservedAmount` cannot be negative.
* **Why this boundary**: Separates operational authorization rules (available balance, reservations, freezing) owned by `Banking` from immutable double-entry accounting owned by `Ledger`.

---

## 4. Account, Ledger, and Settlement Model

* **The Relationship**: `Banking` owns the operational `Account` aggregate (`availableBalance`, `reservedAmount`, and `FundReservation` holds) used for fast synchronous authorization. `Ledger` owns the authoritative accounting history (`LedgerTransaction` and `LedgerEntry`), from which the settled **ledger balance** (`debitTotal`, `creditTotal`, and signed `netBalance = debitTotal - creditTotal` as a Phase 3 signed calculation convention) is derived on read. *(Note: During Phase 2/3 simulator bootstrap, `BankingService.fundAccount` increments operational `availableBalance` without posting a `LedgerTransaction`; full reconciliation between operational and ledger balances is deferred to a later phase).*
* **Immutability**: A posted `LedgerTransaction` and its `LedgerEntry` lines, once committed, are immutable. To correct or reverse a posted transaction, a new compensating `REVERSAL` `LedgerTransaction` (referencing `reversed_transaction_id` and flipping `DEBIT` and `CREDIT` directions) must be posted.
* **Settlement**: Settlement is the bank-to-bank obligation and liquidity process owned by the `Settlement` module, which invokes the `Ledger` module to record the resulting `LedgerTransaction` entries on Nostro/Vostro accounts.

---

## 5. Compliance and Risk Model

* **Compliance Check**: Modeled as a separate domain concept (potentially its own aggregate later). A `Payment` requests a check asynchronously. The `Compliance` domain owns the rules (Sanctions lists) and returns a domain event (`ComplianceCleared` or `ComplianceBlocked`).
* **Risk Assessment**: A value generated by an external AI model. It does not pause the payment directly; rather, the `Payment` aggregate evaluates the returned Risk Assessment score against its own policies.

---

## 6. Payment Lifecycle (Business State Machine)

This machine represents true business states, removing temporary technical states.

```mermaid
stateDiagram-v2
    [*] --> VALIDATING : Instruction Received
    VALIDATING --> REJECTED : Invalid Data/Funds
    VALIDATING --> COMPLIANCE_HOLD : Funds Reserved
    COMPLIANCE_HOLD --> REJECTED : Sanctions Hit
    COMPLIANCE_HOLD --> ROUTING : Cleared
    ROUTING --> REJECTED : No Route Found
    ROUTING --> EXECUTING : Route Selected
    
    EXECUTING --> SETTLED : Internal Transfer
    EXECUTING --> PENDING_EXTERNAL : Sent to Network
    
    PENDING_EXTERNAL --> SETTLED : Network ACK / Nostro Settled
    PENDING_EXTERNAL --> FAILED : Network NACK / Timeout
    
    SETTLED --> [*]
    REJECTED --> [*]
    FAILED --> [*]
```
*Note: `EXECUTING` means the bank has committed to the transfer. `FAILED` represents a failure after commitment, requiring a compensating Ledger transaction (Return). `REJECTED` means the payment was stopped before execution.*

---

## 7. Payment Message Lifecycle

The message has a strictly technical lifecycle distinct from the Payment.

```mermaid
stateDiagram-v2
    [*] --> GENERATED
    GENERATED --> SIGNED
    SIGNED --> SENT
    SENT --> ACKNOWLEDGED : Peer Received
    SENT --> NACKNOWLEDGED : Peer Rejected
    SENT --> TIMEOUT : No Response
```

---

## 8. Domain Invariants vs. Rules

* **Domain Invariant (Strict)**: *Ledger Double-Entry Balance*. Every `LedgerTransaction` must have 2 to 100 entries with positive unsigned `Money` amounts in a single currency where `sum(DEBIT) == sum(CREDIT) == totalAmount`. (Enforced by the `LedgerTransaction` Aggregate).
* **Domain Invariant (Strict)**: *Payment Progression*. A payment cannot settle if compliance is pending. (Enforced by `Payment` Aggregate).
* **Security/Authorization Rule (Cross-cutting)**: Only a user with `ROLE_TELLER` can override a risk hold. (Enforced by application security layer, not the domain model).
* **Operational Rule (Configurable)**: Cross-border payments over $10,000 require manual review. (Enforced by business rule engine).

---

## 9. Domain Events

These represent genuine business facts that have occurred, used to communicate across aggregate boundaries.

* `PaymentInstructionReceived`: Fact that a customer requested a move.
* `FundsReserved`: Fact that the `Banking` module successfully held operational funds on an `Account` for a Payment.
* `ComplianceCleared`: Fact that the Compliance domain approved the entity.
* `PaymentSentToNetwork`: Fact that a Message was physically dispatched.
* `PaymentSettled`: Fact that the final accounting effect is complete.

*(Excluded: Technical events like `DatabaseUpdated` or `MessageQueued`. Note: No domain events are published by the `Ledger` module in Phase 3).*

---

## 10. Module Mapping (Future Boundaries)

We map these aggregates to candidate logical modules for our Evolutionary Modular Monolith:

1. **Banking Module**: Owns `Customer`, `Account` (including `availableBalance`, `reservedAmount`, and `FundReservation`), limits, and KYC status.
2. **Ledger Module**: Owns `LedgerTransaction` (Aggregate Root) and `LedgerEntry` (immutable child line items).
3. **Payment Module**: Owns `PaymentInstruction`, `Payment` orchestration.
4. **Network/Messaging Module**: Owns `PaymentMessage`, parsing ISO 20022.
5. **Routing Module**: Owns `Route` and `CorrespondentRelationship`.
6. **Settlement Module**: Owns `SettlementBatch` and `LiquidityObligation`.
7. **Compliance Module**: Owns `ComplianceCheck`, Sanctions lists.
8. **Operations/Audit Module**: Owns `AuditEvent`, `DisputeCase`.

---

## 11. Cross-Aggregate Interactions

How boundaries communicate conceptually:

* **Payment $\rightarrow$ Banking (Funds Reservation)**:
  * *Interaction*: Payment issues a synchronous command to `Banking` (`BankingService.reserveFunds`) to reserve funds on the customer's `Account`.
  * *Consistency*: Synchronous/Immediate. The Payment cannot proceed if available funds don't exist.
* **Payment / Settlement $\rightarrow$ Ledger (Accounting Posting)**:
  * *Interaction*: When settlement occurs (or a settled posting is reversed), the orchestrating module invokes `Ledger` (`LedgerService.postTransaction` / `reverseTransaction`) synchronously.
  * *Consistency*: Synchronous/Immediate within the local ACID transaction.
* **Payment $\rightarrow$ Compliance**: 
  * *Interaction*: Payment publishes `PaymentCreated` event. Compliance listens, checks rules, publishes `ComplianceCleared`.
  * *Consistency*: Eventual. Payment waits in `COMPLIANCE_HOLD` state.
* **Payment $\rightarrow$ Network**: 
  * *Interaction*: Payment commands Network to dispatch. Network responds asynchronously via events when ACK/NACK is received.

---

## 12. Unresolved Domain Questions
1. **Exchange Rates**: At what precise state transition is the FX rate locked for a cross-currency payment?
2. **Settlement Finality**: If a correspondent bank acknowledges a message but fails before settling the Nostro account, does the Payment aggregate transition to `FAILED` or `SETTLED_WITH_DISPUTE`?
