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

*   **Account (Banking Module)**: Protects account status (`ACTIVE`, `FROZEN`, `CLOSED`), operational balances (`available_balance`, `reserved_amount`), and `fund_reservation` child records.
*   **Payment (Payment Module)**: Protects state transitions (`VALIDATING` $\rightarrow$ `ROUTING` $\rightarrow$ `EXECUTING`).
*   **LedgerTransaction (Ledger Module)**: Protects the double-entry accounting invariants (`2..100` immutable `LedgerEntry` lines, single currency, positive unsigned `Money` amounts, explicit `DEBIT`/`CREDIT` directions, and `sum(DEBIT) == sum(CREDIT) == total_amount`) for each individual accounting transaction (ADR-006). (Note: Treating the entire Ledger as one global aggregate is an anti-pattern that creates a massive concurrency bottleneck).
*   **SettlementObligation (Settlement Module)**: Protects the inter-bank netting and liquidity state.

## C. Module Data Ownership
Each module explicitly owns its data. No module may directly `SELECT`, `INSERT`, `UPDATE`, or `DELETE` tables owned by another module.
*   **Banking**: Owns `customer`, `account`, and `fund_reservation` in the `banking` schema.
*   **Payment**: Owns `payment`, `payment_instruction` in the `payment` schema.
*   **Ledger**: Owns `ledger_transaction` and `ledger_entry` in the `ledger` schema (planned for Phase 3, ADR-006).
*   **Compliance**: Owns `compliance_review`, `sanctions_list` in the `compliance` schema.
*   **Routing**: Owns `correspondent_route` in the `routing` schema.
*   **Settlement**: Owns `settlement_obligation` in the `settlement` schema.
*   **Network/Messaging**: Owns `message_outbox`, `message_inbox` in the `messaging` schema.
*   **Operations/Audit**: Owns `audit_event`, `dispute_case` in the `operations` schema.

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
*   **No Cross-Module Foreign Keys**: Cross-module foreign keys are explicitly **prohibited**. Shared identifiers (e.g., `account_id` stored in `ledger.ledger_entry` or in the `payment` schema) are persisted as plain columns (`UUID`) without database-level FK constraints to `banking.account`.
*   **No Direct Access**: Modules must never execute queries against another schema.
*   **Enforcement**: The prohibition of cross-schema FKs is an architectural rule, not a PostgreSQL limitation. It preserves module independence. Referential integrity across boundaries is enforced by the application architecture and module APIs.

## F. Financial Persistence Model
*   **Ledger Balance / Accounting Truth**: Maintained exclusively in the `ledger` schema via immutable `ledger_transaction` and `ledger_entry` records (`NUMERIC(19,4)` positive unsigned amounts with explicit `DEBIT`/`CREDIT` directions).
*   **Reserved Amount & Available Balance**: Maintained in the `banking` schema on the `account` aggregate (`available_balance` and `reserved_amount`) and `fund_reservation` child table.
*   **Reservation Behavior**: Reserving funds decrements `available_balance` and increments `reserved_amount` in `banking`. It does *not* create a debit in the authoritative `ledger`.
*   **Settlement Accounting**: Only when accounting is posted does the `Ledger` module record the immutable double-entry `LedgerTransaction` (`ledger.ledger_transaction` + `ledger.ledger_entry`), and the `Banking` module consumes the reservation.
*   **Database-Level Ledger Integrity (Phase 3 Design, ADR-006)**:
    *   `ledger.ledger_transaction` enforces `posting_key VARCHAR(200) NOT NULL UNIQUE`, `external_reference VARCHAR(255) NOT NULL`, `description VARCHAR(500) NULL`, `reversed_transaction_id UUID NULL UNIQUE REFERENCES ledger.ledger_transaction(transaction_id)`, and `request_fingerprint CHAR(64) NOT NULL`.
    *   Two deferred constraint triggers (`DEFERRABLE INITIALLY DEFERRED` on `ledger_transaction` and `ledger_entry`) verify at commit time that every transaction has `2..100` entries, contiguous `1..N` line numbers, matching currency, `sum(DEBIT) == sum(CREDIT) == total_amount`, and valid reversal mirroring.
    *   Append-only triggers on `ledger_transaction` and `ledger_entry` reject normal SQL `UPDATE`, `DELETE`, and `TRUNCATE` operations.

## G. Balance Persistence Strategy
The operational `available_balance` and `reserved_amount` are maintained transactionally on the `Account` aggregate in the `banking` schema.
*   **Reservation Tracking**: The explicit lifecycle of each reservation is tracked via the `fund_reservation` child entity table, which is updated atomically with the `Account` balances.
*   **Authoritative Ledger Balance (Phase 3 Design, ADR-006)**: In the `ledger` schema, `debitTotal`, `creditTotal`, and `netBalance` (`creditTotal - debitTotal`) are computed on read from immutable `ledger_entry` rows using an index on `(account_id, currency)`. No separate balance-projection table is introduced in Phase 3.
*   *Why?* Separating operational balances in `Banking` from append-only accounting entries in `Ledger` keeps reservation authorization fast and isolated while preserving an immutable double-entry audit trail. *(Note: `BankingService.fundAccount` currently funds `banking.account.available_balance` for simulation bootstrap without posting a `LedgerTransaction`; reconciliation is deferred to a later phase).*

## H. Idempotency Persistence
CrossPayNet separates idempotency across distinct architectural layers:
1.  **External API Idempotency (Future Payment API)**: Managed via a dedicated `idempotency_record` table intercepting HTTP requests before they reach domain logic:
    *   **Scope**: `client_id` + `idempotency_key`.
    *   **State**: `PENDING`, `COMPLETED`, or `ERROR`.
    *   **Request Hash**: Stored to detect payload mismatches (returning `409 Conflict`).
    *   **Concurrent Duplicate**: If a record is `PENDING`, a concurrent identical request is rejected (`409 Conflict`).
    *   **Response Reference**: Stores the resulting **Payment reference ID** rather than a serialized HTTP response.
2.  **Banking Reservation Idempotency (Implemented in Phase 2)**: Enforced per account via `UNIQUE (account_id, payment_reference)` on `banking.fund_reservation` and domain checks in `Account.reserveFunds(...)`.
3.  **Ledger Posting & Reversal Idempotency (Phase 3 Design, ADR-006)**:
    *   Enforced via `posting_key VARCHAR(200) NOT NULL UNIQUE` and `request_fingerprint CHAR(64) NOT NULL` on `ledger.ledger_transaction`.
    *   Standard postings use a caller-supplied `LedgerPostingKey`; reversals deterministically derive `REVERSAL:{originalTransactionId}`.
    *   Keys are claimed using `INSERT INTO ledger.ledger_transaction ... ON CONFLICT (posting_key) DO NOTHING` so duplicate or conflicting requests never abort the active PostgreSQL transaction.
    *   Same `posting_key` + matching `request_fingerprint` returns `ALREADY_POSTED` with the existing transaction ID; same `posting_key` + different `request_fingerprint` is rejected as a conflict.

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
*   **Mechanism**: **One authoritative Flyway execution mechanism** deployed with the monolith (`classpath:db/migration`).
*   **Organization**: Migrations use a sequential integer version scheme (`V1`, `V2`, `V3`, ...) and are strictly append-only:
    *   **Implemented (Phase 2)**:
        *   `V1__init_system_event_registry.sql` (`system` schema and Spring Modulith `event_publication` table)
        *   `V2__init_banking_schema.sql` (`banking` schema)
        *   `V3__init_banking_tables.sql` (`banking.customer`, `banking.account`, `banking.fund_reservation`)
    *   **Planned (Phase 3 — Ledger Foundation, ADR-006)**:
        *   `V4__init_ledger_schema.sql` (`ledger` schema)
        *   `V5__init_ledger_tables.sql` (`ledger.ledger_transaction`, `ledger.ledger_entry`, constraints, and indexes)
        *   `V6__init_ledger_integrity_triggers.sql` (deferred double-entry/reversal constraint triggers and append-only immutability triggers)
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
