# CrossPayNet

## Description
A distributed cross-border payment messaging and settlement simulator for learning financial software architecture, distributed systems, security, observability and AI engineering.

## Important Disclaimer
**CrossPayNet is an educational simulator.** It does not process real money, nor does it connect to any real financial institutions.

## Project Goals
The project is intended to help understand:
* Payment systems
* Financial messaging
* Distributed systems
* Database transactions
* Financial ledgers
* Security
* Reliability
* Observability
* Modern AI engineering

## Architecture & Current Capabilities
CrossPayNet is implemented as an **Evolutionary Modular Monolith** (Java 25 LTS, Spring Boot 4.1.1, Spring Modulith, Flyway, and PostgreSQL 18):
* **Shared Kernel (`common.money`)**: Exact-precision `Money` and ISO 4217 `Currency` value objects.
* **Banking Module (`banking`, `banking :: contracts`)**: Customer accounts, operational balances (`available_balance`, `reserved_amount`), and idempotent fund reservation lifecycle (`ACTIVE -> RELEASED | CONSUMED`) in the `banking` schema (`V2`–`V3`).
* **Ledger Module (`ledger`)**: Immutable double-entry financial ledger (`STANDARD` and `REVERSAL` transactions, `2..100` balanced lines, conflict-safe `INSERT ... ON CONFLICT DO NOTHING` idempotency, append-only and deferred commit integrity triggers) in the `ledger` schema (`V4`–`V6`, ADR-006).
* **Payment Module (`payment`, `payment :: contracts`, `payment :: settlement`)**: Pure payment domain aggregate, nine-state workflow machine, Financial Commitment Point (`ROUTING -> EXECUTING`), SHA-256 instruction fingerprinting (`CP1`), PostgreSQL persistence with state, reason-code, and UTF-16 Unicode length constraints in the `payment` schema (`V7`–`V9`, `CP2`), and transactional `PaymentService` orchestration with conflict-safe idempotency, synchronous fund reservation, atomic reservation release, and `PaymentSettlementPort` (`CP3`).
* **Settlement Module (`settlement`, `settlement :: contracts`)**: Transactional `SettlementService` orchestrating atomic operational fund reservation consumption (`BankingService.consumeReservation`), conflict-safe double-entry ledger posting (`LedgerService.postTransaction` with deterministic key `PAYMENT_SETTLEMENT:{paymentId}`), and final payment settlement recording (`PaymentSettlementPort.recordSettlement`).
* **Messaging Module (`messaging`, `messaging :: contracts`)**: ISO 20022-inspired (`pacs.008.001.08`) wire message generation (`Pacs008PayloadBuilder`), SHA-256 payload integrity digest (`PayloadDigest`, `crosspaynet-message-digest-v1`), PostgreSQL persistence (`messaging.payment_message`, `V10`–`V11`), and transactional `MessagingService` orchestration with per-attempt conflict-safe dispatch, timeout-gated retries, atomic NACK failure handling, and ACK/settlement boundary separation.
* **Routing Module (`routing`, `routing :: contracts`)**: Correspondent route registry in the `routing` schema (`V12`–`V13`), deterministic route selection by destination BIC, currency, and priority, and `RoutingService.routePayment` committing payments to execution or rejecting them with `NO_ROUTE_AVAILABLE` and atomic reservation release.
* **Distributed Systems & Events Module (`events`, `events :: contracts`)**: Transactional outbox via Spring Modulith event publication registry (`system.event_publication`), deterministic partition-keyed externalization to Apache Kafka (`crosspaynet.*.v1`), standardized `EventEnvelope`, PostgreSQL inbox (`system.event_inbox`, `V14`), conflict-safe consumer deduplication, and consumer reliability with bounded retries and dead-letter publishing (`.DLT`).
* **Security Module (`security`, `security :: contracts`)**: Role-based access control (`ROLE_CUSTOMER`, `ROLE_COMPLIANCE`, `ROLE_OPERATIONS`, `ROLE_TELLER`, `ROLE_ADMIN`), secured application entrypoint (`SecuredPaymentPortalService`) enforcing customer account ownership, administrative protection on account funding (`BankingService.fundAccount`), and data sanitization (`DataMasker`) for PII in logs.
* **Compliance Module (`compliance`, `compliance :: contracts`)**: Pure domain sanctions keyword matching and USD high-value threshold screening (`SanctionsMatcher`), persistent review aggregate (`compliance.compliance_review`, `V15`), transactional `ComplianceService` decision lifecycle (`RELEASE` / `REJECT`), and automated event-driven screening on `PaymentInstructionReceived`.

## Planned Capabilities
* Real-time operational dashboards
* Failure recovery and replay
* Observability
* AI-based anomaly detection
* RAG-based Payment Operations Copilot

## Validation
Run the full unit, architecture, PostgreSQL 18, and Apache Kafka Testcontainers integration test suite with:
```powershell
.\mvnw.cmd clean test
```

## Development Philosophy
This project is developed incrementally in distinct phases. The repository structure is designed to support the eventual introduction of microservices, distributed messaging, and databases. All major architectural decisions are documented via Architectural Decision Records (ADRs).
