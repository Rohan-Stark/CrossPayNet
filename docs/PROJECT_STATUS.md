# Project Status

Current Phase: Phase 8 — Security & Compliance
Current Subphase: Implemented Locally (Pending Acceptance Review)

## Completed Phases
* Phase 0 — Engineering Constitution
* Phase 1.A — Domain Discovery, Problem Definition & System Boundaries
* Phase 1.B.1 — Monolith vs Modular Monolith vs Microservices
* Phase 1.B.2.A — Backend Language & Framework Evaluation
* Phase 1.B.3 — Final Technology Stack & Architecture Decisions
* Phase 1.B.4 — Domain Model Refinement
* Phase 1.B.5 — Use Cases & Interaction Flows
* Phase 1.B.6 — System Architecture Design
* Phase 1.B.7 — API & Contract Design
* Phase 1.B.8 — Persistence Architecture
* Phase 1.B.9 — Architecture Review
* Phase 1.B.10 — Phase 1 Acceptance
* **Phase 2 — Banking Foundation** ✅
  * Checkpoint 1 — Foundation ✅
  * Checkpoint 2 — Persistence & Event Registry ✅
  * Checkpoint 3 — Pure Domain & Shared Kernel ✅
  * Checkpoint 4 — Infrastructure & Capabilities ✅
  * Checkpoint 5 — Verification ✅
  * Banking Boundary Completion (Public Contracts & Reservation Lifecycle) ✅
* **Phase 3 — Double-Entry Financial Ledger (ADR-006)** ✅
  * Checkpoint 1 — Architecture & Contract Alignment ✅
  * Checkpoint 2 — Pure Ledger Domain & Public Contracts ✅
  * Checkpoint 3 — Flyway V4–V6 Migrations, JPA Persistence & Triggers ✅
  * Checkpoint 4/5 — `LedgerService`, Conflict-Safe Idempotency, Reversals & Modulith Verification ✅
* **Phase 4 — Payment Engine & Orchestration** ✅
  * Checkpoint 1 — Pure Payment Domain, State Machine, Fingerprinting & Public Contracts ✅ (Accepted & Pushed: `3fa69c9`)
  * Checkpoint 2 — Flyway V7–V9 Payment Persistence Schema, UTF-16 Length Alignment & PostgreSQL Integrity Constraints ✅ (Accepted & Pushed: `b8688ef`, `676d7eb`)
  * Checkpoint 3 — `PaymentService` Application Orchestration & End-to-End Integration Verification ✅ (Accepted & Pushed: `547fcaa`)
* **Settlement Module — Cross-Module Settlement Orchestration** ✅ (Accepted & Pushed: `a59e11d`)
  * `SettlementService` (`settlePayment`), `SettlePaymentCommand`, `SettlementResult`, and `SettlementException` hierarchy coordinating `BankingService.consumeReservation`, `LedgerService.postTransaction`, and `PaymentSettlementPort.recordSettlement` atomically.
* **Phase 5 — Financial Messaging (`messaging` Module)** ✅ (Accepted & Pushed: `148775b`)
  * Pure `PaymentMessage` domain (`GENERATED -> SIGNED -> SENT -> ACKNOWLEDGED | NACKNOWLEDGED | TIMEOUT`), deterministic ISO 20022-inspired `pacs.008.001.08` payload builder (`Pacs008PayloadBuilder`), SHA-256 payload integrity digest (`PayloadDigest`, `crosspaynet-message-digest-v1`), Flyway `V10`–`V11` persistence in `messaging.payment_message`, and transactional `MessagingService` orchestration with per-attempt conflict-safe idempotency, timeout-driven retry attempts, atomic NACK failure handling, and strict separation between message acknowledgment and financial settlement.

* **Phase 6 — Routing & Correspondent Banking (`routing` Module)** ✅ (Accepted & Pushed: `73eed78`, `52c70f0`)
  * `CorrespondentRoute` domain, Flyway `V12`–`V13` persistence in `routing.correspondent_route`, deterministic route selection (`ACTIVE`, matching BIC and currency, `priority ASC, routeReference ASC`), conflict-safe route registration, and `RoutingService.routePayment` orchestrating `ROUTING -> EXECUTING` or `ROUTING -> REJECTED (NO_ROUTE_AVAILABLE)` with atomic reservation release and committed-route replay.

* **Phase 7 — Distributed Systems, Kafka & Reliability (`events` Module)** ✅ (Accepted & Pushed: `373f3fd`)
  * Transactional outbox via Spring Modulith event publication registry (`system.event_publication`), deterministic partition-keyed event externalization (`crosspaynet.*.v1`), standardized `EventEnvelope`, Flyway `V14` inbox persistence (`system.event_inbox`), conflict-safe consumer deduplication (`INSERT ... ON CONFLICT DO NOTHING`), `TransactionalConsumerExecutor` with atomic effect commit, and robust Kafka consumer error handling with bounded retries and dead-letter publishing (`.DLT`) with diagnostic exception headers.

* **Phase 8 — Security & Compliance (`security` and `compliance` Modules)** ⏳ (Implemented Locally, Pending Review)
  * Role-based access control (RBAC) with method security (`CUSTOMER`, `COMPLIANCE`, `OPERATIONS`, `TELLER`, `ADMIN`), customer debit ownership validation via `SecuredPaymentPortalService`, administrative protection on `BankingService.fundAccount`, PII and account number data masking via `DataMasker`, sanctions and high-value screening via `SanctionsMatcher`, persistent review workflow via `compliance.compliance_review` (`V15`), `ComplianceService` decision lifecycle with `ROLE_COMPLIANCE` authorization, and automated payment screening on `PaymentInstructionReceived`.

## Current/Next Phase
* **Phase 8 — Security & Compliance** (Pending Acceptance Review & Publication)
* **Phase 9 — Observability & Operations** ▶ NEXT

## Upcoming Phases
* Phase 9 — Observability & Operations
* Phase 10 — AI Anomaly Detection
* Phase 11 — RAG Payment Operations Copilot
* Phase 12 — Failure Simulation & Payment Replay Lab
* Phase 13 — Testing, Performance & CI/CD

## Important Decisions
* **Phase 0.A**: The project is strictly an educational simulator, not for real transactions.
* **Phase 1.B**: CrossPayNet is built as an Evolutionary Modular Monolith using Java 25 LTS, Spring Boot 4.1.1, and PostgreSQL 18.
* **Phase 3 (ADR-006)**: Immutable double-entry financial ledger using conflict-safe `INSERT ... ON CONFLICT DO NOTHING` idempotency claims, unconstrained `NUMERIC` with `scale(trim_scale(...)) <= 4` checks, and deferred PostgreSQL commit triggers.
* **Phase 4 (CP1–CP3)**: Payment aggregate state machine (`VALIDATING`, `COMPLIANCE_HOLD`, `ROUTING`, `EXECUTING`, `PENDING_EXTERNAL`, `SETTLED`, `REJECTED`, `CANCELLED`, `FAILED`), Financial Commitment Point at `ROUTING -> EXECUTING`, database-level state/reason/UTF-16 length invariants (`V7`–`V9`), and transactional `PaymentService` orchestration with conflict-safe `(source_account_id, payment_reference)` idempotency and atomic Banking reservation release (`cancelPayment`, `rejectPayment`, `failPayment`).
* **Settlement Module**: Stateless transactional orchestrator (`SettlementService.settlePayment`) that requires an explicit `INTERNAL` `clearingAccountReference` and external `settlementReference` (never inferring the clearing account from `routeReference`), posts a balanced 2-line `STANDARD` ledger entry (`DEBIT` `BANKING_ACCOUNT` `sourceAccountId`, `CREDIT` `INTERNAL` `clearingAccountReference`) under deterministic key `PAYMENT_SETTLEMENT:{paymentId}`, and coordinates reservation consumption, ledger posting, and payment settlement recording atomically within a single ACID transaction.
* **Phase 5 (Financial Messaging)**: `com.crosspaynet.messaging` owns `PaymentMessage` (`messaging.payment_message`, migrations `V10`–`V11`), generating deterministic `pacs.008.001.08` payloads and `crosspaynet-message-digest-v1` SHA-256 integrity digests (verified on sign and database restoration). Network retries (`attemptNumber > 1`) are tracked as independent `PaymentMessage` records after a preceding `TIMEOUT` while preserving `Payment`'s original `externalDispatchReference` from attempt `1`. Positive acknowledgment (`ACKNOWLEDGED`) does not settle the payment or post ledger entries (`SettlementService.settlePayment` remains the explicit settlement boundary); negative acknowledgment (`NACKNOWLEDGED`) atomically invokes `PaymentService.failPayment` (`EXTERNAL_NETWORK_REJECTED`).

* **Phase 6 (Routing)**: `com.crosspaynet.routing` owns `CorrespondentRoute` (`routing.correspondent_route`, migrations `V12`–`V13`). Routing selects routes and makes the routing decision only; it does not move money. Committed routes are replayed by reference (including inactive routes) rather than reselected. Settlement callers obtain `clearingAccountReference` via `RoutingService.findRouteByReference`.
* **Phase 7 (Distributed Systems & Events)**: `com.crosspaynet.events` implements the transactional outbox pattern via Spring Modulith's event publication registry (`system.event_publication`), publishing domain events to Apache Kafka with deterministic aggregate partition keys (`FundsReserved` -> `accountId`, `PaymentInstructionReceived`, `PaymentSentToNetwork`, `PaymentSettledEvent` -> `paymentId`) inside standardized `EventEnvelope`s. Consumer idempotency is enforced via `system.event_inbox` (`V14`) using conflict-safe `INSERT ... ON CONFLICT DO NOTHING` in the same transaction as the consumer effect. Unretryable/poison-pill payloads route to `.DLT` with diagnostic exception headers after bounded retries.
* **Phase 8 (Security, RBAC & Compliance)**: `com.crosspaynet.security` and `com.crosspaynet.compliance` enforce application access control and regulatory simulation. `SecurityRoles` and `@EnableMethodSecurity` protect administrative operations (`BankingService.fundAccount`), enforce customer debit ownership boundaries via `SecuredPaymentPortalService`, and restrict compliance reviews to `ROLE_COMPLIANCE`. `DataMasker` sanitizes PII and account references for structured logs. `compliance.compliance_review` (`V15`) maintains immutable review history, while `SanctionsMatcher` screens for sanctions keywords and non-converting USD high-value thresholds, triggering automated evaluation via `CompliancePaymentListener` upon `PaymentInstructionReceived`.

## Verification Status
* **Test Suite Status**: 261 full-suite tests across 35 Surefire test suites passing against PostgreSQL 18 and Apache Kafka Testcontainers (`0` failures, `0` errors, `0` skipped) with Flyway migrations `V1`–`V15`.
* **Chronological Change Log**: See root [`changes.md`](../changes.md) for detailed checkpoint and corrective commit records.
