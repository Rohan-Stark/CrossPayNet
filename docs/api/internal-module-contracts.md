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

### `Banking.FindReservation` (Phase 9A)
* **Purpose**: Retrieve an immutable read-only view of a fund reservation by its unique `ReservationToken` for operational reconciliation without exposing internal persistence entities or modifying balances.
* **Exposed By**: Banking Module (`BankingService.findReservation(ReservationToken reservationToken)`)
* **Consumed By**: Operations Module / Reconciliation Services
* **Input Concepts**:
  * `ReservationToken` (`com.crosspaynet.banking.contracts.ReservationToken`)
* **Output Concepts**:
  * `Optional<ReservationView>` where `ReservationView` exposes `reservationToken`, `accountId`, `status` (`ReservationStatus { ACTIVE, RELEASED, CONSUMED }`), `amount`, and `paymentReference`.
* **Behavior**: Synchronous read-only query (`readOnly = true`). Preserves all financial and reservation invariants.

### `Routing.SelectRoute`, `Routing.RoutePayment` & `Routing.PublicContracts` (Phase 6)
* **Purpose**: Select the correspondent route for a cross-border transfer and record the routing decision on the Payment.
* **Exposed By**: `com.crosspaynet.routing.RoutingService`:
  * `selectRoute(SelectRouteQuery(BicCode destinationBic, Currency currency))`: `@Transactional(readOnly = true)`. Returns the first `ACTIVE` route matching both values ordered by `priority ASC, routeReference ASC`; throws `NoValidRouteException` if none.
  * `registerRoute(RegisterCorrespondentRouteCommand)`: `@Transactional(noRollbackFor = RouteConflictException.class)`. Conflict-safe `INSERT ... ON CONFLICT DO NOTHING`; identical active registration replays; conflicting `routeReference` or `(destinationBic, currency, priority)` throws `RouteConflictException` before any write.
  * `activateRoute(String)` / `deactivateRoute(String)`: idempotent `ACTIVE <-> INACTIVE`; `RouteNotFoundException` if absent.
  * `findRouteByReference(String)` (includes inactive routes) and `findRoutesForDestination(BicCode, Currency)`: read-only.
  * `routePayment(PaymentId)`: `@Transactional(propagation = Propagation.REQUIRED)`. For `ROUTING` payments, calls `PaymentService.commitToExecution` (`ROUTE_COMMITTED`) or `PaymentService.rejectPayment(NO_ROUTE_AVAILABLE)` (`REJECTED_NO_ROUTE`, releasing the Banking reservation atomically). For `EXECUTING`, `PENDING_EXTERNAL`, `SETTLED`, or `FAILED`, returns the originally committed route by reference without reselecting; throws `RouteConflictException` if it cannot be resolved or mismatches the instruction. Replays `REJECTED (NO_ROUTE_AVAILABLE)`; other states throw `InvalidPaymentStateException` before side effects. Failures mark the transaction rollback-only.
* **Contracts** (`com.crosspaynet.routing.contracts`, `@NamedInterface("contracts")`): `RouteId`, `RouteStatus`, `RouteContext` (including `clearingAccountReference`), `RegisterCorrespondentRouteCommand`, `SelectRouteQuery`, `PaymentRoutingOutcome`, `PaymentRoutingResult`, and `RoutingException` → `RoutingValidationException` → `RouteConflictException`; `RouteNotFoundException`; `NoValidRouteException`.
* **Consumed By**: Internal orchestration callers; settlement callers resolve `clearingAccountReference` via `findRouteByReference` and pass it to `SettlementService.settlePayment`.
* **Boundary**: Routing depends only on `payment`, `payment :: contracts`, `common`, and `common :: money`.

### `Compliance.ScreenPayment` & `Compliance.SubmitDecision` (Phase 8)
* **Purpose**: Screen incoming payment instructions against sanctions keyword lists and high-value transaction thresholds; manage persistent manual analyst reviews and decision clearance.
* **Exposed By**: `com.crosspaynet.compliance.contracts.ComplianceService`:
  * `screenPayment(ScreenPaymentCommand)`: `@Transactional(propagation = Propagation.REQUIRES_NEW)`. Screens payment beneficiary details and amount. Clean payments are immediately approved (`paymentService.approveCompliance`); flagged payments are placed into `PENDING` review in `compliance.compliance_review` and left in `COMPLIANCE_HOLD`. Idempotent replay for already-screened payments.
  * `submitDecision(SubmitComplianceDecisionCommand)`: `@Transactional(propagation = Propagation.REQUIRED)` and `@PreAuthorize("hasRole('COMPLIANCE')")`. Submits analyst decision (`RELEASE` or `REJECT`) with mandatory justification. Releases review and approves payment or rejects review and rejects payment. Conflicting decisions throw `ComplianceConflictException`.
  * `resubmitFailedScreenings()`: `@Transactional`. Resubmits incomplete screening event publications from the Spring Modulith event publication registry (`system.event_publication`) for durable recovery.
  * `findReviewById(ComplianceReviewId)` / `findReviewByPaymentId(PaymentId)`: Read-only queries returning `Optional<ComplianceReviewView>`.
* **Contracts** (`com.crosspaynet.compliance.contracts`, `@NamedInterface("contracts")`): `ComplianceReviewId`, `ComplianceStatus`, `ComplianceDecision`, `ComplianceReviewView`, `ScreenPaymentCommand`, `SubmitComplianceDecisionCommand`, and `ComplianceException` hierarchy (`ComplianceValidationException`, `ComplianceNotFoundException`, `ComplianceConflictException`).
* **Consumed By**: `CompliancePaymentListener` (asynchronous auto-screening on `PaymentInstructionReceived`) and compliance analyst operations callers.
* **Boundary**: Depends on `payment :: contracts`, `common`, and `common :: money`.

### `Security.SecuredPaymentPortalService` (Phase 8)
* **Purpose**: Enforce customer debit account ownership and role-based access control across payment portal operations.
* **Exposed By**: `com.crosspaynet.security.contracts.SecuredPaymentPortalService`:
  * `initiatePayment(InitiatePaymentCommand)`: Checks that the authenticated `ROLE_CUSTOMER` user owns the debit account (`bankingService.isAccountOwnedBy`). Rejects unauthorized customer access with `AccessDeniedException`.
  * `getPayment(PaymentId)`: Ensures `ROLE_CUSTOMER` users can only read payments belonging to accounts they own. Allows operations/teller/compliance staff cross-customer read access.
  * `cancelPayment(CancelPaymentCommand)`: Enforces customer ownership before forwarding cancellation requests to `paymentService`.
* **Contracts** (`com.crosspaynet.security.contracts`, `@NamedInterface("contracts")`): `SecuredPaymentPortalService` and `SecurityRoles`.
* **Consumed By**: External API and user portal controllers.
* **Boundary**: Depends on `payment :: contracts`, `banking :: contracts`, and `common`.

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

### `Ledger.FindByPostingKey` (Phase 9A)
* **Purpose**: Retrieve an immutable read-only view of a posted `LedgerTransaction` and its entry lines by deterministic `LedgerPostingKey` (e.g. `PAYMENT_SETTLEMENT:{paymentId}`) for cross-module reconciliation checks.
* **Exposed By**: Ledger Module (`LedgerService.findByPostingKey(LedgerPostingKey postingKey)`)
* **Consumed By**: Operations Module / Reconciliation Services
* **Input Concepts**:
  * `postingKey`: `LedgerPostingKey`
* **Output Concepts**:
  * `Optional<LedgerTransactionView>`
* **Behavior**: Synchronous read-only query (`readOnly = true`). Never modifies ledger state or executes reversals.

### `Payment.PublicContracts`, `Payment.OrchestrationService` & `Payment.SettlementPort` (Phase 4 CP1–CP3)
* **Purpose**: Provide transactional orchestration of the cross-border payment workflow (`PaymentService`), narrow public named interfaces for Payment commands/views (`payment :: contracts`), and final settlement outcome recording (`payment :: settlement`), without exposing `payment.domain` or `payment.infrastructure.persistence`.
* **Exposed By**:
  * `com.crosspaynet.payment.PaymentService` (implements `PaymentSettlementPort`):
    * `initiatePayment(InitiatePaymentCommand)`: `@Transactional(propagation = Propagation.REQUIRED, noRollbackFor = PaymentReferenceConflictException.class)`. Validates the command and constructs a candidate `Payment` in memory before claiming `(source_account_id, payment_reference)` via `INSERT ... ON CONFLICT (source_account_id, payment_reference) DO NOTHING`. On duplicate claims, returns the existing `PaymentView` when `InstructionFingerprint` matches or throws `PaymentReferenceConflictException` (before any database write in this invocation). On first claims, calls `BankingService.reserveFunds` in the shared transaction: transitions `VALIDATING -> COMPLIANCE_HOLD` on success; catches pre-write business rejections (`InsufficientFundsException`, `InvalidAccountStateException`, `CurrencyMismatchException`) to commit an auditable `REJECTED` payment (`reservationToken = null`); and marks the transaction rollback-only on `AccountNotFoundException`, corrupted persisted state, or unexpected runtime errors so `VALIDATING` never remains as a committed resting state.
    * `approveCompliance(PaymentId)`: `@Transactional(propagation = Propagation.REQUIRED)`. Transitions `COMPLIANCE_HOLD -> ROUTING` (idempotent if already `ROUTING`).
    * `commitToExecution(CommitPaymentExecutionCommand)`: `@Transactional(propagation = Propagation.REQUIRED)`. Transitions `ROUTING -> EXECUTING` at the **Financial Commitment Point** with `routeReference` (idempotent if already `EXECUTING` with the same `routeReference`).
    * `markDispatched(MarkPaymentDispatchedCommand)`: `@Transactional(propagation = Propagation.REQUIRED)`. Transitions `EXECUTING -> PENDING_EXTERNAL` with `externalDispatchReference` (idempotent if already `PENDING_EXTERNAL` with the same `externalDispatchReference`).
    * `cancelPayment(CancelPaymentCommand)`: `@Transactional(propagation = Propagation.REQUIRED)`. Validates pre-commitment state (`VALIDATING`, `COMPLIANCE_HOLD`, `ROUTING`) and reason detail in memory before calling `BankingService.releaseReservation` and persisting `CANCELLED` (`CUSTOMER_CANCELLED`). Uses default rollback behavior so any failure after reservation release rolls back both the Banking release and the Payment transition together.
    * `rejectPayment(RejectPaymentCommand)`: `@Transactional(propagation = Propagation.REQUIRED)`. Validates pre-commitment state (`COMPLIANCE_HOLD` or `ROUTING`) and reason code (`COMPLIANCE_REJECTED` or `NO_ROUTE_AVAILABLE`) in memory before calling `BankingService.releaseReservation` and persisting `REJECTED`. Uses default rollback behavior for atomic reservation release and state update.
    * `failPayment(FailPaymentCommand)`: `@Transactional(propagation = Propagation.REQUIRED)`. Validates post-commitment pre-settlement state (`EXECUTING` or `PENDING_EXTERNAL`) and confirmed failure reason code (`EXTERNAL_NETWORK_REJECTED` or `OPERATIONAL_FAILURE`) in memory before calling `BankingService.releaseReservation` and persisting `FAILED`. Unknown external outcomes remain in `PENDING_EXTERNAL` with their reservation active. Uses default rollback behavior for atomic reservation release and state update.
    * `recordSettlement(RecordPaymentSettlementCommand)`: `@Transactional(propagation = Propagation.REQUIRED)`. Implements `PaymentSettlementPort` (`PENDING_EXTERNAL -> SETTLED` with `ledgerTransactionId`, idempotent if already `SETTLED` with the same `ledgerTransactionId`). Does **not** call `BankingService.consumeReservation` or `LedgerService.postTransaction`.
    * `findPayment(PaymentId)` & `findBySourceAccountAndReference(AccountId, PaymentReference)`: `@Transactional(readOnly = true)` queries returning `Optional<PaymentView>`.
    * `findPaymentsForReconciliation(Set<PaymentStatus>, Instant, PaymentReconciliationCursor, int)`: `@Transactional(readOnly = true)`. Implements `PaymentReconciliationPort` providing bounded keyset-paginated scans with deterministic tie-breaking over `(updatedAt ASC, paymentId ASC)` and continuation support (`PaymentReconciliationPage`), returning minimal `PaymentReconciliationRecord` projections without customer PII.
  * `com.crosspaynet.payment.contracts` (`@NamedInterface("contracts")`): `PaymentId`, `BicCode`, `BeneficiaryDetails` (`beneficiaryName` $\le 140$ UTF-16 code units, `beneficiaryAccountNumber` $\le 34$ UTF-16 code units, `beneficiaryBankBic`), `PaymentInstruction` (`sourceAccountId`, `paymentReference` $\le 255$ UTF-16 code units, `beneficiary`, `amount`), `PaymentStatus`, `PaymentReasonCode`, `PaymentView`, command records (`InitiatePaymentCommand`, `CancelPaymentCommand`, `RejectPaymentCommand`, `CommitPaymentExecutionCommand`, `MarkPaymentDispatchedCommand`, `FailPaymentCommand`), and the `PaymentException` hierarchy (`PaymentValidationException`, `PaymentNotFoundException`, `InvalidPaymentStateException`, `PaymentReferenceConflictException`).
  * `com.crosspaynet.payment.settlement` (`@NamedInterface("settlement")`): `PaymentSettlementPort` and `RecordPaymentSettlementCommand(PaymentId paymentId, UUID ledgerTransactionId)`.
* **Boundary & Validation Alignment**:
  * `Payment` does not depend on `Ledger`; final settlement orchestration (`BankingService.consumeReservation`, `LedgerService.postTransaction`, and `PaymentSettlementPort.recordSettlement`) is owned by the `Settlement` module.
  * Text length limits on `payment_reference` ($\le 255$), `beneficiary_name` ($\le 140$), `beneficiary_account_number` ($\le 34$), `route_reference` ($\le 100$), `external_dispatch_reference` ($\le 100$), and `status_reason_detail` ($\le 500$) are enforced consistently in both Java (`String.length()` UTF-16 code units) and PostgreSQL (`V9__align_payment_unicode_length_constraints.sql`).

### `Settlement.SettlePayment` & `Settlement.PublicContracts`
* **Purpose**: Atomically finalize financial settlement for a dispatched (`PENDING_EXTERNAL`) payment by consuming its operational Banking reservation, posting a balanced double-entry Ledger transaction, and recording the resulting `ledgerTransactionId` on the Payment aggregate.
* **Exposed By**:
  * `com.crosspaynet.settlement.SettlementService`:
    * `settlePayment(SettlePaymentCommand)`: `@Transactional(propagation = Propagation.REQUIRED)`.
    * `postingKeyFor(PaymentId)`: Deterministically derives `LedgerPostingKey.of("PAYMENT_SETTLEMENT:" + paymentId.getValue())`.
  * `com.crosspaynet.settlement.contracts` (`@NamedInterface("contracts")`):
    * `SettlePaymentCommand(PaymentId paymentId, String clearingAccountReference, String settlementReference)`: Validates `paymentId != null`, validates `clearingAccountReference` via `LedgerAccountRef.of(LedgerAccountType.INTERNAL, clearingAccountReference)` (trimmed, non-blank, `1..100` Unicode code points, no ISO control characters), and validates/trims `settlementReference` (non-blank, $\le 255$ characters, no ISO control characters). Never infers `clearingAccountReference` from `PaymentView.routeReference()`.
    * `SettlementResult(PaymentId paymentId, ReservationToken reservationToken, LedgerTransactionId ledgerTransactionId, PostingOutcome ledgerPostingOutcome, PaymentView payment, Instant settledAt)`.
    * `SettlementException` $\rightarrow$ `SettlementValidationException` $\rightarrow$ `SettlementConflictException`.
* **Consumed By**: Internal settlement callers / Network acknowledgment handlers / Operations.
* **Behavior & Failure Semantics**:
  * **Preconditions**: Loads `PaymentView` via `PaymentService.findPayment(paymentId)` (throws `PaymentNotFoundException` if absent). Rejects any status other than `PENDING_EXTERNAL` or `SETTLED` with `InvalidPaymentStateException` before performing any Banking or Ledger write.
  * **Atomic Execution Sequence (`PENDING_EXTERNAL`)**:
    1. `BankingService.consumeReservation(sourceAccountId, reservationToken)` (`ACTIVE -> CONSUMED`).
    2. `LedgerService.postTransaction(PostLedgerTransactionCommand)` with key `PAYMENT_SETTLEMENT:{paymentId}`, currency `payment.instruction().amount().getCurrency()`, two balanced lines (`DEBIT` `BANKING_ACCOUNT` `sourceAccountId.getValue().toString()`, `CREDIT` `INTERNAL` `clearingAccountReference`), `externalReference = settlementReference`, and `description = "Settlement for payment " + paymentId.getValue()`.
    3. `PaymentSettlementPort.recordSettlement(new RecordPaymentSettlementCommand(paymentId, ledgerResult.transactionId().getValue()))` (`PENDING_EXTERNAL -> SETTLED`).
  * **Rollback Safety**: Any runtime exception or error during the mutation sequence explicitly marks the active Spring transaction rollback-only (`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`) before rethrowing, guaranteeing that `BankingService.consumeReservation`, `LedgerService.postTransaction`, and `PaymentSettlementPort.recordSettlement` roll back together even when `LedgerValidationException` (`noRollbackFor` on `LedgerService.postTransaction`) is caught by an outer caller.
  * **Idempotent Replay vs. Conflict (`SETTLED`)**: When the payment is already `SETTLED`, re-evaluates `LedgerService.postTransaction` with key `PAYMENT_SETTLEMENT:{paymentId}` to verify the financial fingerprint (`clearingAccountReference` and `settlementReference`; throws `LedgerPostingKeyConflictException` on mismatch), verifies that `ledgerResult.outcome() == PostingOutcome.ALREADY_POSTED` and `ledgerResult.transactionId().getValue().equals(payment.ledgerTransactionId())` (throws `SettlementConflictException` on mismatch), ensures `BankingService.consumeReservation` and `PaymentSettlementPort.recordSettlement` are idempotently satisfied, and returns `SettlementResult` with `PostingOutcome.ALREADY_POSTED`.

### `Messaging.PublicContracts` & `Messaging.OrchestrationService` (Phase 5)
* **Purpose**: Generate deterministic ISO 20022-inspired (`pacs.008.001.08`) wire messages with SHA-256 payload integrity digests (`crosspaynet-message-digest-v1`), persist per-attempt transmission state in `messaging.payment_message`, and coordinate network dispatch, positive/negative peer acknowledgments, and transmission timeouts across the `Messaging` and `Payment` modules.
* **Exposed By**:
  * `com.crosspaynet.messaging.MessagingService`:
    * `dispatchPaymentMessage(DispatchPaymentMessageCommand)`: `@Transactional(propagation = Propagation.REQUIRED, noRollbackFor = MessageConflictException.class)`. Validates `paymentId`, `attemptNumber` (`1..100`), and `senderBic`. Checks for an existing `(paymentId, attemptNumber)` record first (returning the existing `PaymentMessageView` when `senderBic` matches or throwing `MessageConflictException` when `senderBic` differs). For new dispatches:
      * Attempt `1` requires the target `Payment` to be in `EXECUTING` (or `PENDING_EXTERNAL` on concurrent replay), generates and integrity-signs a `Pacs008` `PaymentMessage` (`GENERATED -> SIGNED -> SENT`), claims `(payment_id, attempt_number)` via `INSERT ... ON CONFLICT DO NOTHING`, and invokes `PaymentService.markDispatched(paymentId, messageId.toString())` (`EXECUTING -> PENDING_EXTERNAL`).
      * Retry attempts (`attemptNumber > 1`) require the target `Payment` to be in `PENDING_EXTERNAL` and the immediately preceding attempt (`attemptNumber - 1`) to exist in `TIMEOUT`, preserving `Payment`'s original `externalDispatchReference` from attempt `1`.
    * `acknowledgeMessage(AcknowledgeMessageCommand)`: `@Transactional(propagation = Propagation.REQUIRED)`. Transitions `SENT -> ACKNOWLEDGED` with trimmed `acknowledgmentReference` ($\le 100$ UTF-16 code units, no ISO control characters; idempotent if already `ACKNOWLEDGED` with the same reference). Does **not** settle the payment or post Ledger entries (`SettlementService.settlePayment` remains the separate settlement boundary).
    * `nacknowledgeMessage(NacknowledgeMessageCommand)`: `@Transactional(propagation = Propagation.REQUIRED)`. Validates that the target `Payment` is in `PENDING_EXTERNAL`, transitions the message `SENT -> NACKNOWLEDGED` with `peerRejectionCode` (`[A-Za-z0-9_.-]{1,35}`) and optional `peerRejectionReason` ($\le 500$ UTF-16 code units), and invokes `PaymentService.failPayment(paymentId, EXTERNAL_NETWORK_REJECTED, detail)` atomically in the same transaction (which releases the operational Banking reservation).
    * `markMessageTimedOut(TimeoutMessageCommand)`: `@Transactional(propagation = Propagation.REQUIRED)`. Validates that the target `Payment` is in `PENDING_EXTERNAL` and transitions the message `SENT -> TIMEOUT` with optional `timeoutReason` ($\le 500$ UTF-16 code units), keeping `Payment` in `PENDING_EXTERNAL` and its Banking reservation `ACTIVE` so a subsequent retry attempt (`attemptNumber + 1`) can be dispatched.
    * `findMessage(MessageId)`, `findByPaymentAndAttempt(PaymentId, int)`, & `findMessagesForPayment(PaymentId)`: `@Transactional(readOnly = true)` queries returning `Optional<PaymentMessageView>` or `List<PaymentMessageView>` ordered by `attemptNumber ASC`.
  * `com.crosspaynet.messaging.contracts` (`@NamedInterface("contracts")`): `MessageId`, `PaymentMessageType` (`PACS_008`), `PaymentMessageStatus` (`GENERATED`, `SIGNED`, `SENT`, `ACKNOWLEDGED`, `NACKNOWLEDGED`, `TIMEOUT`), `PaymentMessageView`, command records (`DispatchPaymentMessageCommand`, `AcknowledgeMessageCommand`, `NacknowledgeMessageCommand`, `TimeoutMessageCommand`), and the `MessagingException` hierarchy (`MessagingValidationException`, `MessageNotFoundException`, `InvalidMessageStateException`, `MessageConflictException`).
* **Boundary & Security Notes**:
  * `PayloadDigest` (`crosspaynet-message-digest-v1`) is an educational SHA-256 tamper-detection digest verified on `sign()` and on `PaymentMessage.restore(...)`, not a cryptographic digital signature (no asymmetric keys or PKI).
  * `Messaging` depends only on `payment`, `payment :: contracts`, `common`, and `common :: money`; it never imports `banking`, `ledger`, or `settlement`.

### `Events.EventPublicationQueryPort` (Phase 9A)
* **Purpose**: Provide encapsulated, read-only inspection of incomplete event publications in `system.event_publication` with bounded keyset pagination and data minimization.
* **Exposed By**: Events Module (`EventPublicationQueryPort` implemented by `DatabaseEventPublicationQueryService`)
* **Consumed By**: Operations Module / Reconciliation Services
* **Input Concepts**:
  * `cutoffTime`: `Instant`
  * `cursor`: `EventPublicationCursor(Instant publicationDate, UUID publicationId)`
  * `limit`: `int` (capped at 500)
* **Output Concepts**:
  * `EventPublicationPage(List<StalePublicationView> items, boolean hasMore, Optional<EventPublicationCursor> nextCursor)`
  * `countIncompletePublications(Instant cutoffTime)` returning total uncompleted count
* **Behavior**: Synchronous read-only JDBC query (`readOnly = true`). Omits raw serialized event payloads to enforce data minimization. Strictly inspects publications; never resubmits, replays, or completes them.

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

---

## 3. Operations & Reconciliation REST API (Phase 9A Checkpoint 4)

Secure operational reconciliation and discrepancy management endpoints exposed under `/api/v1/operations/reconciliation/**`. All endpoints enforce Role-Based Access Control (RBAC) via Spring Security method security, requiring `ROLE_OPERATIONS` or `ROLE_ADMIN`. Operator identity is derived strictly from the authenticated security principal (`authentication.getName()`).

### `POST /api/v1/operations/reconciliation/runs`
* **Purpose**: Synchronously execute an operational reconciliation run across all four registered check executors (`StalePaymentWorkflowCheck`, `PaymentReservationMismatchCheck`, `SettlementLedgerInconsistencyCheck`, and `IncompleteEventPublicationCheck`).
* **Security**: `hasAnyRole('OPERATIONS', 'ADMIN')`, requires valid CSRF token.
* **Operator Derivation**: Extracted from authenticated Spring Security principal.
* **Responses**:
  * `201 Created`: Returns `ReconciliationRunDetailResponse` with `Location: /api/v1/operations/reconciliation/runs/{runId}`.
  * `409 Conflict`: Returned when an active reconciliation run is currently in progress.
  * `401 Unauthorized` / `403 Forbidden`: Unauthenticated or unauthorized callers.

### `GET /api/v1/operations/reconciliation/runs`
* **Purpose**: Retrieve historical reconciliation runs with deterministic ordering (`startedAt DESC, runId DESC`) and bounded pagination.
* **Parameters**: `page` (default: 0, must be >= 0), `size` (default: 20, between 1 and 100).
* **Responses**:
  * `200 OK`: `PagedResponse<ReconciliationRunResponse>`.
  * `400 Bad Request`: Out-of-bounds pagination parameters.

### `GET /api/v1/operations/reconciliation/runs/{runId}`
* **Purpose**: Inspect a single reconciliation run and its individual check outcomes.
* **Responses**:
  * `200 OK`: `ReconciliationRunDetailResponse` containing run status, timing, counts, and check execution items.
  * `404 Not Found`: Non-existent run ID.
  * `400 Bad Request`: Malformed run UUID.

### `GET /api/v1/operations/reconciliation/discrepancies`
* **Purpose**: Query operational discrepancies with deterministic ordering (`lastObservedAt DESC, discrepancyId DESC`), bounded pagination, and optional filters.
* **Parameters**:
  * `status`: Optional lifecycle filter (`OPEN`, `RESOLVED_EXTERNALLY`, `ACKNOWLEDGED`).
  * `type`: Optional discrepancy category (`STALE_IN_FLIGHT_WORKFLOW`, `RESERVATION_PAYMENT_MISMATCH`, `PAYMENT_LEDGER_BREAK`, `INCOMPLETE_EVENT_PUBLICATION`).
  * `runId`: Optional run filter (matches first or latest run).
  * `page` (default: 0), `size` (default: 20).
* **Responses**:
  * `200 OK`: `PagedResponse<ReconciliationDiscrepancyResponse>`.
  * `400 Bad Request`: Invalid status/type enum or invalid pagination.

### `GET /api/v1/operations/reconciliation/discrepancies/{discrepancyId}`
* **Purpose**: Retrieve details of an individual discrepancy finding.
* **Responses**:
  * `200 OK`: `ReconciliationDiscrepancyResponse`.
  * `404 Not Found`: Discrepancy not found.
  * `400 Bad Request`: Malformed discrepancy UUID.
