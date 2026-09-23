# Logical Module Mapping

This document maps the business domains of CrossPayNet into specific, isolated software modules within the Spring Boot monolith. 

## 1. Aggregate vs. Module Distinction

* **Aggregate**: A domain consistency boundary. It guarantees that a specific set of domain objects change state together atomically to protect a business invariant (e.g., Ledger Aggregate ensures debits = credits).
* **Module**: A software responsibility and dependency boundary. A module may contain multiple aggregates, application services, and infrastructure components that share a high level of cohesion.

---

## 2. Defined Modules

### 2.1 Banking Module
* **Responsibility**: Manages the identities, accounts, and limits of participants.
* **Owns Domain Concepts**: `Bank`, `Customer`, `Account` (Aggregate).
* **Owns Business Rules**: Account limits, KYC status checks, Account freezing.
* **Owns Data**: Customer profiles, Account definitions.
* **Exposes Capabilities**: Synchronous API to verify account status and available balance.
* **Consumes Capabilities**: None fundamentally.

### 2.2 Ledger Module
* **Responsibility**: The authoritative accounting engine ensuring mathematical balance.
* **Owns Domain Concepts**: `Ledger` (Aggregate), `LedgerEntry`, `LedgerTransaction`.
* **Owns Business Rules**: Double-entry accounting invariant (Debits = Credits).
* **Owns Data**: Immutable financial history.
* **Exposes Capabilities**: Synchronous commands to post transactions or reserve funds.
* **Consumes Capabilities**: None fundamentally.

### 2.3 Payment Module
* **Responsibility**: Orchestrates the end-to-end transfer of value.
* **Owns Domain Concepts**: `PaymentInstruction`, `Payment` (Aggregate).
* **Owns Business Rules**: Payment state machine transitions, validation of payment intent.
* **Owns Data**: Payment status, history, and instructions.
* **Exposes Capabilities**: Public API for customers to initiate/track payments; events for state changes.
* **Consumes Capabilities**: `Banking` (verify accounts), `Ledger` (reserve funds), `Routing` (get route), `Compliance` (async evaluation), `Messaging` (dispatch).

### 2.4 Compliance Module
* **Responsibility**: Evaluates entities and payments against regulatory rules.
* **Owns Domain Concepts**: `ComplianceCheck`, `RiskAssessment`, Sanctions Lists.
* **Owns Business Rules**: List matching algorithms, risk scoring thresholds.
* **Owns Data**: Compliance results, rule definitions.
* **Exposes Capabilities**: Async events (`ComplianceCleared`, `ComplianceRejected`).
* **Consumes Capabilities**: Listens to `PaymentCreated` events from the Payment module.

### 2.5 Routing Module
* **Responsibility**: Determines the path used to move a payment across the network.
* **Owns Domain Concepts**: `Route`, `CorrespondentRelationship`.
* **Owns Business Rules**: Route calculation, relationship validation, currency support checks.
* **Owns Data**: Correspondent relationship definitions and topology.
* **Exposes Capabilities**: Synchronous API to calculate the optimal route for a payment.
* **Consumes Capabilities**: None fundamentally.

### 2.6 Settlement Module
* **Responsibility**: Manages the financial obligation and liquidity settlement process between banks, distinct from the routing path or the authoritative ledger math.
* **Owns Domain Concepts**: `SettlementBatch`, `LiquidityObligation`.
* **Owns Business Rules**: Netting rules, Nostro/Vostro account reconciliation logic.
* **Owns Data**: Settlement status, obligation records.
* **Exposes Capabilities**: APIs/Events for settling aggregated obligations.
* **Consumes Capabilities**: `Ledger` (to record the actual settlement entries), `Routing` (to know the relationship context).

### 2.7 Network/Messaging Module
* **Responsibility**: Translates domain intents into physical wire artifacts and manages network transmission.
* **Owns Domain Concepts**: `PaymentMessage` (e.g., ISO 20022 `pacs.008`).
* **Owns Business Rules**: Message formatting, network retries, parsing ACKs/NACKs.
* **Owns Data**: Raw message payloads, transmission logs.
* **Exposes Capabilities**: Async command to dispatch a message; Async events for delivery status.
* **Consumes Capabilities**: None (acts on commands from `Payment`).

### 2.8 Operations/Audit Module
* **Responsibility**: Provides visibility and dispute resolution tools for bank staff.
* **Owns Domain Concepts**: `AuditEvent`, `DisputeCase`.
* **Owns Business Rules**: Audit trailing, timeline aggregation.
* **Owns Data**: Audit logs.
* **Exposes Capabilities**: Read-only APIs for the frontend dashboard.
* **Consumes Capabilities**: Listens to domain events from all other modules; may utilize specialized cross-domain read models.

---

## 3. Disambiguation: Routing vs. Settlement vs. Ledger

To ensure clarity, these concepts are explicitly separated into distinct modules:
* **Routing Module** knows *who* we have relationships with and *how* to get a message to the destination. It does not move money.
* **Settlement Module** knows *when* and *how much* liquidity actually changes hands to satisfy obligations created by payments. It orchestrates the financial netting.
* **Ledger Module** knows the *math*. It executes the authoritative accounting entries dictated by the Settlement module.
