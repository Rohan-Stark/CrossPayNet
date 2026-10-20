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

### `Ledger.PostTransaction` (Planned for Phase 3 — ADR-006)
* **Purpose**: Atomically validate and persist a balanced, single-currency double-entry accounting transaction in the `ledger` schema.
* **Exposed By**: Ledger Module (`LedgerService.postTransaction`)
* **Consumed By**: Payment Module / Settlement Module (future phases)
* **Input Concepts (`PostLedgerTransactionCommand`)**:
  * `postingKey`: `LedgerPostingKey` (Required, non-blank, max 200 characters)
  * `transactionType`: `LedgerTransactionType` (Required, `STANDARD` for normal postings; not `REVERSAL`)
  * `currency`: `Currency` (Required, 3-letter ISO 4217 code)
  * `totalAmount`: `Money` (Required, positive, `scale <= 4`, matching `currency`)
  * `externalReference`: `String` (Required, non-blank, max 255 characters $\rightarrow$ stored in `ledger_transaction.external_reference`)
  * `description`: `String` (Optional/nullable, max 500 characters $\rightarrow$ stored in `ledger_transaction.description`; excluded from financial fingerprint)
  * `entries`: `List<LedgerEntryRequest>` (Required, 2 to 100 items, each with `accountType`, `accountReference`, `EntryDirection` (`DEBIT` or `CREDIT`), and positive unsigned `Money` amount)
* **Output Concepts (`LedgerPostingResult`)**:
  * `transactionId`: `LedgerTransactionId`
  * `status`: `PostingOutcome` (`POSTED` on first creation, or `ALREADY_POSTED` on idempotent retry with matching financial fingerprint)
  * `postedAt`: `Instant`
* **Failure Semantics**:
  * Throws `LedgerValidationException` if domain invariants fail (e.g., fewer than 2 or more than 100 entries, currency mismatch, non-positive amount, `sum(DEBIT) != sum(CREDIT)`, or `sum(DEBIT) != totalAmount`).
  * Throws `LedgerPostingKeyConflictException` if `postingKey` was already claimed with a different `request_fingerprint`.
* **Behavior**: Synchronous (`Propagation.REQUIRED`). Claims `posting_key` via `INSERT ... ON CONFLICT (posting_key) DO NOTHING` so idempotent retries and payload conflicts never abort the caller's PostgreSQL transaction. No REST endpoint is exposed.

### `Ledger.ReverseTransaction` (Planned for Phase 3 — ADR-006)
* **Purpose**: Create a new immutable compensating `REVERSAL` transaction whose entries mirror an existing posted `STANDARD` transaction 1-for-1 (same `accountType`, `accountReference`, `currency`, and `amount`) with `DEBIT` and `CREDIT` directions flipped.
* **Exposed By**: Ledger Module (`LedgerService.reverseTransaction`)
* **Consumed By**: Payment Module / Settlement Module / Operations (future phases)
* **Input Concepts (`ReverseLedgerTransactionCommand`)**:
  * `originalTransactionId`: `LedgerTransactionId` (Required $\rightarrow$ stored in `ledger_transaction.reversed_transaction_id`; Ledger deterministically derives `posting_key = "REVERSAL:" + originalTransactionId`)
  * `reversalReference`: `String` (Required, non-blank, max 255 characters $\rightarrow$ stored in `ledger_transaction.external_reference`; included in `request_fingerprint`)
  * `reason`: `String` (Optional/nullable, max 500 characters $\rightarrow$ stored in `ledger_transaction.description`; excluded from `request_fingerprint`)
* **Output Concepts (`LedgerPostingResult`)**:
  * `transactionId`: `LedgerTransactionId` (ID of the newly created or existing reversal transaction)
  * `status`: `PostingOutcome` (`POSTED` on first reversal, or `ALREADY_POSTED` when retried with the same `originalTransactionId` and `reversalReference`)
  * `postedAt`: `Instant`
* **Failure Semantics**:
  * Throws `LedgerTransactionNotFoundException` if `originalTransactionId` does not exist.
  * Throws `LedgerValidationException` if the target transaction is not a `STANDARD` transaction (reversal-of-reversal is prohibited in both the Java domain and PostgreSQL deferred integrity trigger).
  * Throws `LedgerReversalConflictException` (carrying the existing reversal `LedgerTransactionId`) if `originalTransactionId` was already reversed with a different `reversalReference`.
* **Behavior**: Synchronous (`Propagation.REQUIRED`). Preserves the original transaction unchanged and uses the deterministic `REVERSAL:{originalTransactionId}` posting key with `ON CONFLICT DO NOTHING` for concurrency safety.

### `Ledger.GetBalance` (Planned for Phase 3 — ADR-006)
* **Purpose**: Calculate the authoritative accounting position of an account for a specific currency on read from immutable `LedgerEntry` records.
* **Exposed By**: Ledger Module (`LedgerService.getBalance`)
* **Consumed By**: Settlement Module / Operations Module (future phases)
* **Input Concepts**:
  * `accountType` / `accountReference`: Target ledger account identifier (`LedgerAccountReference`)
  * `currency`: `Currency`
* **Output Concepts (`AccountLedgerBalance`)**:
  * `accountType` / `accountReference`: Target ledger account identifier (`LedgerAccountReference`)
  * `currency`: `Currency`
  * `debitTotal`: `Money` (unsigned sum of `DEBIT` entries, zero if none)
  * `creditTotal`: `Money` (unsigned sum of `CREDIT` entries, zero if none)
  * `netBalance`: `BigDecimal` (signed net amount `debitTotal - creditTotal`, scale 4; standardized as a Phase 3 signed calculation convention, not a universal normal-balance rule across account types)
* **Behavior**: Synchronous read-only query (`readOnly = true`). Computed on read without a mutable balance-projection table.

### `Ledger.FindTransaction` (Planned for Phase 3 — ADR-006)
* **Purpose**: Retrieve a posted `LedgerTransaction` aggregate and its immutable `LedgerEntry` lines by `LedgerTransactionId`.
* **Exposed By**: Ledger Module (`LedgerService.findTransaction`)
* **Consumed By**: Payment Module / Settlement Module / Operations Module (future phases)
* **Input Concepts**:
  * `transactionId`: `LedgerTransactionId`
* **Output Concepts**:
  * `Optional<LedgerTransaction>` (including header fields, `reversedTransactionId` if applicable, and ordered `1..N` `LedgerEntry` lines)
* **Behavior**: Synchronous read-only query (`readOnly = true`).

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
