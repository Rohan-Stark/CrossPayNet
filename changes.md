# CrossPayNet Change Log

Chronological record of verified engineering checkpoints and corrective updates in the CrossPayNet Evolutionary Modular Monolith.

---

## 2026-10-25 — Phase 4 Checkpoint 3 (CP3): `PaymentService` Orchestration & Integration Verification — *Local/Unpublished, Pending Review*

* **Scope**: Implemented [`PaymentService`](src/main/java/com/crosspaynet/payment/PaymentService.java) in `com.crosspaynet.payment` (implementing [`PaymentSettlementPort`](src/main/java/com/crosspaynet/payment/settlement/PaymentSettlementPort.java)) to orchestrate the cross-border payment workflow across `Payment`, `PaymentRepository`, `PaymentMapper`, and `BankingService`:
  * **Conflict-Safe Initiation & Synchronous Reservation (`initiatePayment`)**: Atomically claims `(source_account_id, payment_reference)` via `insertClaimOnConflictDoNothing`. Duplicate submissions return the existing `PaymentView` when `InstructionFingerprint` matches or throw `PaymentReferenceConflictException` (narrowly exempted via `noRollbackFor` on the pre-write duplicate branch). First claims invoke `BankingService.reserveFunds` in the shared transaction, transitioning `VALIDATING -> COMPLIANCE_HOLD` on success, committing a `REJECTED` audit record (`INSUFFICIENT_FUNDS`, `INVALID_ACCOUNT_STATE`, or `CURRENCY_MISMATCH` with `reservationToken = null`) on pre-write business reservation rejections, and rolling back the `VALIDATING` claim row on `AccountNotFoundException`, corrupted state, or unexpected runtime errors so `VALIDATING` never persists as a resting state.
  * **Lifecycle Progression & Financial Commitment Point**: Implemented `approveCompliance` (`COMPLIANCE_HOLD -> ROUTING`), `commitToExecution` (`ROUTING -> EXECUTING`, Financial Commitment Point), and `markDispatched` (`EXECUTING -> PENDING_EXTERNAL`) with idempotent retry support.
  * **Atomic Reservation Release & State Transition (`cancelPayment`, `rejectPayment`, `failPayment`)**: Validates command and domain state transitions in memory before invoking `BankingService.releaseReservation`, and uses default rollback semantics so any downstream persistence or optimistic-locking failure rolls back both the Banking reservation release and the Payment status change together.
  * **Settlement Port (`recordSettlement`) & Read Queries (`findPayment`, `findBySourceAccountAndReference`)**: Records supplied `ledgerTransactionId` (`PENDING_EXTERNAL -> SETTLED`) via `PaymentSettlementPort` without calling `BankingService.consumeReservation` or `LedgerService.postTransaction` (reserved for the future `Settlement` module).
* **Tests & Verification**: Added [`PaymentServiceIntegrationTest`](src/test/java/com/crosspaynet/payment/PaymentServiceIntegrationTest.java) (13 integration tests covering initiation, idempotency, business rejections, claim rollbacks, lifecycle transitions, pre-commitment cancellation/rejection, post-commitment failure, settlement port boundaries, 8-thread concurrent initiation races, and optimistic-lock/constraint rollback atomicity) and expanded [`PaymentArchitectureTest`](src/test/java/com/crosspaynet/payment/PaymentArchitectureTest.java) (+1 test). Focused suite: **19 tests passing** (`0` failures, `0` errors, `0` skipped). Full suite: **152 tests passing** (`0` failures, `0` errors, `0` skipped) across 20 Surefire test suites with Flyway `V1`–`V9`.

---

## 2026-10-25 — Phase 4 CP2 Corrective Update: V9 UTF-16 Unicode Length Alignment (`676d7eb1dd32c18896c7acf4b177d392c13f296d`) — *Accepted & Published to `origin/main`*

* **Problem**: Java domain value objects (`PaymentReference`, `BeneficiaryDetails`, `Payment`) validate text length limits using `String.length()` (UTF-16 code units, where supplementary Unicode characters `U+10000`–`U+10FFFF` occupy 2 units), whereas PostgreSQL `VARCHAR(N)` and `char_length(...)` count Unicode code points (`1` per supplementary character). Without UTF-16-aware SQL constraints, direct SQL could insert a row containing supplementary characters that satisfied `VARCHAR(N)` in PostgreSQL but failed domain restoration (`PersistedPaymentStateException`) in Java.
* **Migration Added**: `V9__align_payment_unicode_length_constraints.sql` updates the six `payment.payment` text-length `CHECK` constraints (`chk_payment_reference_non_blank` $\le 255$, `chk_payment_beneficiary_name` $\le 140$, `chk_payment_beneficiary_account` $\le 34$, `chk_payment_route_reference` $\le 100$, `chk_payment_external_dispatch_reference` $\le 100$, and `chk_payment_reason_detail` $\le 500`) using `(char_length(col) + regexp_count(col, '[\U00010000-\U0010FFFF]')) <= N` while preserving `IS NULL` allowance on nullable columns, trimming, non-blank, and control-character checks.
* **Tests & Verification**: Added `shouldEnforceUtf16SupplementaryUnicodeLengthAlignmentInDatabaseConstraints` in `PaymentPersistenceIntegrationTest` verifying `SQLSTATE 23514` rejection on over-limit UTF-16 supplementary-Unicode inputs across all six columns and positive persistence/restoration at exact UTF-16 boundaries. Full suite: **138 tests passing** (`0` failures, `0` errors, `0` skipped) across 19 Surefire suites with Flyway `V1`–`V9`.

---

## 2026-10-25 — Phase 4 Checkpoint 2 (CP2): Payment Persistence & Integrity Constraints (`b8688ef05a8a6ceeb6875edf3bebb3e701ad3fe5`) — *Accepted & Published to `origin/main`*

* **Scope**: Implemented the `payment` relational schema and JPA persistence adapter:
  * `V7__init_payment_schema.sql`: Created the `payment` schema (`CREATE SCHEMA IF NOT EXISTS payment;`).
  * `V8__init_payment_tables.sql`: Created `payment.payment` with `uk_payment_source_account_ref UNIQUE (source_account_id, payment_reference)`, unconstrained `amount NUMERIC NOT NULL` guarded by `chk_payment_amount` (`scale(trim_scale(amount)) <= 4` without silent rounding), `chk_payment_state_invariants` with explicit `status_reason_code IS NOT NULL` guards in `REJECTED`, `CANCELLED`, and `FAILED` branches to prevent PostgreSQL three-valued logic (`UNKNOWN`) bypasses, operational indexes, and zero cross-module foreign keys.
  * `PaymentEntity`, `PaymentMapper`, and `PaymentRepository` (including `@Version` optimistic locking, in-place entity updates preserving immutable instruction attributes, corruption translation to `PersistedPaymentStateException`, and conflict-safe `insertClaimOnConflictDoNothing`).
* **Tests & Verification**: Added `PaymentPersistenceIntegrationTest` against PostgreSQL 18 Testcontainers. Full suite at commit: **137 tests passing** (`0` failures, `0` errors, `0` skipped).

---

## 2026-10-25 — Phase 4 Checkpoint 1 (CP1): Payment Pure Domain & Public Contracts (`3fa69c93fa457527b9ed489a1857f70aa4a957fe`) — *Accepted & Published to `origin/main`*

* **Scope**: Implemented the pure-Java `Payment` aggregate root (`com.crosspaynet.payment.domain`), deterministic SHA-256 `InstructionFingerprint` (`crosspaynet-payment-fingerprint-v1`), nine-state workflow machine (`VALIDATING`, `COMPLIANCE_HOLD`, `ROUTING`, `EXECUTING`, `PENDING_EXTERNAL`, `SETTLED`, `REJECTED`, `CANCELLED`, `FAILED`), Financial Commitment Point (`ROUTING -> EXECUTING`), public `com.crosspaynet.payment.contracts` (`@NamedInterface("contracts")`) value objects/commands/views/exceptions, and `com.crosspaynet.payment.settlement.PaymentSettlementPort` (`@NamedInterface("settlement")`).
* **Tests & Verification**: Added `PaymentValueObjectsAndContractsTest`, `InstructionFingerprintTest`, `PaymentTest`, and `PaymentArchitectureTest` (20 focused tests). Full suite: **125 tests passing** (`0` failures, `0` errors, `0` skipped).

---

## 2026-10-25 — Banking Boundary Completion (`18e5c06259fc8fb4e591d869455fbc526de3f3a7`) — *Accepted & Published to `origin/main`*

* **Scope**: Completed the narrow public Banking contract package `com.crosspaynet.banking.contracts` (`@NamedInterface("contracts")`), root `BankingException` hierarchy, internal `PersistedBankingStateException`, full reservation lifecycle (`Account.releaseReservation`, `Account.consumeReservation`, `BankingService.releaseReservation`, `BankingService.consumeReservation`), narrow `noRollbackFor` semantics on `BankingService.reserveFunds`, and in-place `FundReservationEntity` persistence in `AccountMapper.updateEntity`.
* **Tests & Verification**: Expanded `AccountTest`, `FundReservationTest`, `BankingServiceTest`, `AccountPersistenceTest`, and `BankingModulithTest`. Full suite: **105 tests passing** (`0` failures, `0` errors, `0` skipped).

---

## 2026-10-21 — Phase 3 Checkpoint 4/5: `LedgerService` & Modulith Verification (`d66a89b7fd41b81ac399040e26b1baebc1868afd`) — *Accepted & Published to `origin/main`*

* **Scope**: Implemented `LedgerService` (`postTransaction`, `reverseTransaction`, `getBalance`, `findTransaction`) in `com.crosspaynet.ledger` backed by conflict-safe `INSERT ... ON CONFLICT DO NOTHING` posting-key claims in `LedgerTransactionRepository` (migrations `V4`–`V6`, ADR-006).
* **Tests & Verification**: Added `LedgerServiceIntegrationTest` (16 integration tests including 8-thread concurrent posting and reversal barrier races against PostgreSQL 18 Testcontainers). Full suite: **91 tests passing** (`0` failures, `0` errors, `0` skipped).
