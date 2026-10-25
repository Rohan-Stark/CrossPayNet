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
*   **Payment (Payment Module)**: Protects payment instruction immutability, deterministic SHA-256 instruction fingerprinting, the nine-state workflow lifecycle (`VALIDATING`, `COMPLIANCE_HOLD`, `ROUTING`, `EXECUTING`, `PENDING_EXTERNAL`, `SETTLED`, `REJECTED`, `CANCELLED`, `FAILED`), the Financial Commitment Point (`ROUTING -> EXECUTING`), and state-specific correlation and reason-code invariants.
*   **LedgerTransaction (Ledger Module)**: Protects the double-entry accounting invariants (`2..100` immutable `LedgerEntry` lines, single currency, positive unsigned `Money` amounts, explicit `DEBIT`/`CREDIT` directions, and `sum(DEBIT) == sum(CREDIT) == total_amount`) for each individual accounting transaction (ADR-006). (Note: Treating the entire Ledger as one global aggregate is an anti-pattern that creates a massive concurrency bottleneck).
*   **SettlementObligation (Settlement Module)**: Protects the inter-bank netting and liquidity state.

## C. Module Data Ownership
Each module explicitly owns its data. No module may directly `SELECT`, `INSERT`, `UPDATE`, or `DELETE` tables owned by another module.
*   **Banking**: Owns `customer`, `account`, and `fund_reservation` in the `banking` schema.
*   **Payment**: Owns `payment` in the `payment` schema (`V7`–`V9`).
*   **Ledger**: Owns `ledger_transaction` and `ledger_entry` in the `ledger` schema (ADR-006, `V4`–`V6`).
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
*   **No Cross-Module Foreign Keys**: Cross-module foreign keys are explicitly **prohibited**. Shared identifiers (e.g., `account_type` and `account_reference` stored in `ledger.ledger_entry`, or account identifiers in the `payment` schema) are persisted as plain columns without database-level FK constraints to `banking.account`.
*   **No Direct Access**: Modules must never execute queries against another schema.
*   **Enforcement**: The prohibition of cross-schema FKs is an architectural rule, not a PostgreSQL limitation. It preserves module independence. Referential integrity across boundaries is enforced by the application architecture and module APIs.

## F. Financial Persistence Model
*   **Ledger Balance / Accounting Truth**: Maintained exclusively in the `ledger` schema via immutable `ledger_transaction` and `ledger_entry` records (positive unsigned `NUMERIC` amounts constrained to `<= 999999999999999.9999` and `scale(trim_scale(...)) <= 4` with explicit `DEBIT`/`CREDIT` directions).
*   **Reserved Amount & Available Balance**: Maintained in the `banking` schema on the `account` aggregate (`available_balance` and `reserved_amount`) and `fund_reservation` child table.
*   **Reservation Behavior**: Reserving funds decrements `available_balance` and increments `reserved_amount` in `banking`. It does *not* create a debit in the authoritative `ledger`.
*   **Settlement Accounting**: Only when accounting is posted does the `Ledger` module record the immutable double-entry `LedgerTransaction` (`ledger.ledger_transaction` + `ledger.ledger_entry`), and the `Banking` module consumes the reservation.
*   **Database-Level Ledger Integrity (Phase 3 Design, ADR-006)**:
    *   `ledger.ledger_transaction` enforces `posting_key VARCHAR(200) NOT NULL UNIQUE` (matching `LedgerLimits.MAX_POSTING_KEY_LENGTH = 200` and syntax `[A-Za-z0-9:_.-]{1,200}`), `total_amount NUMERIC NOT NULL` constrained by `chk_ledger_tx_total_amount` (`total_amount > 0 AND total_amount <= 999999999999999.9999 AND scale(trim_scale(total_amount)) <= 4`), `external_reference VARCHAR(255) NOT NULL` (`MAX_EXTERNAL_REFERENCE_LENGTH = 255`), `description VARCHAR(500) NULL` (`MAX_DESCRIPTION_LENGTH = 500`), `reversed_transaction_id UUID NULL UNIQUE REFERENCES ledger.ledger_transaction(transaction_id)`, and `request_fingerprint CHAR(64) NOT NULL` (versioned length-prefixed UTF-8 SHA-256 digest `crosspaynet-ledger-fingerprint-v1`).
    *   `ledger.ledger_entry` stores `account_type` (`BANKING_ACCOUNT` or `INTERNAL`), `account_reference` (`1..100` Unicode code points with no ISO control characters, matching `LedgerLimits.MAX_ACCOUNT_REFERENCE_LENGTH = 100`), `direction` (`DEBIT` or `CREDIT`), `currency` (`VARCHAR(3)`), and `amount NUMERIC NOT NULL` constrained by `chk_ledger_entry_amount` (`amount > 0 AND amount <= 999999999999999.9999 AND scale(trim_scale(amount)) <= 4`). Using unconstrained `NUMERIC NOT NULL` with `scale(trim_scale(...)) <= 4` preserves the accepted monetary range and no-silent-rounding requirement by ignoring insignificant trailing zeros while rejecting inputs with more than four meaningful fractional decimal places (avoiding PostgreSQL `NUMERIC(19,4)` input coercion rounding). JPA entity mappings and SQL constraints will be checked against the Java `Money` implementation and verified using PostgreSQL 18 integration tests in `CP3`.
    *   Two deferred constraint triggers (`DEFERRABLE INITIALLY DEFERRED` on `ledger_transaction` and `ledger_entry`) independently verify at commit time (as defense in depth alongside pure Java domain validation):
        1. Every transaction has `2..100` entries matching `entry_count`, distinct contiguous `1..entry_count` line numbers, matching entry and header `currency`, and `sum(DEBIT) == sum(CREDIT) == total_amount`.
        2. For `REVERSAL` transactions: the referenced `reversed_transaction_id` exists and is a `STANDARD` transaction (`transaction_type = 'STANDARD'` and `reversed_transaction_id IS NULL`); original and reversal `currency`, `total_amount`, and `entry_count` match; every original entry has **exactly one** matching reversal entry with the same `account_type`, `account_reference`, `currency`, and `amount` and with `DEBIT` and `CREDIT` directions swapped (verified via a `FULL OUTER JOIN` comparing grouped `(account_type, account_reference, currency, amount, direction)` tuple multiplicities); and no extra or missing reversal entries exist.
        3. Phase 3 (`CP3`) PostgreSQL integration tests will include direct-SQL test cases bypassing domain validation to verify that the deferred triggers reject zero-entry headers, unbalanced lines, and malformed or mismatched reversal entries.
    *   Append-only triggers on `ledger_transaction` and `ledger_entry` reject normal SQL `UPDATE`, `DELETE`, and `TRUNCATE` operations.
*   **Database-Level Payment State & Unicode Length Integrity (Phase 4 CP2)**:
    *   `payment.payment` enforces `uk_payment_source_account_ref UNIQUE (source_account_id, payment_reference)`, unconstrained `amount NUMERIC NOT NULL` guarded by `chk_payment_amount` (`amount > 0 AND amount <= 999999999999999.9999 AND scale(trim_scale(amount)) <= 4`), `chk_payment_currency_supported`, `chk_payment_beneficiary_bic`, and `chk_payment_state_invariants` across all nine `PaymentStatus` values.
    *   **PostgreSQL Three-Valued Logic (`UNKNOWN`) Safeguard**: Because PostgreSQL `CHECK` constraints pass when a boolean expression evaluates to `UNKNOWN` (`NULL`), every `REJECTED`, `CANCELLED`, and `FAILED` branch in `chk_payment_state_invariants` explicitly requires `status_reason_code IS NOT NULL` before evaluating `status_reason_code IN (...)` or `status_reason_code = 'CUSTOMER_CANCELLED'`.
    *   **Java UTF-16 vs. PostgreSQL Unicode Code-Point Length Alignment (`V9__align_payment_unicode_length_constraints.sql`)**: Java domain value objects (`PaymentReference`, `BeneficiaryDetails`, `Payment`) validate maximum text lengths using `String.length()`, which counts **UTF-16 code units** (where each supplementary Unicode character in `U+10000..U+10FFFF` occupies a 2-unit surrogate pair). By contrast, PostgreSQL `VARCHAR(N)` and `char_length(...)` count **Unicode code points** (`1` per supplementary character). Without an explicit UTF-16 length check in SQL, direct SQL could insert a row containing supplementary characters whose code-point count is `<= N` but whose UTF-16 length exceeds `N`, causing `PaymentMapper.toDomain(...)` to fail with `PersistedPaymentStateException` on read. Migration `V9` aligns `chk_payment_reference_non_blank` (`<= 255`), `chk_payment_beneficiary_name` (`<= 140`), `chk_payment_beneficiary_account` (`<= 34`), `chk_payment_route_reference` (`<= 100`), `chk_payment_external_dispatch_reference` (`<= 100`), and `chk_payment_reason_detail` (`<= 500`) using `(char_length(col) + regexp_count(col, '[\U00010000-\U0010FFFF]')) <= N`, ensuring PostgreSQL enforces the exact same UTF-16 length ceiling as the Java domain.

## G. Balance Persistence Strategy
The operational `available_balance` and `reserved_amount` are maintained transactionally on the `Account` aggregate in the `banking` schema.
*   **Reservation Tracking**: The explicit lifecycle of each reservation is tracked via the `fund_reservation` child entity table, which is updated atomically with the `Account` balances.
*   **Authoritative Ledger Balance (Phase 3 Design, ADR-006)**: In the `ledger` schema, `debitTotal`, `creditTotal`, and signed `netBalance` (`debitTotal - creditTotal`) are computed on read from immutable `ledger_entry` rows using an index on `(account_type, account_reference, currency)`. `netBalance = debitTotal - creditTotal` is a Phase 3 signed calculation convention; without a chart-of-accounts model, it must not be presented as the universal normal balance of every account. No separate balance-projection table is introduced in Phase 3.
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
3.  **Ledger Posting & Reversal Idempotency (Implemented in Phase 3, ADR-006)**:
    *   Enforced via `posting_key VARCHAR(200) NOT NULL UNIQUE` and `request_fingerprint CHAR(64) NOT NULL` on `ledger.ledger_transaction`.
    *   Standard postings use a caller-supplied `LedgerPostingKey`; reversals deterministically derive `REVERSAL:{originalTransactionId}`.
    *   Keys are claimed using `INSERT INTO ledger.ledger_transaction ... ON CONFLICT (posting_key) DO NOTHING` so duplicate or conflicting requests never abort the active PostgreSQL transaction.
    *   Same `posting_key` + matching `request_fingerprint` returns `ALREADY_POSTED` with the existing transaction ID; same `posting_key` + different `request_fingerprint` is rejected as a conflict.
4.  **Payment Instruction Idempotency (Phase 4)**:
    *   Enforced per source account via `uk_payment_source_account_ref UNIQUE (source_account_id, payment_reference)` and `instruction_fingerprint CHAR(64) NOT NULL` (`crosspaynet-payment-fingerprint-v1`) on `payment.payment`.
    *   Claimed via `INSERT INTO payment.payment ... ON CONFLICT (source_account_id, payment_reference) DO NOTHING` so duplicate or conflicting claims never abort the active PostgreSQL transaction (`25P02`).

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
    *   **Implemented (Phase 2 — Banking Foundation)**:
        *   `V1__init_system_event_registry.sql` (`system` schema and Spring Modulith `event_publication` table)
        *   `V2__init_banking_schema.sql` (`banking` schema)
        *   `V3__init_banking_tables.sql` (`banking.customer`, `banking.account`, `banking.fund_reservation`)
    *   **Implemented (Phase 3 — Ledger Foundation, ADR-006)**:
        *   `V4__init_ledger_schema.sql` (`ledger` schema)
        *   `V5__init_ledger_tables.sql` (`ledger.ledger_transaction`, `ledger.ledger_entry`, constraints, and indexes)
        *   `V6__init_ledger_integrity_triggers.sql` (deferred double-entry/reversal constraint triggers and append-only immutability triggers)
    *   **Implemented (Phase 4 CP2 — Payment Persistence)**:
        *   `V7__init_payment_schema.sql` (`payment` schema)
        *   `V8__init_payment_tables.sql` (`payment.payment`, state-invariant constraints, and operational indexes)
        *   `V9__align_payment_unicode_length_constraints.sql` (aligns `payment.payment` text `CHECK` constraints with Java UTF-16 `String.length()` for supplementary Unicode characters)
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
