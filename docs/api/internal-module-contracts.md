# Internal Module Contracts

This document defines the conceptual, technology-neutral contracts between internal modules in the CrossPayNet Evolutionary Modular Monolith. These contracts define how boundaries enforce the interactions mapped in Phase 1.B.6.

*Note: No Java interfaces or implementation signatures are provided here. These are conceptual contracts.*

## 1. Synchronous Contracts (APIs)

Synchronous contracts are used when immediate consistency is required to satisfy a domain invariant (e.g., reserving funds before allowing a payment to proceed).

### `Banking.ReserveFunds`
* **Purpose**: Ensure sufficient liquidity exists and place a temporary hold on a customer's available balance.
* **Exposed By**: Banking Module (`BankingService.reserveFunds`)
* **Consumed By**: Payment Module
* **Input Concepts**:
  * `AccountId` (`com.crosspaynet.banking.contracts.AccountId` — Target account)
  * `Money` (`com.crosspaynet.common.money.Money` — Strictly positive amount & currency)
  * `PaymentReference` (`com.crosspaynet.banking.contracts.PaymentReference` — Non-empty trimmed reference up to 255 characters, e.g., Payment ID requesting the hold)
* **Output Concepts**:
  * `ReservationToken` (`com.crosspaynet.banking.contracts.ReservationToken` — Proof of hold, required for release or consumption)
* **Failure Semantics** (Public exceptions in `com.crosspaynet.banking.contracts`, rooted at `BankingException extends RuntimeException`):
  * Throws pre-write business exceptions (`AccountNotFoundException`, `InsufficientFundsException`, `InvalidAccountStateException`, `CurrencyMismatchException`, `InvalidReservationStateException`) when business preconditions fail before any write.
* **Behavior**: Synchronous (`Propagation.REQUIRED`). Idempotent if an `ACTIVE` reservation already exists for the same `(accountId, paymentReference)` and `Money` amount (returning the existing `ReservationToken` without publishing a duplicate `FundsReserved` event). Only the specified pre-write business exceptions (`AccountNotFoundException`, `InsufficientFundsException`, `InvalidAccountStateException`, `CurrencyMismatchException`, `InvalidReservationStateException`) are configured with `noRollbackFor` so they do not mark the shared caller transaction rollback-only, allowing the Payment module to catch the business rejection, transition the Payment to `REJECTED`, and commit the audit trail (per `persistence-architecture.md` §I). Corrupted persisted state (`PersistedBankingStateException`), invalid/null caller arguments (`IllegalArgumentException`), optimistic-locking failures, database/integrity failures, and unexpected runtime errors always mark the surrounding transaction for rollback.

### `Banking.ReleaseReservation`
* **Purpose**: Release an active operational fund reservation (`ACTIVE -> RELEASED`) and restore the held amount from `reservedAmount` back to `availableBalance`.
* **Exposed By**: Banking Module (`BankingService.releaseReservation`)
* **Consumed By**: Payment Module / Compliance Module / Operations Module
* **Input Concepts**:
  * `AccountId` (`com.crosspaynet.banking.contracts.AccountId`)
  * `ReservationToken` (`com.crosspaynet.banking.contracts.ReservationToken`)
* **Failure Semantics**:
  * Throws `AccountNotFoundException` if the account does not exist.
  * Throws `InvalidReservationStateException` if the reservation token does not exist on the account or if the reservation is already `CONSUMED` (`CONSUMED -> RELEASED` is prohibited).
* **Behavior**: Synchronous (`Propagation.REQUIRED`). Idempotent no-op if the reservation is already `RELEASED`. Permitted on an `ACTIVE` reservation even if the account has since been frozen or closed so held funds are not trapped on payment rejection. Updates the existing `FundReservationEntity` in place without deleting reservation history.

### `Banking.ConsumeReservation`
* **Purpose**: Consume an active operational fund reservation (`ACTIVE -> CONSUMED`) upon settlement and decrement `reservedAmount` while keeping `availableBalance` deducted.
* **Exposed By**: Banking Module (`BankingService.consumeReservation`)
* **Consumed By**: Payment Module / Settlement Module
* **Input Concepts**:
  * `AccountId` (`com.crosspaynet.banking.contracts.AccountId`)
  * `ReservationToken` (`com.crosspaynet.banking.contracts.ReservationToken`)
* **Failure Semantics**:
  * Throws `AccountNotFoundException` if the account does not exist.
  * Throws `InvalidReservationStateException` if the reservation token does not exist on the account or if the reservation is already `RELEASED` (`RELEASED -> CONSUMED` is prohibited).
  * Throws `InvalidAccountStateException` if an `ACTIVE` reservation is consumed while the account is not `ACTIVE` (`FROZEN` or `CLOSED`).
* **Behavior**: Synchronous (`Propagation.REQUIRED`). Idempotent no-op if the reservation is already `CONSUMED` (checked prior to account-status validation). Updates the existing `FundReservationEntity` in place without deleting reservation history.

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
* **Purpose**: Atomically validate and persist a balanced, single-currency double-entry `STANDARD` accounting transaction in the `ledger` schema.
* **Exposed By**: Ledger Module (`LedgerService.postTransaction`)
* **Consumed By**: Payment Module / Settlement Module (future phases)
* **Input Concepts (`PostLedgerTransactionCommand(LedgerPostingKey postingKey, Currency currency, List<LedgerEntryLine> entries, String externalReference, String description)`)**:
  * `postingKey`: `LedgerPostingKey` (Required, syntax `[A-Za-z0-9:_.-]{1,200}`, must not start with `REVERSAL:` case-insensitively)
  * `currency`: `Currency` (Required, 3-letter ISO 4217 code accepted by `SupportedCurrencyPolicy`: `USD, EUR, GBP, INR, JPY, CHF, SGD, AED`)
  * `entries`: `List<LedgerEntryLine>` (Required, 2 to 100 items, each `LedgerEntryLine(LedgerAccountRef account, EntryDirection direction, Money amount)` with `LedgerAccountRef(LedgerAccountType type, String value)` where `type` is `BANKING_ACCOUNT` or `INTERNAL` and `value` is `1..100` Unicode code points with no ISO control chars, `EntryDirection` (`DEBIT` or `CREDIT`), and positive unsigned `Money` amount `<= 999999999999999.9999`; the domain assigns entry UUIDs and deterministic `1..N` line numbers and derives `totalAmount`)
  * `externalReference`: `String` (Required, non-blank, max 255 characters $\rightarrow$ stored in `ledger_transaction.external_reference`)
  * `description`: `String` (Optional/nullable, max 500 characters, blank normalizes to `null` $\rightarrow$ stored in `ledger_transaction.description`; excluded from financial fingerprint)
* **Output Concepts (`LedgerPostingResult(LedgerTransactionId transactionId, PostingOutcome outcome, Instant postedAt)`)**:
  * `transactionId`: `LedgerTransactionId`
  * `outcome`: `PostingOutcome` (`POSTED` on first creation, or `ALREADY_POSTED` on idempotent retry with matching financial fingerprint)
  * `postedAt`: `Instant`
* **Failure Semantics** (Public exceptions in `com.crosspaynet.ledger`, rooted at `LedgerException extends RuntimeException` $\rightarrow$ `LedgerValidationException extends LedgerException`):
  * Throws `LedgerValidationException` (or a specific subclass) if domain invariants fail (e.g., fewer than 2 or more than 100 entries, unsupported or mismatched currency, non-positive or out-of-bounds amount, or `sum(DEBIT) != sum(CREDIT)`).
  * Throws `LedgerPostingKeyConflictException` (extends `LedgerValidationException`) if `postingKey` was already claimed with a different `request_fingerprint`.
* **Behavior**: Synchronous (`Propagation.REQUIRED`). Always creates a `STANDARD` transaction (`LedgerTransactionType.STANDARD`). Claims `posting_key` via `INSERT ... ON CONFLICT (posting_key) DO NOTHING` so idempotent retries and payload conflicts never abort the caller's PostgreSQL transaction. No REST endpoint is exposed.

### `Ledger.ReverseTransaction` (Planned for Phase 3 — ADR-006)
* **Purpose**: Create a new immutable compensating `REVERSAL` transaction whose entries mirror an existing posted `STANDARD` transaction 1-for-1 (same `LedgerAccountRef`, `currency`, and `amount`) with `DEBIT` and `CREDIT` directions flipped.
* **Exposed By**: Ledger Module (`LedgerService.reverseTransaction`)
* **Consumed By**: Payment Module / Settlement Module / Operations (future phases)
* **Input Concepts (`ReverseLedgerTransactionCommand(LedgerTransactionId originalTransactionId, String reversalReference, String reason)`)**:
  * `originalTransactionId`: `LedgerTransactionId` (Required $\rightarrow$ stored in `ledger_transaction.reversed_transaction_id`; Ledger deterministically derives uppercase `posting_key = "REVERSAL:" + originalTransactionId`; callers do not supply a posting key)
  * `reversalReference`: `String` (Required, non-blank, max 255 characters $\rightarrow$ stored in `ledger_transaction.external_reference`; included in `request_fingerprint`)
  * `reason`: `String` (Optional/nullable, max 500 characters, blank normalizes to `null` $\rightarrow$ stored in `ledger_transaction.description`; excluded from `request_fingerprint`)
* **Output Concepts (`LedgerPostingResult(LedgerTransactionId transactionId, PostingOutcome outcome, Instant postedAt)`)**:
  * `transactionId`: `LedgerTransactionId` (ID of the newly created or existing reversal transaction)
  * `outcome`: `PostingOutcome` (`POSTED` on first reversal, or `ALREADY_POSTED` when retried with the same `originalTransactionId` and `reversalReference`)
  * `postedAt`: `Instant`
* **Failure Semantics**:
  * Throws `LedgerTransactionNotFoundException` (extends `LedgerValidationException`) if `originalTransactionId` does not exist.
  * Throws `LedgerValidationException` if the target transaction is not a `STANDARD` transaction (reversal-of-reversal is prohibited in both the Java domain and PostgreSQL deferred integrity trigger).
  * Throws `LedgerReversalConflictException` (extends `LedgerValidationException`, carrying `existingReversalTransactionId`) if `originalTransactionId` was already reversed with a different `reversalReference`.
* **Behavior**: Synchronous (`Propagation.REQUIRED`). Preserves the original transaction unchanged and uses the deterministic `REVERSAL:{originalTransactionId}` posting key with `ON CONFLICT DO NOTHING` for concurrency safety.

### `Ledger.GetBalance` (Planned for Phase 3 — ADR-006)
* **Purpose**: Calculate the authoritative accounting position of an account for a specific currency on read from immutable `LedgerEntry` records.
* **Exposed By**: Ledger Module (`LedgerService.getBalance`)
* **Consumed By**: Settlement Module / Operations Module (future phases)
* **Input Concepts**:
  * `account`: `LedgerAccountRef(LedgerAccountType type, String value)`
  * `currency`: `Currency`
* **Output Concepts (`LedgerBalance(LedgerAccountRef account, Currency currency, Money debitTotal, Money creditTotal, BigDecimal netBalance)`)**:
  * `account`: `LedgerAccountRef`
  * `currency`: `Currency`
  * `debitTotal`: `Money` (unsigned sum of `DEBIT` entries, zero if none)
  * `creditTotal`: `Money` (unsigned sum of `CREDIT` entries, zero if none)
  * `netBalance`: `BigDecimal` (signed net amount `debitTotal - creditTotal`, scale 4; standardized as a Phase 3 signed calculation convention, not a universal normal-balance rule across account types)
* **Behavior**: Synchronous read-only query (`readOnly = true`). Computed on read without a mutable balance-projection table.

### `Ledger.FindTransaction` (Planned for Phase 3 — ADR-006)
* **Purpose**: Retrieve a read-only view of a posted `LedgerTransaction` and its immutable entries by `LedgerTransactionId` without exposing internal `ledger.domain` classes across module boundaries.
* **Exposed By**: Ledger Module (`LedgerService.findTransaction(LedgerTransactionId transactionId)`)
* **Consumed By**: Payment Module / Settlement Module / Operations Module (future phases)
* **Input Concepts**:
  * `transactionId`: `LedgerTransactionId`
* **Output Concepts**:
  * `Optional<LedgerTransactionView>` where `LedgerTransactionView` exposes `transactionId`, `transactionType` (`LedgerTransactionType { STANDARD, REVERSAL }`), `postingKey`, `currency`, `totalAmount`, `externalReference`, optional `description`, optional `reversedTransactionId`, `postedAt`, and immutable `List<LedgerEntryView>` (each `LedgerEntryView(UUID entryId, int lineNumber, LedgerAccountRef account, EntryDirection direction, Money amount)`)
* **Behavior**: Synchronous read-only query (`readOnly = true`).

### `Payment.PublicContracts` & `Payment.SettlementPort` (Phase 4 CP1–CP2)
* **Purpose**: Provide narrow public named interfaces for Payment workflow commands/views (`payment :: contracts`) and final settlement outcome recording (`payment :: settlement`), without exposing `payment.domain` or `payment.infrastructure.persistence`.
* **Exposed By**:
  * `com.crosspaynet.payment.contracts` (`@NamedInterface("contracts")`): `PaymentId`, `BicCode`, `BeneficiaryDetails` (`beneficiaryName` $\le 140$ UTF-16 code units, `beneficiaryAccountNumber` $\le 34$ UTF-16 code units, `beneficiaryBankBic`), `PaymentInstruction` (`sourceAccountId`, `paymentReference` $\le 255$ UTF-16 code units, `beneficiary`, `amount`), `PaymentStatus`, `PaymentReasonCode`, `PaymentView`, command records (`InitiatePaymentCommand`, `CancelPaymentCommand`, `RejectPaymentCommand`, `CommitPaymentExecutionCommand`, `MarkPaymentDispatchedCommand`, `FailPaymentCommand`), and the `PaymentException` hierarchy (`PaymentValidationException`, `PaymentNotFoundException`, `InvalidPaymentStateException`, `PaymentReferenceConflictException`).
  * `com.crosspaynet.payment.settlement` (`@NamedInterface("settlement")`): `PaymentSettlementPort` and `RecordPaymentSettlementCommand(PaymentId paymentId, UUID ledgerTransactionId)`.
* **Boundary & Validation Alignment**:
  * `Payment` does not depend on `Ledger`; final settlement orchestration (`BankingService.consumeReservation`, `LedgerService.postTransaction`, and `PaymentSettlementPort.recordSettlement`) is reserved for the `Settlement` module.
  * Text length limits on `payment_reference` ($\le 255$), `beneficiary_name` ($\le 140$), `beneficiary_account_number` ($\le 34$), `route_reference` ($\le 100$), `external_dispatch_reference` ($\le 100$), and `status_reason_detail` ($\le 500$) are enforced consistently in both Java (`String.length()` UTF-16 code units) and PostgreSQL (`V9__align_payment_unicode_length_constraints.sql`).

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
