# CrossPayNet Change Log

Chronological record of verified engineering checkpoints and corrective updates in the CrossPayNet Evolutionary Modular Monolith.

---

## 2026-10-28 — Phase 9A Checkpoint 3 Corrective Fixes: Ledger Query Contract Isolation & Module Boundary Tightening — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Resolved module boundary violations in the `operations` module by isolating read-only Ledger access and pruning overly broad dependency declarations:
  * **Narrow Read-Only Ledger Contract (`com.crosspaynet.ledger.contracts`)**:
    * Created package `com.crosspaynet.ledger.contracts` with `@NamedInterface("contracts")`.
    * Introduced `LedgerPostingQueryPort` with method `Optional<LedgerPostingView> findPostingByKey(String canonicalPostingKey)`.
    * Introduced contract record `LedgerPostingView(UUID transactionId, Money totalAmount)` strictly exposing only the required data (transaction identifier and total money amount with currency).
    * Updated `LedgerService` to implement `LedgerPostingQueryPort`, delegating internally to `findByPostingKey(LedgerPostingKey)` and enforcing canonical posting key domain validation (`LedgerPostingKey.of`) without duplicating persistence logic.
    * Refactored `SettlementLedgerInconsistencyCheck` to inject `LedgerPostingQueryPort` instead of `LedgerService`, removing all imports and usage of root `com.crosspaynet.ledger` types.
  * **Tightened Operations Module Boundaries (`com.crosspaynet.operations/package-info.java`)**:
    * Removed broad `"ledger"` dependency, replacing it with `"ledger :: contracts"`.
    * Removed redundant broad `"events"` dependency, retaining `"events :: contracts"`.
    * Preserved explicitly required named contracts: `"payment :: contracts"`, `"banking :: contracts"`, `"ledger :: contracts"`, `"events :: contracts"`, `"messaging :: contracts"`, `"common"`, and `"common :: money"`.
  * **Architectural Invariant Enforcement (`OperationsModulithArchitectureTest`)**:
    * Added ArchUnit rule `operationsMustNotDependOnLedgerRootPackage` ensuring `operations` has zero compile-time references to root `com.crosspaynet.ledger` or mutative ledger APIs (`postTransaction`, `reverseTransaction`).
    * Added ArchUnit rule `operationsMustNotDependOnEventsRootPackage` ensuring `operations` only references `events :: contracts`.
    * Verified Spring Modulith boundary verification and database isolation rules.
  * **Tests & Verification**:
    * Added focused unit test suite `LedgerPostingQueryUnitTest` covering posting lookup, transaction ID and amount mapping, absent posting handling, and Ledger domain validation error paths (null, blank, disallowed characters, excessive length, reserved prefix).
    * Updated `SettlementLedgerInconsistencyCheckTest` to verify all 4 settlement reconciliation scenarios against `LedgerPostingQueryPort`.
    * Ran full unit, architecture, and PostgreSQL Testcontainers integration test suites (196 unit/architecture tests and 28 PostgreSQL integration tests passed with zero failures).

---

## 2026-10-28 — Phase 9A Checkpoint 3 Corrective Fixes: Banking Contract & Abandoned Run Recovery — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Addressed the two acceptance review findings for Checkpoint 3:
  * **Narrow Read-Only Banking Port (`com.crosspaynet.banking.contracts`)**:
    * Introduced `BankingReservationQueryPort` in `com.crosspaynet.banking.contracts` with method `Optional<ReservationView> findReservation(ReservationToken reservationToken)`.
    * Updated `BankingService` to implement `BankingReservationQueryPort`.
    * Refactored `PaymentReservationMismatchCheck` to inject `BankingReservationQueryPort` instead of `BankingService`.
    * Removed `"banking"` root package from `operations/package-info.java`, strictly scoping the dependency to `"banking :: contracts"`.
    * Added ArchUnit test `operationsMustNotDependOnBankingRootPackage` ensuring `operations` has zero compile-time references to internal or mutative Banking APIs.
  * **Abandoned Reconciliation-Run Recovery Policy (`com.crosspaynet.operations.application`)**:
    * Added `recoverAbandonedRuns()` to `ReconciliationService` and `ReconciliationServiceImpl`.
    * Introduced `ReconciliationStartupRecoveryListener` (`ApplicationReadyEvent`) recovering orphaned `IN_PROGRESS` runs upon application startup.
    * Integrated abandoned run recovery into `triggerRun` before checking active status, preventing permanent lockouts caused by ungraceful process crashes.
    * Protected actively executing runs using `activeRunId` (`AtomicReference<UUID>`), ensuring active runs are never marked `FAILED`.
    * Terminal Failure Outcome: Transitions genuinely abandoned runs to `FAILED` with explicit audit failure reason `"Abandoned run recovered: execution interrupted by process termination or restart"`, preserving historical check counts and never marking them `COMPLETED`.
  * **Tests & Verification**:
    * Unit tests for recovery (`ReconciliationServiceTest` and `ReconciliationStartupRecoveryListenerTest`).
    * Full PostgreSQL Testcontainers integration tests in `ReconciliationEngineIntegrationTest` covering all 5 regression requirements:
      1. Persisted abandoned run recovered and subsequent run executes successfully.
      2. Currently active run is not incorrectly marked `FAILED`.
      3. Concurrent triggers cannot create overlapping runs or inconsistently recover one another.
      4. Recovery records a terminal failure outcome (`FAILED`), preserving check history and audit failure reason without reporting false success.
      5. Existing check execution and discrepancy deduplication remain fully functional.

---

## 2026-10-28 — Phase 9A Checkpoint 3: Reconciliation Engine & Check Executors — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Implemented the core operational reconciliation engine, four read-only check executors, discrepancy deduplication with repeat detection tracking, and run orchestration in the `operations` module (`com.crosspaynet.operations`):
  * **Messaging Public Contract Extension (`com.crosspaynet.messaging.contracts`)**:
    * Introduced `PaymentMessageQueryPort` in `messaging.contracts` exposing `findLatestMessageByPaymentId(PaymentId)` returning `Optional<PaymentMessageView>`.
    * Implemented in `MessagingService` delegating to `PaymentMessageRepository.findFirstByPaymentIdOrderByCreatedAtDescAttemptNumberDesc`.
    * Updated `operations/package-info.java` to declare allowed dependencies on `messaging :: contracts` and `banking`.
  * **Configuration Properties (`OperationsReconciliationProperties`)**:
    * Configured under `@ConfigurationProperties(prefix = "crosspaynet.operations.reconciliation")`: `staleWorkflowThreshold` (default 30m), `graceWindow` (default 5m), `staleEventPublicationThreshold` (default 15m), and `pageSize` (default 100).
  * **Four Read-Only Check Executors (`com.crosspaynet.operations.application.check`)**:
    * **Check A: `StalePaymentWorkflowCheck` (`STALE_IN_FLIGHT_WORKFLOW`)**:
      * Scans in-flight payment statuses (`INITIATED`, `ROUTING`, `EXECUTING`, `COMPLIANCE_HOLD`) with deterministic keyset pagination.
      * Emits discrepancy for payments older than `cutoffTime = now - staleWorkflowThreshold`.
    * **Check B: `PaymentReservationMismatchCheck` (`RESERVATION_PAYMENT_MISMATCH`)**:
      * Queries Banking module via `BankingService.findReservation(ReservationToken)` using the reservation token stored on the payment.
      * Detects:
        * `COMPLETED` payment with unconsumed reservation (`ACTIVE`, `RELEASED`, or missing).
        * Terminal failed payment (`REJECTED`, `SETTLEMENT_FAILED`, `COMPLIANCE_REJECTED`) with unreleased reservation (`ACTIVE`, `CONSUMED`).
        * In-flight active payment with consumed or released reservation.
    * **Check C: `SettlementLedgerInconsistencyCheck` (`PAYMENT_LEDGER_BREAK`)**:
      * Inspects settled payments against the financial ledger via `LedgerService.findByPostingKey`.
      * Detects missing ledger transaction for settled payments, amount mismatches, and currency mismatches.
    * **Check D: `IncompleteEventPublicationCheck` (`INCOMPLETE_EVENT_PUBLICATION`)**:
      * Scans incomplete outbox event publications older than `now - staleEventPublicationThreshold` via `EventPublicationQueryPort`.
      * Reports publications stuck in the transactional outbox without triggering replay or state mutation.
  * **Discrepancy Deduplication & Repeat Observation (`DiscrepancyRecorder`)**:
    * Enforces deterministic discrepancy keys (e.g., `STALE_WORKFLOW:payment:<paymentId>`, `RESERVATION_MISMATCH:payment:<paymentId>`, `LEDGER_BREAK:payment:<paymentId>`, `STALE_EVENT:pub:<pubId>`).
    * Implements repeat observation: increments `consecutiveDetections`, updates `latestRunId` and `lastObservedAt`, and reopens previously resolved discrepancies if they reappear.
    * Strictly respects data minimization: captures only operational IDs, statuses, monetary amounts, and timestamps in JSONB details. No PII, customer names, account numbers, or raw payloads.
  * **Run Orchestration & Concurrency (`ReconciliationServiceImpl`)**:
    * Orchestrates run lifecycle: `IN_PROGRESS -> COMPLETED | PARTIAL_FAILURE | FAILED`.
    * Enforces concurrency safety via in-memory `ReentrantLock` and database check `ReconciliationRunRepository.existsByStatus(IN_PROGRESS)`.
    * Handles per-check isolation: catches unexpected check exceptions, marks individual checks as `FAILED`, and allows remaining checks to execute.
    * Sets overall run status to `PARTIAL_FAILURE` if at least one check succeeded and at least one check failed; `FAILED` if all checks failed.
  * **Tests & Verification**:
    * **Check Executor Unit Tests (12 tests passing)**: `StalePaymentWorkflowCheckTest` (4 tests), `PaymentReservationMismatchCheckTest` (3 tests), `SettlementLedgerInconsistencyCheckTest` (3 tests), and `IncompleteEventPublicationCheckTest` (2 tests).
    * **Application Service Unit Tests (3 tests passing)**: `ReconciliationServiceTest` (3 tests verifying orchestration, concurrency protection, and partial failure status handling).
    * **PostgreSQL Integration Tests (3 tests passing)**: `ReconciliationEngineIntegrationTest` (3 tests verifying full engine orchestration against PostgreSQL, discrepancy deduplication with consecutive detection increments, and active run rejection).
    * **Operations Module Suite (43 tests passing)**: All 43 domain, architecture, persistence, and application tests passing.
    * **Regression Suite (15 tests passing)**: Checkpoint 1 verification suite passing cleanly.

---

## 2026-10-27 — Phase 9A Checkpoint 2: Operations Domain, Schema & Persistence — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Introduced foundational domain modeling, PostgreSQL schema migrations, and persistence infrastructure for the new `operations` module (`com.crosspaynet.operations`):
  * **Module Architecture & Boundary Enforcement (`com.crosspaynet.operations`)**:
    * **Package Declaration (`package-info.java`)**: Explicitly declares allowed dependencies on `payment::contracts`, `banking::contracts`, `ledger`, `events::contracts`, `security`, and `shared`. Prohibits internal package access or direct database queries to other modules.
    * **Spring Modulith Verification (`OperationsModulithArchitectureTest`)**: Enforces strict module isolation, cycle absence, and encapsulation.
  * **Flyway Migration (`V16__init_operations_schema.sql`)**:
    * Created dedicated schema `operations` and three isolated tables:
      * `operations.reconciliation_run`: Tracks run lifecycle (`IN_PROGRESS -> COMPLETED | FAILED`), trigger provenance (`triggered_by`), counters (`checks_evaluated >= 0`, `discrepancy_count >= 0`), optimistic locking (`version`), and timestamp invariants (`started_at <= completed_at`).
      * `operations.reconciliation_check_execution`: Captures per-check execution results (`COMPLETED | FAILED | SKIPPED`), scanned/discrepancy counts, error summary, and foreign key to parent run with `ON DELETE RESTRICT`. Enforces unique `(run_id, check_name)`.
      * `operations.reconciliation_discrepancy`: Deduplicated discrepancy tracking across runs keyed by unique `discrepancy_key` (`VARCHAR(255) UNIQUE`). Supports rich diagnostic metadata in `JSONB` (`details`), repeat detection tracking (`consecutive_detections >= 1`), operational entity cross-referencing without foreign keys, and status-timestamp coherence for resolutions (`RESOLVED_INTERNALLY`, `RESOLVED_EXTERNALLY`).
    * Preserves zero cross-module foreign key constraints and prevents cascade-deletions (`ON DELETE RESTRICT`).
  * **Pure Domain Models & Lifecycle Invariants (`com.crosspaynet.operations.domain`)**:
    * **Aggregates & Entities**: `ReconciliationRun`, `ReconciliationCheckExecution`, and `ReconciliationDiscrepancy`.
    * **Repeat Observation & Reopen Semantics**: `ReconciliationDiscrepancy.recordRepeatObservation` updates `latestRunId`, advances `lastObservedAt`, increments `consecutiveDetections`, and automatically transitions back to `OPEN` if the discrepancy was previously resolved.
    * **Value Objects & Strongly-Typed Identifiers**: `ReconciliationRunId`, `ReconciliationCheckExecutionId`, `DiscrepancyId`, `DiscrepancyKey`, `ReconciliationRunStatus`, `ReconciliationCheckStatus`, `DiscrepancyType`, and `DiscrepancyStatus`.
    * **Exception Hierarchy**: `OperationsException`, `OperationsValidationException`, `InvalidOperationsStateException`, `OperationsNotFoundException`, and `OperationsConflictException`.
  * **Persistence Layer & Mappings (`com.crosspaynet.operations.infrastructure.persistence`)**:
    * JPA entities: `ReconciliationRunEntity`, `ReconciliationCheckExecutionEntity`, `ReconciliationDiscrepancyEntity` with optimistic locking (`@Version`).
    * Spring Data Repositories: `ReconciliationRunRepository`, `ReconciliationCheckExecutionRepository`, `ReconciliationDiscrepancyRepository` with keyset and status lookup methods.
    * Domain-to-Entity Mapper: `OperationsMapper` aligning entity creation and updates with Spring Data JPA's optimistic locking semantics (`null` version on new entities).
* **Tests & Verification**:
  * **Domain Unit Tests (15 tests passing)**: `ReconciliationRunTest` (7 tests), `ReconciliationCheckExecutionTest` (2 tests), `ReconciliationDiscrepancyTest` (6 tests).
  * **Modulith Architecture Tests (3 tests passing)**: `OperationsModulithArchitectureTest` (3 tests verifying boundaries, encapsulation, and acyclic module dependencies).
  * **PostgreSQL Integration Tests (7 tests passing)**: `OperationsPersistenceIntegrationTest` (7 tests verifying run lifecycle persistence, check executions, JSONB query operators, optimistic locking version increments, unique discrepancy key constraints, and database check constraints).
  * **Regression Suite (15 tests passing)**: Checkpoint 1 verification tests (`PaymentReconciliationPortIntegrationTest`, `BankingReservationQueryIntegrationTest`, `LedgerPostingKeyQueryIntegrationTest`, `EventPublicationQueryPortIntegrationTest`) passing with 0 failures.

---

## 2026-10-27 — Phase 9A Checkpoint 1: Read-Only Public Contracts for Operational Reconciliation — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Implemented non-invasive, strictly read-only public contracts across the Payment, Banking, Ledger, and Events modules to support deterministic, cross-module operational reconciliation with bounded keyset pagination and data minimization:
  * **Payment Module (`com.crosspaynet.payment.contracts`, `@NamedInterface("contracts")`)**:
    * **Reconciliation Query Port (`PaymentReconciliationPort`)**: Exposes `findPaymentsForReconciliation(Set<PaymentStatus>, Instant cutoffTime, PaymentReconciliationCursor, int limit)` returning `PaymentReconciliationPage`.
    * **Deterministic Keyset Pagination**: Stable ordering by `(updatedAt ASC, paymentId ASC)`. Uses `(updatedAt, paymentId)` as keyset continuation cursor, with `hasMore` and `nextCursor` disambiguating scan completion from page limits.
    * **Data Minimization Projection (`PaymentReconciliationRecord`)**: Exposes minimal operational correlation fields (`paymentId`, `status`, `sourceAccountId`, `amount`, `reservationToken`, `routeReference`, `externalDispatchReference`, `ledgerTransactionId`, `statusReasonCode`, `createdAt`, `updatedAt`) while omitting customer PII (beneficiary name, account number, BIC).
    * **Implementation**: Implemented in `PaymentService` via keyset queries in `PaymentRepository`.
  * **Banking Module (`com.crosspaynet.banking.contracts`, `@NamedInterface("contracts")`)**:
    * **Reservation View (`ReservationView`)**: Public read-only record exposing `(reservationToken, accountId, status, amount, paymentReference)`.
    * **Read-Only Inspection (`BankingService.findReservation`)**: Exposes `findReservation(ReservationToken)` returning `Optional<ReservationView>`, backed by `accountRepository.findReservationByToken`. Preserves all reservation and balance invariants with zero state mutations.
  * **Ledger Module (`com.crosspaynet.ledger`)**:
    * **Posting Key Lookup (`LedgerService.findByPostingKey`)**: Added read-only query `findByPostingKey(LedgerPostingKey)` delegating to existing `repository.findByPostingKey` and mapping through `LedgerTransactionMapper` to `LedgerTransactionView`. Strictly read-only without mutations or reversals.
  * **Events Module (`com.crosspaynet.events.contracts`, `@NamedInterface("contracts")`)**:
    * **Publication Query Port (`EventPublicationQueryPort`)**: Exposes `findIncompletePublications(Instant cutoffTime, EventPublicationCursor, int limit)` and `countIncompletePublications(Instant cutoffTime)`.
    * **Safe Metadata Projection (`StalePublicationView`)**: Exposes `(publicationId, listenerId, eventType, publicationDate, completionAttempts, lastResubmissionDate)` without exposing serialized event payloads.
    * **Encapsulated Implementation (`DatabaseEventPublicationQueryService`)**: Reads `system.event_publication` via JDBC with deterministic keyset ordering `(publication_date ASC, id ASC)`. Strictly read-only: never replays, resubmits, or alters publication records.
* **Invariants Maintained**:
  * No Operations module, database migration V16, reconciliation tables, or REST endpoints introduced in Checkpoint 1.
  * Module-to-module reads strictly respect Spring Modulith boundaries via named public contracts. No cross-module repository or database queries.
  * Zero automated mutations: no payment state alterations, no reservation releases/consumptions, no ledger postings, and no publication resubmissions.
* **Tests & Verification**:
  * **Focused Unit Tests (14 tests passing)**: `PaymentReconciliationPortUnitTest` (4 tests), `BankingReservationQueryUnitTest` (3 tests), `LedgerPostingKeyQueryUnitTest` (3 tests), and `EventPublicationQueryServiceUnitTest` (4 tests).
  * **Modulith Architecture Verification (26 tests passing)**: Full verification across all modules (`BankingModulithTest`, `ComplianceModulithArchitectureTest`, `EventsModulithArchitectureTest`, `LedgerArchitectureAndLimitsTest`, `MessagingArchitectureTest`, `PaymentArchitectureTest`, `RoutingArchitectureTest`, `SecurityModulithArchitectureTest`, `SettlementArchitectureTest`) passed with 0 violations.
  * **In-Memory Test Suite (135 tests passing)**: Total of 135 unit, domain, and architecture tests passed with 0 failures, 0 errors, 0 skips.

---

## 2026-10-26 — Phase 8: Security, RBAC & Compliance (`security` and `compliance` Modules) — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Implemented end-to-end security, role-based access control (RBAC), data sanitization/masking, and compliance screening with persistent review records:
  * **Security & Access Control (`com.crosspaynet.security`, `@NamedInterface("contracts")`)**:
    * **Role Definitions (`SecurityRoles`)**: Standardized roles `CUSTOMER`, `COMPLIANCE`, `OPERATIONS`, `TELLER`, and `ADMIN` with `ROLE_` prefixes.
    * **Security Configuration (`SecurityConfiguration`)**: Method-level security enabled via `@EnableMethodSecurity`. Configured `InMemoryUserDetailsManager` backed by externalized, configurable properties under `crosspaynet.security.users` with BCrypt password hashing; default credentials are environment/config driven (no hardcoded passwords in production code).
    * **Secured Payment Portal Facade (`SecuredPaymentPortalService`)**: Application entrypoint enforcing customer ownership boundaries:
      * `initiatePayment`: Verifies that authenticated `ROLE_CUSTOMER` users own the debit account (`bankingService.isAccountOwnedBy`). Rejects unauthorized attempts with `AccessDeniedException`.
      * `getPayment`: Restricts `ROLE_CUSTOMER` to payments originating from their owned accounts; permits staff (`ROLE_OPERATIONS`, `ROLE_TELLER`, `ROLE_ADMIN`, `ROLE_COMPLIANCE`) cross-customer read access.
      * `cancelPayment`: Restricts cancellations to customer-owned payments.
    * **Banking Security Integration**: Annotated `BankingService.fundAccount` with `@PreAuthorize("hasRole('ADMIN')")`, and added public query methods `isAccountOwnedBy(AccountId, UUID)` and `findAccountOwner(AccountId)`.
    * **Log Sanitization & Data Masking (`DataMasker`)**: Masking utilities for PII and sensitive banking identifiers: account numbers masked preserving last 4 characters, names truncated to initial + asterisks, email addresses masked, and payload strings sanitized for structured logging. Integrated into consumer logging (`SampleReliableConsumer`).
  * **Compliance Screening & Review Management (`com.crosspaynet.compliance`, `@NamedInterface("contracts")`)**:
    * **Pure Domain Screening (`SanctionsMatcher`)**: Case-insensitive substring matching against a configurable sanctions keyword list (default: `"SANCTIONED"`, `"TERRORIST"`, `"BLOCKED"`, `"RESTRICTED"`). Checks beneficiary name, account number, and bank BIC. Configurable high-value transaction threshold with a default of USD 10,000 (applies strictly to USD transactions without currency conversion, preventing inaccurate cross-currency comparisons).
    * **Compliance Review Aggregate (`ComplianceReview`)**: Tracks review lifecycle (`PENDING -> RELEASED | REJECTED`). Invariants enforce that reviews must be in `PENDING` to transition, decisions require a non-blank justification, and decisions are immutable once reached.
    * **PostgreSQL Persistence (`V15__init_compliance_schema.sql`, `compliance.compliance_review`)**: Dedicated schema and table with `review_id UUID PRIMARY KEY`, `payment_id UUID NOT NULL UNIQUE`, `status VARCHAR(30)`, `decision VARCHAR(30)`, `justification VARCHAR(500)`, `reviewer VARCHAR(100)`, UTF-16 code unit constraints (`(char_length(...) + regexp_count(..., '[\U00010000-\U0010FFFF]')) <= N`), state-invariant checks (`chk_compliance_review_state_invariants`), optimistic locking `version`, and query indexes. Zero cross-schema foreign keys.
    * **Compliance Service (`ComplianceService`, `ComplianceServiceImpl`)**:
      * `screenPayment`: Screen a payment; clean payments are immediately cleared (`ComplianceStatus.CLEARED`) and promoted to `ROUTING` via `paymentService.approveCompliance`. Flagged payments create a `PENDING` `ComplianceReview` and remain in `COMPLIANCE_HOLD`. Idempotent replay for already-screened payments. Executes under `Propagation.REQUIRES_NEW`.
      * `submitDecision`: `@PreAuthorize("hasRole('COMPLIANCE')")`. Submits an analyst decision (`RELEASE` -> `paymentService.approveCompliance`; `REJECT` -> `paymentService.rejectPayment`). Replays identical decisions idempotently; conflicting decisions throw `ComplianceConflictException`.
      * Read operations: `findReviewById`, `findReviewByPaymentId`.
      * Recovery operations: `resubmitFailedScreenings` resubmits uncompleted `PaymentInstructionReceived` event publications via Spring Modulith's `IncompleteEventPublications` registry.
    * **Event-Driven Auto-Screening & Durable Recovery (`CompliancePaymentListener`)**: Listens for `PaymentInstructionReceived` (`AFTER_COMMIT`) and automatically screens newly initiated payments. Clean payments seamlessly clear to `ROUTING`, while flagged payments remain safely held. If screening encounters a failure, the exception is propagated out of the listener so Spring Modulith marks the publication as incomplete (`completion_date = NULL`) in `system.event_publication`. Automated recovery occurs on startup via `spring.modulith.events.republish-outstanding-events-on-restart: true`, and at runtime via `complianceService.resubmitFailedScreenings()`.
  * **Limitations & Educational Scope**: Authentication is configured for modular monolith demonstration; production identity providers (OAuth2/OIDC/Keycloak) remain future infrastructure. Sanctions matching is a pure educational simulator rule engine and does not connect to real OFAC or UN sanctions feeds.
* **Tests & Verification**:
  * **Focused Phase 8 Tests (42 passing)**: `SanctionsMatcherTest` (8 tests), `ComplianceReviewTest` (7 tests), `DataMaskerTest` (3 tests), `ComplianceModulithArchitectureTest` (2 tests), `CompliancePersistenceIntegrationTest` (5 tests), `SecurityModulithArchitectureTest` (1 test), `SecurityAccessControlTest` (9 tests), and `ComplianceServiceIntegrationTest` (7 tests, including failure exception propagation, incomplete publication retention, and recovery).
  * **Full Suite Tests (261 passing)**: Full Maven build (`mvnw test`) passed across all 35 test suites with **261 tests passing** (`0` failures, `0` errors, `0` skipped) against PostgreSQL 18 and Apache Kafka Testcontainers with Flyway `V1`–`V15`.

---

## 2026-10-26 — Phase 7: Distributed Systems, Kafka & Reliability (`events` Module) — *Accepted & Published to `origin/main` (commit `373f3fd6f4e629679e85a1312646a587af944dd5`)*

* **Scope**: Implemented distributed reliability infrastructure and Apache Kafka integration leveraging Spring Modulith's transactional event publication registry for the transactional outbox pattern, an idempotent database inbox deduplicator, and robust Kafka consumer reliability:
  * **Contracts & Pure Value Objects (`com.crosspaynet.events.contracts`)**: Standardized `EventEnvelope<T>` (`eventId`, `eventType`, `eventVersion`, `occurredAt`, `correlationId`, `causationId`, `payload`), `EventDeduplicator`, `TransactionalConsumerExecutor`, and `ConsumerExecutionResult`. Domain events carry stable immutable identifiers (`eventId`, `occurredAt`, `correlationId`, `causationId`) generated prior to transaction commit.
  * **Transactional Outbox (`system.event_publication` & `@Externalized`)**: Leverages Spring Modulith's built-in event publication registry and `@Externalized` routing mechanism:
    - `FundsReserved` (`crosspaynet.banking.funds-reserved.v1`, partition key `accountId`)
    - `PaymentInstructionReceived` (`crosspaynet.payment.instruction-received.v1`, partition key `paymentId`)
    - `PaymentSentToNetwork` (`crosspaynet.messaging.message-dispatched.v1`, partition key `paymentId`)
    - `PaymentSettledEvent` (`crosspaynet.settlement.payment-settled.v1`, partition key `paymentId`)
    Events are published to PostgreSQL within the business transaction and externalized to Kafka with aggregate partition keys to guarantee per-aggregate order.
  * **Idempotent Inbox & Persistence (`V14__init_system_event_inbox.sql`, `DatabaseEventDeduplicator`, `DatabaseTransactionalConsumerExecutor`)**: Flyway migration `V14` created `system.event_inbox` (`PRIMARY KEY (message_id, consumer_group)`) and `system.sample_consumer_effect`. Conflict-safe `INSERT ... ON CONFLICT (message_id, consumer_group) DO NOTHING` atomically deduplicates retried Kafka deliveries. `TransactionalConsumerExecutor` runs business consumer effects and inbox marking inside a single transactional boundary (`Propagation.REQUIRES_NEW`), guaranteeing exactly-once side effects.
  * **Kafka Producer & Consumer Reliability**: Configured `acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=1`, and `retries=3` for producers. Consumers use `ErrorHandlingDeserializer`, bounded retries (`FixedBackOff` 1s, 3 attempts), and `DeadLetterPublishingRecoverer` routing unretryable or poison-pill payloads (such as malformed envelopes or `EventValidationException`) to dedicated `.DLT` topics with diagnostic headers (`kafka_dlt-exception-fqcn`, `kafka_dlt-original-topic`, etc.).
  * **Module Boundaries**: `events` module exposes public `@NamedInterface("contracts")` contracts while domain modules (`banking`, `payment`, `messaging`, `settlement`) remain decoupled from Kafka and events internals.
* **Tests & Verification**: Added [`EventEnvelopeAndValueObjectsTest`](src/test/java/com/crosspaynet/events/EventEnvelopeAndValueObjectsTest.java) (5 tests), [`EventsModulithArchitectureTest`](src/test/java/com/crosspaynet/events/EventsModulithArchitectureTest.java) (1 test), [`TransactionalOutboxPublicationIntegrationTest`](src/test/java/com/crosspaynet/events/TransactionalOutboxPublicationIntegrationTest.java) (3 tests), and [`KafkaReliabilityAndConsumerIntegrationTest`](src/test/java/com/crosspaynet/events/KafkaReliabilityAndConsumerIntegrationTest.java) (4 tests covering duplicate delivery deduplication, bounded retry with recovery, DLT routing of poison pills with diagnostic headers, and transactional outbox event publication across modules). Focused events suite: **13 tests passing** (`0` failures, `0` errors, `0` skipped). Full suite: **220 tests passing** (`0` failures, `0` errors, `0` skipped) across 34 Surefire test suites with Flyway `V1`–`V14`.

---

## 2026-10-26 — Phase 6 Corrective Update: BIC Canonicalization & Clearing-Account Whitespace Validation — *Accepted & Published to `origin/main` (corrective commit `52c70f01f7cb6b848880d199e23829149a110a66`)*

* **Problem**: Acceptance review of the Phase 6 implementation found that (1) `BicCode` accepts 8- and 11-character BICs without canonicalizing them, so Routing treated `DEUTDEFF` and `DEUTDEFFXXX` as different destinations (causing false `NO_ROUTE_AVAILABLE` rejections and allowing the unique `(destination_bic, currency, priority)` slot to be bypassed), and (2) clearing-account references with leading/trailing Unicode whitespace (e.g. U+00A0) were accepted by Routing but always rejected by `LedgerAccountRef` at settlement time.
* **Fix (Routing module only; no migrations, no dependency changes, no other modules touched)**:
  * Added routing-local `RoutingBics.canonicalize` (appends `XXX` to an 8-character BIC; 11-character BICs, including different branch BICs, are preserved). Applied in `RegisterCorrespondentRouteCommand` (destination and correspondent), `SelectRouteQuery`, `RouteContext`, `CorrespondentRoute.register`/`restore`, and in `RoutingService.routePayment`, `findRoutesForDestination`, and committed-route replay verification, so stored and exposed BICs are canonical and priority-slot conflicts are detected across forms.
  * Clearing-account references are no longer silently trimmed; values whose first or last code point satisfies `Character.isWhitespace` or `Character.isSpaceChar` are rejected in `RegisterCorrespondentRouteCommand`, `CorrespondentRoute` (register and restore), and `RouteContext`, matching `LedgerAccountRef`. Internal spaces remain allowed.
* **Tests & Verification**: Added canonicalization and Unicode-whitespace unit tests to `CorrespondentRouteTest` (now 5) and `shouldTreatEightAndElevenCharacterBicsAsSameDestinationForRoutingConflictsAndReplay` to `RoutingServiceIntegrationTest` (now 9: 8↔11-character routing in both directions, priority-slot conflict across forms, committed-route replay after deactivation, branch BICs not equated). Focused Routing suite: **21 tests passing** (`0` failures, `0` errors, `0` skipped). Full suite: **207 tests passing** (`0` failures, `0` errors, `0` skipped) with Flyway `V1`–`V13`.

---

## 2026-10-26 — Phase 6: Routing & Correspondent Banking (`routing` Module) — *Accepted & Published to `origin/main` (commit `73eed78201642d90bfb1ad394e0be13c83076a98`)*

* **Scope**: Implemented the `routing` application module (`com.crosspaynet.routing` and `com.crosspaynet.routing.contracts` `@NamedInterface("contracts")`), depending only on `payment`, `payment :: contracts`, `common`, and `common :: money`:
  * **Pure Domain (`CorrespondentRoute`, `RoutingLimits`, `PersistedRouteStateException`)**: `CorrespondentRoute` aggregate with `routeReference` (`[A-Za-z0-9:_.-]{1,100}`), `destinationBic`, `correspondentBic`, `currency` (USD, EUR, GBP, INR, JPY, CHF, SGD, AED), `clearingAccountReference` (`1..100`, no ISO control characters), `priority` (`1..1000`), `status` (`ACTIVE`/`INACTIVE`), and monotonic timestamps; idempotent `activate`/`deactivate`; strict `restore(...)` validation.
  * **Persistence (`V12`, `V13`, `CorrespondentRouteEntity`, `CorrespondentRouteMapper`, `CorrespondentRouteRepository`)**: `routing.correspondent_route` with `UNIQUE (route_reference)`, `UNIQUE (destination_bic, currency, priority)`, BIC/currency/priority/status/timestamp checks, UTF-16 supplementary-Unicode length alignment, optimistic-locking `version`, lookup index, and zero cross-schema foreign keys.
  * **Deterministic Selection (`RoutingService.selectRoute`)**: Only `ACTIVE` routes matching destination BIC and currency are eligible, ordered by `priority ASC, routeReference ASC`; throws `NoValidRouteException` when none match.
  * **Conflict-Safe Registration (`registerRoute`)**: `INSERT ... ON CONFLICT DO NOTHING`; identical active registrations replay idempotently; conflicting `routeReference` or priority-slot claims throw `RouteConflictException` (narrow `noRollbackFor`, pre-write path only). Also `activateRoute`, `deactivateRoute`, `findRouteByReference` (includes inactive routes), and `findRoutesForDestination`.
  * **Payment Routing (`routePayment`)**: For `ROUTING` payments, commits the selected route via `PaymentService.commitToExecution` (`ROUTING -> EXECUTING`) or rejects via `PaymentService.rejectPayment(NO_ROUTE_AVAILABLE)` (`ROUTING -> REJECTED`, releasing the Banking reservation in the same transaction). Post-commitment replays resolve and return the originally committed route (even if since deactivated) without reselecting; an unresolvable or mismatched committed route raises `RouteConflictException`. `REJECTED (NO_ROUTE_AVAILABLE)` replays return the existing rejection; other states fail before side effects. Failures mark the transaction rollback-only.
  * **Downstream Compatibility**: Messaging continues to read `routeReference` from `PaymentView`; callers resolve `clearingAccountReference` via `findRouteByReference` and pass it to `SettlementService.settlePayment`. No completed module was modified.
* **Tests & Verification**: Added [`CorrespondentRouteTest`](src/test/java/com/crosspaynet/routing/domain/CorrespondentRouteTest.java) (3), [`RoutingArchitectureTest`](src/test/java/com/crosspaynet/routing/RoutingArchitectureTest.java) (4), [`RoutingPersistenceIntegrationTest`](src/test/java/com/crosspaynet/routing/RoutingPersistenceIntegrationTest.java) (3), and [`RoutingServiceIntegrationTest`](src/test/java/com/crosspaynet/routing/RoutingServiceIntegrationTest.java) (8: registration/conflicts, deterministic selection and promotion, end-to-end routing → messaging → settlement, no-route rejection with reservation release, committed-route replay after deactivation, invalid states, optimistic-lock rollback on both paths, 8-thread concurrent registration and routing). Focused suite: **18 tests passing** (`0` failures, `0` errors, `0` skipped). Full suite: **204 tests passing** (`0` failures, `0` errors, `0` skipped) across 30 Surefire test suites with Flyway `V1`–`V13`.

---

## 2026-10-25 — Phase 5: Financial Messaging (`messaging` Module) (`148775b339cdbd0561c91eeba32a3252ec323dab`) — *Accepted & Published to `origin/main`*

* **Scope**: Implemented the `messaging` application module (`com.crosspaynet.messaging` and `com.crosspaynet.messaging.contracts` `@NamedInterface("contracts")`) to manage ISO 20022-inspired (`pacs.008.001.08`) wire message generation, SHA-256 payload integrity digests (`crosspaynet-message-digest-v1`), PostgreSQL persistence (`V10`–`V11`), and transactional dispatch/acknowledgment/timeout orchestration (`MessagingService`):
  * **Pure Domain & ISO 20022-Inspired Payload Generation (`PaymentMessage`, `Pacs008PayloadBuilder`, `PayloadDigest`, `MessagingLimits`)**: Implemented the pure-Java `PaymentMessage` aggregate root (`GENERATED -> SIGNED -> SENT -> ACKNOWLEDGED | NACKNOWLEDGED | TIMEOUT`), deterministic `pacs.008.001.08` payload construction (`Pacs008PayloadBuilder` with UTF-8 byte-length-prefixed token encoding), and `PayloadDigest` (`crosspaynet-message-digest-v1` 64-character lowercase hex SHA-256 integrity digest — explicitly documented as an educational tamper-detection digest, not a cryptographic digital signature). `PaymentMessage.restore(...)` re-computes `Pacs008PayloadBuilder` and `PayloadDigest` on every database read, rejecting tampered payloads or mismatched digests with `PersistedMessageStateException`.
  * **PostgreSQL Persistence & Integrity Constraints (`V10`, `V11`, `PaymentMessageEntity`, `PaymentMessageMapper`, `PaymentMessageRepository`)**: Added `V10__init_messaging_schema.sql` (`messaging` schema) and `V11__init_messaging_tables.sql` (`messaging.payment_message`) enforcing `uk_payment_message_payment_attempt UNIQUE (payment_id, attempt_number)`, `uk_payment_message_idempotency_key UNIQUE (idempotency_key)`, unconstrained `amount NUMERIC NOT NULL` guarded by `chk_payment_message_amount` (`scale(trim_scale(amount)) <= 4` without silent rounding), UTF-16 supplementary-Unicode length alignment (`(char_length(col) + regexp_count(col, '[\U00010000-\U0010FFFF]')) <= N`), 3VL-safe `chk_payment_message_state_invariants`, operational indexes, and zero cross-module foreign keys.
  * **Independent Retry Attempts & Conflict-Safe Dispatch (`MessagingService.dispatchPaymentMessage`)**: Tracks each network transmission attempt (`1..100`) independently in `messaging.payment_message` with deterministic idempotency key `MSG:{paymentId}:ATTEMPT:{attemptNumber}` claimed via `INSERT ... ON CONFLICT DO NOTHING`. Attempt `1` requires the payment to be in `EXECUTING` (or `PENDING_EXTERNAL` on idempotent replay) and invokes `PaymentService.markDispatched(paymentId, messageId.toString())` (`EXECUTING -> PENDING_EXTERNAL`). Retry attempts (`attemptNumber > 1`) require the payment to be in `PENDING_EXTERNAL` and the immediately preceding attempt (`attemptNumber - 1`) to be in `TIMEOUT`, preserving the payment's original `externalDispatchReference` from attempt `1`.
  * **Acknowledgment, Negative Acknowledgment & Timeout Separation (`acknowledgeMessage`, `nacknowledgeMessage`, `markMessageTimedOut`)**:
    * `acknowledgeMessage` transitions `SENT -> ACKNOWLEDGED` with `acknowledgmentReference` while keeping the `Payment` in `PENDING_EXTERNAL` (leaving final financial settlement to an explicit `SettlementService.settlePayment` call).
    * `nacknowledgeMessage` transitions `SENT -> NACKNOWLEDGED` with `peerRejectionCode` and optional `peerRejectionReason`, and coordinates `PaymentService.failPayment` (`EXTERNAL_NETWORK_REJECTED`) atomically in the same transaction (releasing the Banking reservation).
    * `markMessageTimedOut` transitions `SENT -> TIMEOUT` with optional `timeoutReason`, preserving `Payment` in `PENDING_EXTERNAL` and its Banking reservation `ACTIVE` so a subsequent retry attempt can be dispatched.
* **Tests & Verification**: Added [`PaymentMessageTest`](src/test/java/com/crosspaynet/messaging/domain/PaymentMessageTest.java) (4 unit tests), [`MessagingArchitectureTest`](src/test/java/com/crosspaynet/messaging/MessagingArchitectureTest.java) (4 architecture/Modulith tests), [`MessagingPersistenceIntegrationTest`](src/test/java/com/crosspaynet/messaging/MessagingPersistenceIntegrationTest.java) (3 persistence/constraint/UTF-16/tamper tests), and [`MessagingServiceIntegrationTest`](src/test/java/com/crosspaynet/messaging/MessagingServiceIntegrationTest.java) (9 end-to-end service integration tests including 8-thread concurrent dispatch race, timeout-to-retry flow, atomic NACK failure rollback, and ACK-to-Settlement boundary separation). Focused suite: **20 tests passing** (`0` failures, `0` errors, `0` skipped). Full suite: **186 tests passing** (`0` failures, `0` errors, `0` skipped) across 26 Surefire test suites with Flyway `V1`–`V11`.

---

## 2026-10-25 — Settlement Module: Transactional Settlement Orchestration (`a59e11db3fe7c2e9cd7fcee3681a7fea740c98ac`) — *Accepted & Published to `origin/main`*

* **Scope**: Implemented the `settlement` application module (`com.crosspaynet.settlement` and `com.crosspaynet.settlement.contracts` `@NamedInterface("contracts")`) to coordinate atomic cross-module settlement across `Banking`, `Ledger`, and `Payment` without new database tables or schema migrations:
  * **Contracts (`SettlePaymentCommand`, `SettlementResult`, `SettlementException` hierarchy)**: Defined `SettlePaymentCommand(PaymentId paymentId, String clearingAccountReference, String settlementReference)` requiring an explicit caller-supplied `INTERNAL` clearing account reference (validated via `LedgerAccountRef.of(LedgerAccountType.INTERNAL, clearingAccountReference)`) and trimmed external settlement acknowledgment reference ($\le 255$ characters, non-blank, no ISO control characters), never inferring the clearing account from `routeReference`.
  * **Transactional Settlement Orchestration (`SettlementService.settlePayment`)**: Validates that the target payment is in `PENDING_EXTERNAL`, then executes within a single `@Transactional(propagation = Propagation.REQUIRED)` boundary: (1) `BankingService.consumeReservation` (`ACTIVE -> CONSUMED`), (2) `LedgerService.postTransaction` with deterministic key `PAYMENT_SETTLEMENT:{paymentId}` and a balanced 2-line single-currency `STANDARD` entry (`DEBIT` `BANKING_ACCOUNT` `sourceAccountId`, `CREDIT` `INTERNAL` `clearingAccountReference`), and (3) `PaymentSettlementPort.recordSettlement` (`PENDING_EXTERNAL -> SETTLED` with `ledgerTransactionId`). Explicitly marks the active transaction rollback-only before rethrowing any failure from the mutation sequence so `LedgerValidationException` (`noRollbackFor` on `LedgerService.postTransaction`) or optimistic-lock failures cannot leave a consumed reservation or orphaned ledger entry committed.
  * **Conflict-Safe Idempotent Replay**: When a payment is already `SETTLED`, verifies the existing `PAYMENT_SETTLEMENT:{paymentId}` ledger fingerprint against `clearingAccountReference` and `settlementReference` (`LedgerPostingKeyConflictException` on mismatch) and verifies that `payment.ledgerTransactionId()` matches the posted transaction (`SettlementConflictException` on mismatch) before returning `PostingOutcome.ALREADY_POSTED`.
* **Tests & Verification**: Added [`SettlementArchitectureTest`](src/test/java/com/crosspaynet/settlement/SettlementArchitectureTest.java) (4 architecture/Modulith tests) and [`SettlementServiceIntegrationTest`](src/test/java/com/crosspaynet/settlement/SettlementServiceIntegrationTest.java) (10 integration tests against PostgreSQL 18 Testcontainers covering happy-path settlement, idempotent and conflicting retries, pre-existing conflicting/matching ledger keys, non-`PENDING_EXTERNAL` state rejections, frozen account/released reservation rollbacks, final-step optimistic-lock rollback across all three modules, 8-thread concurrent settlement race, and command validation). Focused suite: **14 tests passing** (`0` failures, `0` errors, `0` skipped). Full suite: **166 tests passing** (`0` failures, `0` errors, `0` skipped) across 22 Surefire test suites with Flyway `V1`–`V9`.

---

## 2026-10-25 — Phase 4 Checkpoint 3 (CP3): `PaymentService` Orchestration & Integration Verification (`547fcaae78a711a89f1ded45272109ca0648f6e2`) — *Accepted & Published to `origin/main`*

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
