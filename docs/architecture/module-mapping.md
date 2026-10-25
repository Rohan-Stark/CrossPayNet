# Logical Module Mapping

This document maps the business domains of CrossPayNet into specific, isolated software modules within the Spring Boot monolith. 

## 1. Aggregate vs. Module Distinction

* **Aggregate**: A domain consistency boundary. It guarantees that a specific set of domain objects change state together atomically to protect a business invariant (e.g., `LedgerTransaction` ensures `sum(DEBIT) == sum(CREDIT) == totalAmount` for an individual accounting transaction).
* **Module**: A software responsibility and dependency boundary. A module may contain multiple aggregates, application services, and infrastructure components that share a high level of cohesion.

---

## 2. Defined Modules

### 2.1 Banking Module
* **Responsibility**: Manages the identities, accounts, operational balances (`available_balance`, `reserved_amount`), fund reservations, and limits of participants.
* **Owns Domain Concepts**: `Bank`, `Customer`, `Account` (Aggregate Root), `FundReservation`.
* **Owns Business Rules**: Account status checks (`ACTIVE`, `FROZEN`, `CLOSED`), operational funds reservation and release, simulator bootstrap funding.
* **Owns Data**: Customer profiles, Account definitions, operational balances, and `fund_reservation` records in the `banking` schema.
* **Exposes Capabilities**: Synchronous API (`BankingService`) to reserve funds and fund accounts operationally, plus the `FundsReserved` business event.
* **Consumes Capabilities**: None fundamentally (uses Shared Kernel `common.money`).

### 2.2 Ledger Module
* **Responsibility**: The authoritative double-entry accounting engine ensuring mathematical balance and immutable financial history (planned for Phase 3, ADR-006).
* **Owns Domain Concepts**: `LedgerTransaction` (Aggregate Root), `LedgerEntry` (immutable child line item). There is no single global `Ledger` aggregate root.
* **Owns Business Rules**: Double-entry accounting invariants (`2..100` entries per transaction, positive unsigned `Money` amounts, explicit `DEBIT`/`CREDIT` directions, single currency per transaction, `sum(DEBIT) == sum(CREDIT) == totalAmount`, append-only immutability, full reversal rules, and posting-key idempotency).
* **Owns Data**: Immutable `ledger_transaction` and `ledger_entry` records in the `ledger` schema. Does not own operational fund reservations.
* **Exposes Capabilities**: Synchronous internal API (`LedgerService`: `postTransaction`, `reverseTransaction`, `getBalance`, `findTransaction`).
* **Consumes Capabilities**: None fundamentally (uses Shared Kernel `common.money`; references accounts by plain `accountType` and `accountReference` without depending on `Banking`).

### 2.3 Payment Module
* **Responsibility**: Orchestrates the cross-border payment lifecycle and enforces the Financial Commitment Point (`ROUTING -> EXECUTING`).
* **Owns Domain Concepts**: `Payment` (Aggregate Root), `PaymentInstruction`, `InstructionFingerprint`, `PaymentLimits`, `PaymentSupportedCurrencyPolicy` (`com.crosspaynet.payment.domain`).
* **Owns Business Rules**: Nine-state payment state machine (`VALIDATING`, `COMPLIANCE_HOLD`, `ROUTING`, `EXECUTING`, `PENDING_EXTERNAL`, `SETTLED`, `REJECTED`, `CANCELLED`, `FAILED`), SHA-256 instruction fingerprinting, pre-commitment vs. post-commitment transition rules, and atomic reservation release on pre-commitment cancellation/rejection and confirmed post-commitment pre-settlement failure.
* **Owns Data**: `payment.payment` records in the `payment` schema (`V7`–`V9`), mapped via `PaymentEntity`, `PaymentMapper`, and `PaymentRepository` (`com.crosspaynet.payment.infrastructure.persistence`).
* **Exposes Capabilities**:
  * Root application service `PaymentService` (`com.crosspaynet.payment.PaymentService`): `initiatePayment`, `approveCompliance`, `commitToExecution`, `markDispatched`, `cancelPayment`, `rejectPayment`, `failPayment`, `recordSettlement`, `findPayment`, and `findBySourceAccountAndReference`.
  * Narrow public contracts in `com.crosspaynet.payment.contracts` (`@NamedInterface("contracts")`) and settlement port `PaymentSettlementPort` in `com.crosspaynet.payment.settlement` (`@NamedInterface("settlement")`).
* **Consumes Capabilities**: `Banking` (`BankingService.reserveFunds` and `BankingService.releaseReservation` via `banking` and `banking :: contracts`) and Shared Kernel (`common`, `common :: money`). Does **not** depend on `Ledger` or call `BankingService.consumeReservation` (owned by the future `Settlement` module).

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
* **Responsibility**: Coordinates the atomic financial settlement of dispatched (`PENDING_EXTERNAL`) payments across the `Banking`, `Ledger`, and `Payment` modules without duplicating state already owned by those aggregates.
* **Owns Domain Concepts**: Settlement orchestration contracts (`SettlePaymentCommand`, `SettlementResult`, `SettlementException`, `SettlementValidationException`, `SettlementConflictException` in `com.crosspaynet.settlement.contracts`) and deterministic settlement posting key derivation (`PAYMENT_SETTLEMENT:{paymentId}`).
* **Owns Business Rules**: Requires an explicit caller-supplied `INTERNAL` clearing account reference (`clearingAccountReference`) and confirmed external settlement acknowledgment reference (`settlementReference`) rather than inferring accounting targets from `routeReference`; enforces atomic consumption of the operational Banking reservation (`ACTIVE -> CONSUMED`), single-currency double-entry Ledger posting (`DEBIT` `BANKING_ACCOUNT` `sourceAccountId`, `CREDIT` `INTERNAL` `clearingAccountReference`), and Payment settlement state recording (`PENDING_EXTERNAL -> SETTLED`); and verifies fingerprint/transaction-ID consistency on idempotent replays of already-`SETTLED` payments.
* **Owns Data**: Stateless orchestrator in the current checkpoint; persists durable state through `banking.fund_reservation`, `ledger.ledger_transaction` / `ledger.ledger_entry`, and `payment.payment` within a single shared database transaction.
* **Exposes Capabilities**:
  * Root application service `SettlementService` (`com.crosspaynet.settlement.SettlementService`): `settlePayment(SettlePaymentCommand)` and `postingKeyFor(PaymentId)`.
  * Narrow public contracts in `com.crosspaynet.settlement.contracts` (`@NamedInterface("contracts")`).
* **Consumes Capabilities**: `Banking` (`BankingService.consumeReservation` via `banking` and `banking :: contracts`), `Ledger` (`LedgerService.postTransaction` via `ledger`), `Payment` (`PaymentService.findPayment` and `PaymentSettlementPort.recordSettlement` via `payment`, `payment :: contracts`, and `payment :: settlement`), and Shared Kernel (`common`, `common :: money`).

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
