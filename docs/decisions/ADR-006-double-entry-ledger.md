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

### 1. Aggregate Root, Package Boundaries, and Pure Domain Model
*   **Aggregate Root (`LedgerTransaction`)**: `LedgerTransaction` is the aggregate root of the `Ledger` module. `LedgerEntry` is an immutable child entity owned by `LedgerTransaction`. There is **no** global `Ledger` aggregate root.
*   **Module and Package Boundaries**:
    *   `com.crosspaynet.ledger` owns the public module vocabulary and contracts: `LedgerTransactionId`, `LedgerPostingKey`, `LedgerAccountRef(LedgerAccountType type, String value)`, `LedgerAccountType { BANKING_ACCOUNT, INTERNAL }`, `LedgerTransactionType { STANDARD, REVERSAL }`, `EntryDirection { DEBIT, CREDIT }`, `PostingOutcome { POSTED, ALREADY_POSTED }`, `PostLedgerTransactionCommand`, `LedgerEntryLine`, `ReverseLedgerTransactionCommand`, `LedgerPostingResult`, `LedgerBalance`, `LedgerTransactionView`, `LedgerEntryView`, and the public `LedgerException` hierarchy.
    *   `com.crosspaynet.ledger.domain` owns the pure-Java domain implementation: `LedgerTransaction`, `LedgerEntry`, `PostingFingerprint`, `SupportedCurrencyPolicy`, and `LedgerLimits`.
    *   `ledger.domain.LedgerTransaction` is never exposed through public module contracts; `findTransaction` returns `Optional<LedgerTransactionView>` containing `List<LedgerEntryView>`. Both `LedgerEntryView.entryId` and `LedgerEntry.entryId` use raw `UUID`s (no separate `LedgerEntryId` value object is introduced in Phase 3).
*   **Pure Java Domain**: `com.crosspaynet.ledger.domain` must remain completely independent of Spring, JPA, Hibernate, and JDBC.
*   **No Persisted Draft State**: Transactions are constructed and validated in memory via domain factory methods (`LedgerTransaction.post(...)` and `LedgerTransaction.reverse(...)`) so that only complete, balanced transactions are ever persisted.
*   **Domain Constants (`LedgerLimits`)**: `LedgerLimits` in `com.crosspaynet.ledger.domain` is the single source of truth for domain bounds, which must strictly agree with database column constraints:
    *   `MIN_ENTRIES = 2`
    *   `MAX_ENTRIES = 100`
    *   `MAX_SCALE = 4`
    *   `MAX_NUMERIC_PRECISION = 19`
    *   `MAX_AMOUNT = new BigDecimal("999999999999999.9999")`
    *   `MAX_POSTING_KEY_LENGTH = 200`
    *   `REVERSAL_POSTING_KEY_PREFIX = "REVERSAL:"`
    *   `MAX_EXTERNAL_REFERENCE_LENGTH = 255`
    *   `MAX_DESCRIPTION_LENGTH = 500`
    *   `MAX_ACCOUNT_REFERENCE_LENGTH = 100`
*   **Account References (`LedgerAccountRef` and `LedgerAccountType`)**:
    *   `LedgerAccountType` has two values in Phase 3: `BANKING_ACCOUNT` and `INTERNAL`.
    *   `LedgerAccountRef(LedgerAccountType type, String value)` requires a non-null `type` and a non-null, trimmed, non-blank `value` of `1..100` Unicode code points (`value.codePointCount(0, value.length()) <= 100`) containing no ISO control characters (`Character.isISOControl(cp)`). No ASCII-only restriction is imposed on `value`.
*   **Supported Currency Policy (`SupportedCurrencyPolicy`)**:
    *   The initial simulator currency allow-list enforced by `SupportedCurrencyPolicy` in `ledger.domain` is: `USD, EUR, GBP, INR, JPY, CHF, SGD, AED`.
    *   This is a Ledger simulator policy and does not modify the Shared Kernel `Currency` class.
*   **Domain and Accounting Invariants**:
    *   **Entry Count**: Every posted transaction must contain between `MIN_ENTRIES` (`2`) and `MAX_ENTRIES` (`100`) entries, inclusive.
    *   **Unsigned Positive Amounts + Explicit Direction**: Each `LedgerEntry` holds a strictly positive, unsigned `Money` amount (`amount > 0`, `scale <= 4`, `amount <= MAX_AMOUNT`) and an explicit `EntryDirection` (`DEBIT` or `CREDIT`). Signed entry amounts are not used.
    *   **Single Supported Currency**: Every entry in a `LedgerTransaction` must match the transaction's single `Currency`, which must be accepted by `SupportedCurrencyPolicy`. Multi-currency / FX postings are out of scope for Phase 3.
    *   **Double-Entry Balance**: The sum of all `DEBIT` entry amounts must strictly equal the sum of all `CREDIT` entry amounts, and both must equal the transaction header's `totalAmount` (`sum(DEBIT) == sum(CREDIT) == totalAmount <= MAX_AMOUNT`).
    *   **Deterministic Line Numbers**: The domain assigns entry `UUID` identifiers and sequential 1-based line numbers (`1..N`) to entries in a deterministic order; callers do not supply entry IDs or line numbers.
    *   **Immutable History**: Once constructed/posted, a `LedgerTransaction` and its `LedgerEntry` lines are defensively copied and immutable.

### 2. Internal Module Capability, Public Contracts, and Posting Idempotency
*   **Public Module Contract (`LedgerService`)**: The `Ledger` module exposes a synchronous internal application service (`LedgerService`) with four planned operations for Phase 3:
    *   `LedgerPostingResult postTransaction(PostLedgerTransactionCommand command)`
    *   `LedgerPostingResult reverseTransaction(ReverseLedgerTransactionCommand command)`
    *   `LedgerBalance getBalance(LedgerAccountRef account, Currency currency)`
    *   `Optional<LedgerTransactionView> findTransaction(LedgerTransactionId transactionId)`
    *   No REST controller or external HTTP endpoint is introduced in Phase 3.
*   **Command and View Shapes**:
    *   `PostLedgerTransactionCommand(LedgerPostingKey postingKey, Currency currency, List<LedgerEntryLine> entries, String externalReference, String description)`: Always creates a `STANDARD` transaction (no `transactionType` field on the command). `externalReference` is required, trimmed non-blank, `<= 255` chars; `description` is optional, trimmed `<= 500` chars (blank normalizes to `null`).
    *   `LedgerEntryLine(LedgerAccountRef account, EntryDirection direction, Money amount)`: Caller-supplied entry specification.
    *   `ReverseLedgerTransactionCommand(LedgerTransactionId originalTransactionId, String reversalReference, String reason)`: Does not accept a caller-supplied posting key. `reversalReference` is required, trimmed non-blank, `<= 255` chars; `reason` is optional, trimmed `<= 500` chars (blank normalizes to `null`).
    *   `LedgerPostingResult(LedgerTransactionId transactionId, PostingOutcome outcome, Instant postedAt)`.
    *   `LedgerTransactionView(LedgerTransactionId transactionId, LedgerTransactionType transactionType, LedgerPostingKey postingKey, Currency currency, Money totalAmount, String externalReference, String description, LedgerTransactionId reversedTransactionId, Instant postedAt, List<LedgerEntryView> entries)` and `LedgerEntryView(UUID entryId, int lineNumber, LedgerAccountRef account, EntryDirection direction, Money amount)`.
    *   `LedgerBalance(LedgerAccountRef account, Currency currency, Money debitTotal, Money creditTotal, BigDecimal netBalance)`.
*   **Posting Key (`LedgerPostingKey`) Validation**:
    *   Maximum length `200` characters (`MAX_POSTING_KEY_LENGTH = 200`), matching `VARCHAR(200)` in PostgreSQL.
    *   Allowed syntax: `^[A-Za-z0-9:_.-]{1,200}$` (trimmed; no whitespace or characters outside this ASCII set).
    *   Caller-supplied standard posting keys must not start with `REVERSAL:` (checked case-insensitively).
    *   For reversals, `Ledger` deterministically derives the exact uppercase key `"REVERSAL:" + originalTransactionId.getValue()` via an internal/reversal factory method.
*   **Separation of Idempotency Concerns**: Ledger posting idempotency (`LedgerPostingKey`) is distinct from external HTTP API idempotency (`Idempotency-Key`), `Banking` fund-reservation idempotency (`(account_id, payment_reference)`), and future network-message deduplication.
*   **Deterministic Financial Fingerprint (`PostingFingerprint` / `request_fingerprint`)**:
    *   Uses a versioned, length-prefixed UTF-8 canonical encoding hashed with JDK `SHA-256` to produce a 64-character lowercase hexadecimal digest.
    *   **Version Marker**: Begins with `crosspaynet-ledger-fingerprint-v1\n`.
    *   **Length-Prefixed Token Rule**: Each non-null scalar value is encoded as `<utf8ByteLength>:<utf8Bytes>` (where `<utf8ByteLength>` is the decimal count of UTF-8 bytes in `value.getBytes(StandardCharsets.UTF_8).length`, not UTF-16 `String.length()`), and `null` is encoded distinctly as the literal token `NULL` (with no colon), so `null` and empty string `0:` can never collide.
    *   **Included Header Fields (in exact order, each followed by `\n`)**:
        1. `transactionType`: `LedgerTransactionType.name()` (`STANDARD` or `REVERSAL`)
        2. `currency`: 3-letter uppercase ISO code (`currency.getCode()`)
        3. `totalAmount`: exact 4-decimal plain string (`totalAmount.getAmount().setScale(4, RoundingMode.UNNECESSARY).toPlainString()`)
        4. `externalReference`: trimmed `externalReference` (or `reversalReference` for reversals)
        5. `reversedTransactionId`: `NULL` for `STANDARD`, or length-prefixed lowercase UUID string (`reversedTransactionId.getValue().toString()`) for `REVERSAL`
        6. `entryCount`: length-prefixed decimal integer count of entries
    *   **Canonical Entry Tuples**:
        *   Each entry is encoded as the 4-field length-prefixed record `<len>:<accountType.name()>|<len>:<accountRef.value()>|<len>:<direction.name()>|<len>:<amount.setScale(4).toPlainString()>`.
        *   The encoded entry records are sorted deterministically in ascending lexicographical order (`Comparator.naturalOrder()` over their canonical length-prefixed strings) and appended in sorted order (each followed by `\n`).
        *   Descriptive metadata (`description` / reversal `reason`) and caller input list order are excluded from the fingerprint.
*   **Public Exception Hierarchy (`com.crosspaynet.ledger`)**:
    *   `LedgerException extends RuntimeException`: Root exception for the `Ledger` module.
    *   `LedgerValidationException extends LedgerException`: Base exception for domain, invariant, and business validation errors (distinct from technical/database runtime failures).
    *   `LedgerPostingKeyConflictException extends LedgerValidationException`: Thrown when a `posting_key` is reused with a different `request_fingerprint`.
    *   `LedgerReversalConflictException extends LedgerValidationException`: Thrown when `originalTransactionId` was already reversed with a different `reversalReference`; carries `existingReversalTransactionId` (`LedgerTransactionId`).
    *   `LedgerTransactionNotFoundException extends LedgerValidationException`: Thrown when a referenced `originalTransactionId` does not exist.
    *   Additional specific domain-validation exceptions may extend `LedgerValidationException`.
*   **PostgreSQL Conflict-Safe Claim (`INSERT ... ON CONFLICT DO NOTHING`)**:
    *   Because an uncaught uniqueness violation in PostgreSQL marks the current database transaction as aborted (`25P02`), `LedgerService` must **not** use a failing `INSERT` + exception catch as a recoverable business flow.
    *   Instead, after pure domain validation succeeds, the persistence layer attempts to insert the transaction header using `INSERT INTO ledger.ledger_transaction (...) VALUES (...) ON CONFLICT (posting_key) DO NOTHING`.
    *   **Row inserted (`1` row affected)**: The posting key is claimed; the child `ledger_entry` rows are inserted in the same local transaction and the method returns `POSTED` with the new `LedgerTransactionId`.
    *   **Conflict (`0` rows affected)**: The existing `LedgerTransaction` for `posting_key` is loaded and its persisted `request_fingerprint` is compared against the incoming command's fingerprint:
        *   **Matching fingerprint**: Returns `ALREADY_POSTED` with the existing `LedgerTransactionId` (idempotent replay, no duplicate entries written).
        *   **Different fingerprint**: Throws `LedgerPostingKeyConflictException` (or `LedgerReversalConflictException` for reversals) without writing any new records.

### 3. Reversal Semantics and Persistence Mapping
*   **Compensating Full Reversal**: Correcting a posted transaction requires creating a new immutable `LedgerTransaction` of type `REVERSAL` whose entries mirror the original `STANDARD` transaction's entries 1-for-1 (same `accountType`, `accountReference`, `currency`, and `amount`) with `DEBIT` and `CREDIT` directions flipped (`DEBIT` $\leftrightarrow$ `CREDIT`).
*   **Deterministic Reversal Posting Key**: Callers do not supply a posting key when requesting a reversal. `Ledger` deterministically derives `posting_key = "REVERSAL:" + originalTransactionId.getValue()` (well within the 200-character limit).
*   **Consistent Original-Transaction Reference (`reversed_transaction_id`)**: Across the SQL schema, JPA entity mapping, domain model, and documentation, the column/field referencing the original transaction being reversed is named `reversed_transaction_id` (`reversedTransactionId` in Java).
*   **Command-to-Column Mapping**:
    *   `ReverseLedgerTransactionCommand.originalTransactionId` $\rightarrow$ `ledger_transaction.reversed_transaction_id` (`UUID NULL UNIQUE REFERENCES ledger.ledger_transaction(transaction_id)`).
    *   `ReverseLedgerTransactionCommand.reversalReference` (required, non-blank, `<= 255` chars) $\rightarrow$ `ledger_transaction.external_reference` (`VARCHAR(255) NOT NULL`). Included in `request_fingerprint`.
    *   `ReverseLedgerTransactionCommand.reason` (optional / nullable, `<= 500` chars when present; blank normalized to `null`) $\rightarrow$ `ledger_transaction.description` (`VARCHAR(500) NULL`). Excluded from `request_fingerprint`.
    *   Standard `PostLedgerTransactionCommand` uses the same column constraints: `externalReference` is required (`VARCHAR(255) NOT NULL`) and `description` is optional (`VARCHAR(500) NULL`).
*   **Reversal Rules and Concurrency Safety**:
    *   **Target must be a `STANDARD` transaction**: Reversing a reversal is strictly prohibited. Both the pure Java domain (`LedgerValidationException`) and the PostgreSQL deferred constraint trigger reject any reversal whose target transaction does not have `transaction_type = 'STANDARD'` (or has `reversed_transaction_id IS NOT NULL`).
    *   **Concurrent or repeated reversals**: Claimed via the deterministic key `REVERSAL:{originalTransactionId}` using `INSERT ... ON CONFLICT (posting_key) DO NOTHING` (backed by `UNIQUE (posting_key)` and `UNIQUE (reversed_transaction_id)`).
    *   **Same `originalTransactionId` + same `reversalReference`**: Fingerprints match $\rightarrow$ returns `ALREADY_POSTED` with the existing reversal transaction ID.
    *   **Same `originalTransactionId` + different `reversalReference`**: Fingerprints differ $\rightarrow$ rejects with a reversal conflict exception referencing the existing reversal transaction ID, with no second financial effect and without aborting the PostgreSQL transaction via an uncaught constraint error.

### 4. Persistence Schema, Migrations, and Transaction Boundaries
*   **Schema Ownership**: The `Ledger` module exclusively owns the PostgreSQL `ledger` schema.
*   **Flyway Migration Sequence**: Planned Phase 3 migrations continue the repository's integer Flyway sequence (`V1`–`V3` exist in Phase 2):
    *   `V4__init_ledger_schema.sql`: `CREATE SCHEMA IF NOT EXISTS ledger;`
    *   `V5__init_ledger_tables.sql`: Creates `ledger.ledger_transaction` and `ledger.ledger_entry`, primary keys, unique constraints (`uk_ledger_transaction_posting_key`, `uk_ledger_tx_reversed_tx`, `uk_ledger_entry_tx_line`), check constraints (`chk_ledger_tx_total_amount` and `chk_ledger_entry_amount` enforcing positive amounts `<= 999999999999999.9999` with `scale(trim_scale(...)) <= 4` on unconstrained `NUMERIC NOT NULL` columns, `entry_count BETWEEN 2 AND 100`, `line_number BETWEEN 1 AND 100`, non-blank strings, reversal header consistency), and indexes (`(account_type, account_reference, currency)` on `ledger_entry`).
    *   `V6__init_ledger_integrity_triggers.sql`: Creates deferred constraint triggers and append-only immutability triggers.
*   **Monetary Numeric Persistence Contract (No Database Input Rounding)**:
    *   `ledger.ledger_transaction.total_amount` and `ledger.ledger_entry.amount` use unconstrained `NUMERIC NOT NULL` rather than `NUMERIC(19,4)` so PostgreSQL does not silently round inputs with more than four fractional digits during column type coercion before `CHECK` constraints are evaluated.
    *   `chk_ledger_tx_total_amount` (`total_amount > 0 AND total_amount <= 999999999999999.9999 AND scale(trim_scale(total_amount)) <= 4`) and `chk_ledger_entry_amount` (`amount > 0 AND amount <= 999999999999999.9999 AND scale(trim_scale(amount)) <= 4`) preserve the accepted monetary range (`0.0001` to `999999999999999.9999`) and no-silent-rounding requirement: `scale(trim_scale(...)) <= 4` ignores insignificant trailing fractional zeros while rejecting amounts with more than four meaningful fractional decimal places.
    *   JPA entity mappings and these SQL constraint expressions must be checked against the actual Java `Money` implementation and verified via PostgreSQL 18 integration tests during `CP3`.
*   **No Cross-Module Foreign Keys**: Each `ledger.ledger_entry` identifies its target account using plain `account_type` and `account_reference` columns with **no** foreign key to `banking.account(account_id)`.
*   **Transaction Propagation and Rollback Discipline**:
    *   `LedgerService` write methods use `@Transactional(propagation = Propagation.REQUIRED)` with no internal retry loop; the orchestrating caller controls transaction boundaries and retries.
    *   All domain and command validation must execute **before** the first database write or posting-key claim.
    *   `noRollbackFor = LedgerValidationException.class` is permitted **only** under the strict invariant that no `LedgerValidationException` can be thrown after a posting key has been claimed or any row has been written in the current call. Any database, JDBC, or unexpected runtime error must remain a rollback-causing exception.

### 5. Database-Level Integrity and Append-Only Safeguards
*   **Two Deferred Constraint Triggers (`DEFERRABLE INITIALLY DEFERRED`)**:
    *   One `AFTER INSERT` constraint trigger on `ledger.ledger_transaction` (so a transaction header inserted with zero entries cannot commit unchecked).
    *   One `AFTER INSERT` constraint trigger on `ledger.ledger_entry` (so inserted entry lines are validated against their parent header).
    *   **Standard Transaction Verification at Commit**: At transaction commit (or `SET CONSTRAINTS ALL IMMEDIATE`), the PL/pgSQL verification function independently verifies for the parent `ledger_transaction`:
        1. The parent `ledger_transaction` row exists and its declared `entry_count` is between `2` and `100`.
        2. The actual number of `ledger_entry` rows for `transaction_id` equals `entry_count`.
        3. The `line_number` values are distinct and form a contiguous 1-based sequence `1..entry_count` (`COUNT(*) = entry_count AND COUNT(DISTINCT line_number) = entry_count AND MIN(line_number) = 1 AND MAX(line_number) = entry_count`).
        4. Every `ledger_entry.currency` matches the parent `ledger_transaction.currency`.
        5. `SUM(amount)` for `DEBIT` entries equals `total_amount` and `SUM(amount)` for `CREDIT` entries equals `total_amount` (`SUM(DEBIT) == SUM(CREDIT) == total_amount`).
    *   **Complete Reversal Verification at Commit (Defense in Depth)**: When `ledger_transaction.transaction_type = 'REVERSAL'`, the deferred trigger function additionally verifies at commit:
        1. `reversed_transaction_id` is non-null, references an existing row in `ledger.ledger_transaction`, and that referenced original transaction has `transaction_type = 'STANDARD'` (and `reversed_transaction_id IS NULL`).
        2. The reversal header's `currency`, `total_amount`, and `entry_count` all strictly equal the original transaction's `currency`, `total_amount`, and `entry_count`.
        3. Every original entry has **exactly one** matching reversal entry with the same `account_type`, `account_reference`, `currency`, and `amount`, and with `DEBIT` and `CREDIT` directions swapped (`DEBIT` $\leftrightarrow$ `CREDIT`), and no extra or missing reversal entries exist.
        4. *Algorithmic Guarantee for 1-to-1 Entry Bijection*: To handle even transactions that contain multiple lines with identical `(account_type, account_reference, currency, amount, direction)` tuples without depending on `line_number` order, the trigger compares the grouped tuple multiplicities (`COUNT(*)` per `(account_type, account_reference, currency, amount, flipped_direction)`) of the original transaction's entries against the grouped tuple multiplicities (`COUNT(*)` per `(account_type, account_reference, currency, amount, direction)`) of the reversal transaction's entries via a `FULL OUTER JOIN ... WHERE orig.multiplicity IS DISTINCT FROM rev.multiplicity`. Combined with `rev.entry_count = orig.entry_count` and contiguous `1..entry_count` line numbers, an empty symmetric multiset difference guarantees an exact 1-to-1 bijection between original and reversal entries.
    *   **Direct-SQL Integration Testing Requirement (`CP3`)**: The `CP3` PostgreSQL integration tests will include direct-SQL/JDBC test cases that bypass pure Java domain validation to prove the deferred trigger rejects: zero-entry headers, unbalanced entries, non-contiguous line numbers, reversing a `REVERSAL` transaction, reversal currency/total/entry-count mismatches, unflipped reversal entry directions, mismatched `account_type`/`account_reference`/`currency`/`amount` on a reversal entry, and extra or missing reversal entries.
*   **Append-Only Immutability Triggers**:
    *   `BEFORE UPDATE OR DELETE` row-level triggers and `BEFORE TRUNCATE` statement-level triggers on both `ledger.ledger_transaction` and `ledger.ledger_entry` unconditionally raise an exception to block mutations during normal application and SQL usage.
    *   *Explicit Limitation*: PostgreSQL triggers protect against application bugs and ordinary SQL modifications, but they can be bypassed or disabled by a database superuser or table owner (`session_replication_role = replica` or `ALTER TABLE ... DISABLE TRIGGER`).

### 6. Balance Calculation Strategy and Banking Reconciliation
*   **On-Read Balance Derivation**: In Phase 3, `LedgerService.getBalance(...)` computes `debitTotal`, `creditTotal`, and signed `netBalance` (`debitTotal - creditTotal`) on read via an indexed aggregate query over `ledger.ledger_entry` (`idx_ledger_entry_account_currency`). No separate `account_balance` projection table is introduced in Phase 3.
*   **Signed Balance Calculation Convention (`netBalance = debitTotal - creditTotal`)**: `netBalance = debitTotal - creditTotal` is standardized as a signed calculation convention for Phase 3. Because Phase 3 does not introduce a chart-of-accounts model (which would distinguish debit-normal asset/expense accounts from credit-normal liability/equity/revenue accounts), `debitTotal - creditTotal` must not be interpreted as the universal normal balance of every account.
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
    *   On-read balance aggregation sums historical entries for an `(account_type, account_reference, currency)` tuple, which is fast for simulator volumes with an index but may eventually require snapshots or projections if a single account accumulates millions of entries.

## Consequences
*   Phase 3 implementation (Checkpoints `CP2`–`CP5`) will follow this exact design: pure domain in `CP2`, Flyway `V4`–`V6` and persistence in `CP3`, `LedgerService` idempotency/reversal/concurrency in `CP4`, and final Modulith/integration verification in `CP5`.
*   Existing Phase 1 architecture and domain documents (`domain-model.md`, `module-mapping.md`, `use-cases.md`, `persistence-architecture.md`, and `internal-module-contracts.md`) are updated alongside this ADR so the repository documentation reflects a single consistent architecture.

## Rejected Alternatives
*   **Mutable `status` Column (`DRAFT` $\rightarrow$ `POSTED` $\rightarrow$ `REVERSED`) on `ledger_transaction`**: Rejected because mutating a posted transaction header violates strict append-only immutability and complicates append-only trigger guards. Reversals are recorded purely by inserting a new `REVERSAL` transaction referencing `reversed_transaction_id`.
*   **Cross-Schema Foreign Key from `ledger.ledger_entry` to `banking.account(account_id)`**: Rejected because cross-module foreign keys violate modular monolith data isolation (ADR-001, `persistence-architecture.md`) and would couple `Ledger` to `Banking` tables.
