# ADR-006: Double-Entry Financial Ledger Foundation

## Status
**Accepted**

## Date
2026-10-20

## Context
CrossPayNet is an evolutionary modular monolith simulating cross-border payment messaging, accounting, and interbank settlement (ADR-001, ADR-002, ADR-003). In Phase 2, the `Banking` module was implemented to manage customer accounts, operational balances (`available_balance` and `reserved_amount`), and fund reservations (`fund_reservation`).

Phase 3 establishes the architectural foundation for the **Double-Entry Financial Ledger** (`Ledger` module). While the `Banking` module handles operational authorization and temporary holds, the `Ledger` module is responsible for recording the authoritative, append-only accounting truth once financial movements are posted or reversed. Before writing any Phase 3 implementation code, we must formally lock the domain boundaries, aggregate design, idempotency semantics, reversal rules, database-level integrity safeguards, transaction propagation rules, and balance calculation strategy, and resolve earlier Phase 1 documentation inconsistencies.

## Problem
How should the `Ledger` module be modeled, persisted, and invoked inside the modular monolith so that:
1. Double-entry accounting invariants are strictly enforced in both pure Java domain logic and PostgreSQL storage without creating a global concurrency bottleneck?
2. Duplicate or retried posting and reversal commands are handled idempotently without relying on catching uniqueness exceptions that abort the active PostgreSQL transaction?
3. Posted accounting records remain immutable and auditable, with corrections handled exclusively via full reversal transactions?
4. Module boundaries between `Banking`, `Ledger`, `Payment`, and `Settlement` remain strictly separated with zero cross-module foreign keys?

## Decision

### 1. Aggregate Root and Pure Domain Model
*   **Aggregate Root (`LedgerTransaction`)**: `LedgerTransaction` is the aggregate root of the `Ledger` module. `LedgerEntry` is an immutable child entity owned by `LedgerTransaction`. There is **no** global `Ledger` aggregate root.
*   **Pure Java Domain**: `com.crosspaynet.ledger.domain` must remain completely independent of Spring, JPA, Hibernate, and JDBC.
*   **No Persisted Draft State**: Transactions are constructed and validated in memory via domain factory methods (`LedgerTransaction.post(...)` and `LedgerTransaction.reverse(...)`) so that only complete, balanced transactions are ever persisted.
*   **Domain and Accounting Invariants**:
    *   **Entry Count**: Every posted transaction must contain at least `2` and at most `100` entries (`2 <= entries.size() <= 100`).
    *   **Unsigned Positive Amounts + Explicit Direction**: Each `LedgerEntry` holds a strictly positive, unsigned `Money` amount (`amount > 0`, scale `<= 4`, within `NUMERIC(19,4)` bounds) and an explicit `EntryDirection` (`DEBIT` or `CREDIT`). Signed entry amounts are not used.
    *   **Single Currency**: Every entry in a `LedgerTransaction` must match the transaction's single ISO 4217 `Currency`. Multi-currency / FX postings are out of scope for Phase 3.
    *   **Double-Entry Balance**: The sum of all `DEBIT` entry amounts must strictly equal the sum of all `CREDIT` entry amounts, and both must equal the transaction header's declared `totalAmount` (`sum(DEBIT) == sum(CREDIT) == totalAmount`).
    *   **Deterministic Line Numbers**: The domain assigns sequential 1-based line numbers (`1..N`) to entries in a deterministic order.
    *   **Immutable History**: Once posted, a `LedgerTransaction` and its `LedgerEntry` lines are never updated or deleted.

### 2. Internal Module Capability and Posting Idempotency
*   **Public Module Contract (`LedgerService`)**: The `Ledger` module exposes a synchronous internal application service (`LedgerService`) with four planned operations for Phase 3: `postTransaction`, `reverseTransaction`, `getBalance`, and `findTransaction`. No REST controller or external HTTP endpoint is introduced in Phase 3.
*   **Posting Key (`LedgerPostingKey`)**: Standard postings require a caller-supplied `LedgerPostingKey` (non-blank, trimmed length `1..200` characters, matching `VARCHAR(200)` in PostgreSQL).
*   **Separation of Idempotency Concerns**: Ledger posting idempotency (`LedgerPostingKey`) is distinct from external HTTP API idempotency (`Idempotency-Key`), `Banking` fund-reservation idempotency (`(account_id, payment_reference)`), and future network-message deduplication.
*   **Deterministic Financial Fingerprint (`request_fingerprint`)**: Ledger computes a 64-character lowercase hex SHA-256 digest over the financially significant fields of the request: `transactionType`, `currency`, `totalAmount`, `externalReference`, `reversedTransactionId` (when present), and the multiset of `(accountId, direction, amount)` entry tuples canonicalized independently of caller input list order. Descriptive metadata (`description` / reversal `reason`) is excluded from the fingerprint.
*   **PostgreSQL Conflict-Safe Claim (`INSERT ... ON CONFLICT DO NOTHING`)**:
    *   Because an uncaught uniqueness violation in PostgreSQL marks the current database transaction as aborted (`25P02`), `LedgerService` must **not** use a failing `INSERT` + exception catch as a recoverable business flow.
    *   Instead, after pure domain validation succeeds, the persistence layer attempts to insert the transaction header using `INSERT INTO ledger.ledger_transaction (...) VALUES (...) ON CONFLICT (posting_key) DO NOTHING`.
    *   **Row inserted (`1` row affected)**: The posting key is claimed; the child `ledger_entry` rows are inserted in the same local transaction and the method returns `POSTED` with the new `LedgerTransactionId`.
    *   **Conflict (`0` rows affected)**: The existing `LedgerTransaction` for `posting_key` is loaded and its persisted `request_fingerprint` is compared against the incoming command's fingerprint:
        *   **Matching fingerprint**: Returns `ALREADY_POSTED` with the existing `LedgerTransactionId` (idempotent replay, no duplicate entries written).
        *   **Different fingerprint**: Throws a conflict exception (`LedgerPostingKeyConflictException` / validation conflict) without writing any new records.

### 3. Reversal Semantics and Persistence Mapping
*   **Compensating Full Reversal**: Correcting a posted transaction requires creating a new immutable `LedgerTransaction` of type `REVERSAL` whose entries mirror the original transaction's entries with `DEBIT` and `CREDIT` directions flipped (`DEBIT` $\leftrightarrow$ `CREDIT`).
*   **Deterministic Reversal Posting Key**: Callers do not supply a posting key when requesting a reversal. `Ledger` deterministically derives `posting_key = "REVERSAL:" + originalTransactionId.getValue()` (well within the 200-character limit).
*   **Consistent Original-Transaction Reference (`reversed_transaction_id`)**: Across the SQL schema, JPA entity mapping, domain model, and documentation, the column/field referencing the original transaction being reversed is named `reversed_transaction_id` (`reversedTransactionId` in Java).
*   **Command-to-Column Mapping**:
    *   `ReverseLedgerTransactionCommand.originalTransactionId` $\rightarrow$ `ledger_transaction.reversed_transaction_id` (`UUID NULL UNIQUE REFERENCES ledger.ledger_transaction(transaction_id)`).
    *   `ReverseLedgerTransactionCommand.reversalReference` (required, non-blank, `<= 255` chars) $\rightarrow$ `ledger_transaction.external_reference` (`VARCHAR(255) NOT NULL`). Included in `request_fingerprint`.
    *   `ReverseLedgerTransactionCommand.reason` (optional / nullable, `<= 500` chars when present; blank normalized to `null`) $\rightarrow$ `ledger_transaction.description` (`VARCHAR(500) NULL`). Excluded from `request_fingerprint`.
    *   Standard `PostLedgerTransactionCommand` uses the same column constraints: `externalReference` is required (`VARCHAR(255) NOT NULL`) and `description` is optional (`VARCHAR(500) NULL`).
*   **Reversal Rules and Concurrency Safety**:
    *   **Reversal of a reversal**: Rejected (`LedgerValidationException`) if the target transaction has `transaction_type = 'REVERSAL'` or `reversed_transaction_id IS NOT NULL`.
    *   **Concurrent or repeated reversals**: Claimed via the deterministic key `REVERSAL:{originalTransactionId}` using `INSERT ... ON CONFLICT (posting_key) DO NOTHING` (backed by `UNIQUE (posting_key)` and `UNIQUE (reversed_transaction_id)`).
    *   **Same `originalTransactionId` + same `reversalReference`**: Fingerprints match $\rightarrow$ returns `ALREADY_POSTED` with the existing reversal transaction ID.
    *   **Same `originalTransactionId` + different `reversalReference`**: Fingerprints differ $\rightarrow$ rejects with a reversal conflict exception referencing the existing reversal transaction ID, with no second financial effect and without aborting the PostgreSQL transaction via an uncaught constraint error.

### 4. Persistence Schema, Migrations, and Transaction Boundaries
*   **Schema Ownership**: The `Ledger` module exclusively owns the PostgreSQL `ledger` schema.
*   **Flyway Migration Sequence**: Planned Phase 3 migrations continue the repository's integer Flyway sequence (`V1`–`V3` exist in Phase 2):
    *   `V4__init_ledger_schema.sql`: `CREATE SCHEMA IF NOT EXISTS ledger;`
    *   `V5__init_ledger_tables.sql`: Creates `ledger.ledger_transaction` and `ledger.ledger_entry`, primary keys, unique constraints (`uk_ledger_transaction_posting_key`, `uk_ledger_tx_reversed_tx`, `uk_ledger_entry_tx_line`), check constraints (`NUMERIC(19,4)`, positive amounts, `entry_count BETWEEN 2 AND 100`, `line_number BETWEEN 1 AND 100`, non-blank strings, reversal consistency), and indexes (`(account_id, currency)` on `ledger_entry`).
    *   `V6__init_ledger_integrity_triggers.sql`: Creates deferred constraint triggers and append-only immutability triggers.
*   **No Cross-Module Foreign Keys**: `ledger.ledger_entry.account_id` (`UUID NOT NULL`) is stored as a plain identifier (`LedgerAccountId` in `ledger.domain`) with **no** foreign key to `banking.account(account_id)`.
*   **Transaction Propagation and Rollback Discipline**:
    *   `LedgerService` write methods use `@Transactional(propagation = Propagation.REQUIRED)` with no internal retry loop; the orchestrating caller controls transaction boundaries and retries.
    *   All domain and command validation must execute **before** the first database write or posting-key claim.
    *   `noRollbackFor = LedgerValidationException.class` is permitted **only** under the strict invariant that no `LedgerValidationException` can be thrown after a posting key has been claimed or any row has been written in the current call. Any database, JDBC, or unexpected runtime error must remain a rollback-causing exception.

### 5. Database-Level Integrity and Append-Only Safeguards
*   **Two Deferred Constraint Triggers (`DEFERRABLE INITIALLY DEFERRED`)**:
    *   One `AFTER INSERT` constraint trigger on `ledger.ledger_transaction` (so a transaction header inserted with zero entries cannot commit unchecked).
    *   One `AFTER INSERT` constraint trigger on `ledger.ledger_entry` (so inserted entry lines are validated against their parent header).
    *   At transaction commit (or `SET CONSTRAINTS ALL IMMEDIATE`), the verification function checks: actual entry count matches `entry_count` (`2..100`), `line_number` values form a contiguous sequence `1..entry_count`, entry currencies all match header `currency`, `SUM(DEBIT) == SUM(CREDIT) == total_amount`, and for `REVERSAL` transactions, the referenced `reversed_transaction_id` is not itself a reversal and the entries exactly mirror the original entries with flipped directions.
*   **Append-Only Immutability Triggers**:
    *   `BEFORE UPDATE OR DELETE` row-level triggers and `BEFORE TRUNCATE` statement-level triggers on both `ledger.ledger_transaction` and `ledger.ledger_entry` unconditionally raise an exception to block mutations during normal application and SQL usage.
    *   *Explicit Limitation*: PostgreSQL triggers protect against application bugs and ordinary SQL modifications, but they can be bypassed or disabled by a database superuser or table owner (`session_replication_role = replica` or `ALTER TABLE ... DISABLE TRIGGER`).

### 6. Balance Calculation Strategy and Banking Reconciliation
*   **On-Read Balance Derivation**: In Phase 3, `LedgerService.getBalance(LedgerAccountId, Currency)` computes `debitTotal`, `creditTotal`, and signed `netBalance` (`creditTotal - debitTotal`) on read via an indexed aggregate query over `ledger.ledger_entry` (`idx_ledger_entry_account_currency`). No separate `account_balance` projection table is introduced in Phase 3.
*   **Separation from Banking Operational Balances**: `Banking` continues to own `available_balance`, `reserved_amount`, and `fund_reservation`.
*   **Documented Simulator Funding Gap**: `BankingService.fundAccount(...)` (introduced in Phase 2 for simulator bootstrap) increments `banking.account.available_balance` without posting a `LedgerTransaction`. This intentional Phase 2/3 limitation is documented rather than silently coupling `Banking` and `Ledger` in Phase 3; full funding/settlement reconciliation will be addressed in a later phase.

## Alternatives Considered
*   **Global `Ledger` Aggregate Root**: Treating the entire ledger as one aggregate root was rejected because loading or locking a single global entity on every transaction serializes all postings and creates a severe concurrency bottleneck.
*   **Signed `Money` Amounts on `LedgerEntry`**: Representing debits as negative numbers and credits as positive numbers (or vice versa) was rejected because accounting rules depend on account type and explicit debit/credit totals (`sum(DEBIT) == sum(CREDIT)`). Positive unsigned `Money` plus an explicit `EntryDirection` (`DEBIT` / `CREDIT`) is unambiguous and matches standard financial messaging and accounting models.
*   **Catching `DataIntegrityViolationException` for Idempotency**: Relying on a unique-constraint failure and catching the exception to return `ALREADY_POSTED` was rejected because PostgreSQL marks the entire transaction rollback-only (`current transaction is aborted`) whenever a statement fails, breaking callers participating in a shared `Propagation.REQUIRED` transaction.
*   **Trigger Only on `ledger_entry`**: Attaching a deferred trigger only to `ledger_entry` was rejected because an errant SQL `INSERT` into `ledger_transaction` with zero child entries would never fire the entry trigger and would commit an empty header.
*   **Materialized Balance Projection Table in Phase 3**: Maintaining a mutable `ledger_balance` table updated on every posting was rejected for Phase 3 to avoid premature complexity and row-contention hotspots before workload requirements justify a projection.

## Trade-offs
*   **Pros**:
    *   Strong defense-in-depth: financial invariants are enforced both in pure Java (`ledger.domain`) and at transaction commit in PostgreSQL (`V5` constraints + `V6` deferred triggers).
    *   Safe participation in caller transactions (`Propagation.REQUIRED`) thanks to conflict-safe `INSERT ... ON CONFLICT DO NOTHING` idempotency claims.
    *   Zero cross-module coupling between `Banking` and `Ledger` (enforced by Spring Modulith and schema isolation).
*   **Cons**:
    *   Deferred constraint triggers execute aggregate checks per touched transaction at commit time, adding minor database overhead compared to application-only checks.
    *   On-read balance aggregation sums historical entries for an `(account_id, currency)` pair, which is fast for simulator volumes with an index but may eventually require snapshots or projections if a single account accumulates millions of entries.

## Consequences
*   Phase 3 implementation (Checkpoints `CP2`–`CP5`) will follow this exact design: pure domain in `CP2`, Flyway `V4`–`V6` and persistence in `CP3`, `LedgerService` idempotency/reversal/concurrency in `CP4`, and final Modulith/integration verification in `CP5`.
*   Existing Phase 1 architecture and domain documents (`domain-model.md`, `module-mapping.md`, `use-cases.md`, `persistence-architecture.md`, and `internal-module-contracts.md`) are updated alongside this ADR so the repository documentation reflects a single consistent architecture.

## Rejected Alternatives
*   **Mutable `status` Column (`DRAFT` $\rightarrow$ `POSTED` $\rightarrow$ `REVERSED`) on `ledger_transaction`**: Rejected because mutating a posted transaction header violates strict append-only immutability and complicates append-only trigger guards. Reversals are recorded purely by inserting a new `REVERSAL` transaction referencing `reversed_transaction_id`.
*   **Cross-Schema Foreign Key from `ledger.ledger_entry(account_id)` to `banking.account(account_id)`**: Rejected because cross-module foreign keys violate modular monolith data isolation (ADR-001, `persistence-architecture.md`) and would couple `Ledger` to `Banking` tables.
