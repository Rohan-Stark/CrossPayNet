# Persistence Architecture

This document defines the persistence architecture for CrossPayNet, an Evolutionary Modular Monolith relying on a single PostgreSQL 18 instance. It explicitly defines how the domain model maps to relational persistence while strictly preserving modular boundaries and financial correctness.

*Note: This is a conceptual architecture. No implementation code or SQL is present in Phase 1.*

---

## A. Persistence Objectives
*   **Financial Correctness**: All monetary amounts must use exact precision types (e.g., `NUMERIC`).
*   **Immutability**: True financial history (the Ledger) is append-only. Settled records are never rewritten.
*   **Consistency**: Strict transactional boundaries protect domain invariants without requiring distributed transaction coordinators (2PC).
*   **Module Independence**: Persistence boundaries reflect the software modules, preparing the system for safe future extraction.

## B. Aggregate Persistence Boundaries
An aggregate is a consistency boundary. It determines what must be persisted together transactionally.

*   **Account (Banking Module)**: Protects account status and operational limits.
*   **Payment (Payment Module)**: Protects state transitions (`VALIDATING` $\rightarrow$ `ROUTING` $\rightarrow$ `EXECUTING`).
*   **LedgerTransaction (Ledger Module)**: Protects the accounting invariant that `sum(debits) = sum(credits)` for any given double-entry transaction. (Note: Treating the entire Ledger as one aggregate is an anti-pattern that creates a massive concurrency bottleneck).
*   **SettlementObligation (Settlement Module)**: Protects the inter-bank netting and liquidity state.

## C. Module Data Ownership
Each module explicitly owns its data. No module may directly `SELECT`, `INSERT`, `UPDATE`, or `DELETE` tables owned by another module.
*   **Banking**: Owns `customer`, `account` (and operational balances).
*   **Payment**: Owns `payment`, `payment_instruction`.
*   **Ledger**: Owns `ledger_transaction`, `ledger_entry`.
*   **Compliance**: Owns `compliance_review`, `sanctions_list`.
*   **Routing**: Owns `correspondent_route`.
*   **Settlement**: Owns `settlement_obligation`.
*   **Network/Messaging**: Owns `message_outbox`, `message_inbox`.
*   **Operations/Audit**: Owns `audit_event`, `dispute_case`.

## D. PostgreSQL Schema-Per-Module Strategy
CrossPayNet utilizes a **schema-per-module** strategy within the single PostgreSQL database. 

Example conceptual schemas:
*   `banking`
*   `payment`
*   `ledger`
*   `compliance`
*   `routing`
*   `settlement`
*   `messaging`
*   `operations`

*Reasoning*: Multiple schemas within the same database can safely participate in the same local ACID transaction. This provides strong physical isolation, makes cross-domain database joins obviously incorrect during code review, and drastically simplifies future selective module extraction compared to using a single `public` schema.

## E. Cross-Module Database Rules
*   **No Cross-Module Foreign Keys**: Cross-module foreign keys are explicitly **prohibited**. Shared identifiers (e.g., `account_id` stored in the `payment` schema) are persisted as plain columns without database-level FK constraints. 
*   **No Direct Access**: Modules must never execute queries against another schema.
*   **Enforcement**: The prohibition of cross-schema FKs is an architectural rule, not a PostgreSQL limitation. It preserves module independence. Referential integrity across boundaries is enforced by the application architecture and module APIs.

## F. Financial Persistence Model
*   **Ledger Balance / Accounting Truth**: Maintained exclusively in the `ledger` schema via immutable `ledger_entry` records.
*   **Reserved Amount & Available Balance**: Maintained in the `banking` schema on the `account` aggregate. 
*   **Reservation Behavior**: Reserving funds decrements the `available_balance` and increments the `reserved_amount`. It does *not* create a debit in the authoritative ledger. 
*   **Settlement Accounting**: Only when final settlement occurs does the Ledger record the actual, immutable double-entry movement, and the Banking module clears the reservation.

## G. Balance Persistence Strategy
The `available_balance` and `reserved_amount` are maintained transactionally on the `Account` aggregate in the `banking` schema.
*   *Why?* Calculating the available balance dynamically by summing millions of historical `LedgerEntry` records on every payment authorization is a massive performance bottleneck. The `Account` provides a fast, transactionally safe operational cache for authorization, while the `Ledger` remains the ultimate authoritative truth for actual settled accounting.

## H. Idempotency Persistence
Idempotency state is managed via a dedicated `idempotency_record` table intercepting requests before they reach the domain logic. This prevents duplicate financial effects.
*   **Scope**: `client_id` + `idempotency_key`.
*   **State**: `PENDING`, `COMPLETED`, or `ERROR`.
*   **Request Hash**: Stored to detect payload mismatches (returning `409 Conflict`).
*   **Concurrent Duplicate**: If a record is `PENDING`, a concurrent identical request is rejected (`409 Conflict`).
*   **Response Reference**: The record stores the resulting **Payment reference ID**, rather than a serialized HTTP response. This allows the API to gracefully handle a later retry by fetching the exact, current state of the Payment from the domain, rather than returning stale cached data.

## I. Transaction Boundaries
*   **Payment Creation + Successful Reservation**: Executed as **one local ACID transaction**. The orchestration logic begins a transaction, creates the Payment (in `payment` schema), and reserves funds (in `banking` schema). Both commit together.
*   **Workflow-Critical Durable Event Publication**: The business state change and its durable event-publication record must participate in the same local database transaction where appropriate.
*   **Failed Reservation**: If the reservation fails (e.g., insufficient funds), the orchestration logic catches the failure, transitions the Payment to `REJECTED`, and commits. No reservation remains. The Ledger is unchanged. (Rolling back the transaction entirely is rejected, as it destroys the audit trail of the failed attempt).
*   **Asynchronous Operations**: Compliance, Network dispatch, and Settlement occur in separate, subsequent transactions triggered by domain events.

## J. JPA/Hibernate Architectural Guidance (Conceptual)
*   **Mapping**: Aggregates (e.g., `Payment`, `Account`) are mapped as `@Entity`. Concepts lacking identity (e.g., `Money` containing amount/currency) are mapped as `@Embeddable`.
*   **Concurrency**: Mutable aggregates use `@Version` for Optimistic Locking to prevent lost updates.
*   **Isolation**: JPA Entities must remain hidden within their owning module's implementation layer. They do not cross API boundaries (DTOs are used instead).

## K. Flyway Strategy
*   **Mechanism**: **One authoritative Flyway execution mechanism** deployed with the monolith.
*   **Organization**: Migrations are strictly ordered, append-only, and module-oriented in naming (e.g., `V1.1__bnk_create_account.sql`, `V1.2__ldg_create_transaction.sql`).
*   **Ordering**: A single execution engine ensures deterministic ordering and safety during application startup.

## L. Shared Reference Data
Read-only concepts such as Currency definitions (ISO 4217) and Country codes are managed via a **Shared Kernel**. Other modules store the primitive reference (e.g., a `String` currency code) and rely on the Shared Kernel API for validation, eliminating the need to duplicate reference tables across all schemas.

## M. Failure / Recovery Considerations
*   **Transaction Rollback**: Utilized only for internal structural failures. Business failures (e.g., insufficient funds) result in explicitly committed `REJECTED` states.
*   **Settlement Failure (Before Accounting)**: If a reservation exists but the ledger accounting has NOT yet posted, a failure requires releasing the Banking reservation and restoring the available balance. The Ledger remains unchanged, and no compensating ledger transaction is required.
*   **Settlement Failure (After Accounting)**: If a ledger accounting transaction has already been posted and later requires reversal or correction, an explicit *compensating accounting transaction* must be used. Historical ledger entries are never modified silently.

## N. Future Evolution
This persistence architecture perfectly positions CrossPayNet for future scale:
*   **Selective Module Extraction**: Schema-per-module and the prohibition of cross-module FKs mean a module and its data can be extracted into a separate microservice with minimal refactoring.
*   **Distributed Processing & Kafka**: Domain events (currently Spring Application Events) can later be published to Kafka using the Transactional Outbox pattern, which is easily supported by the local PostgreSQL transactions defined here.
