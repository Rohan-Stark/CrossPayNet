# CrossPayNet Change Log

Chronological record of verified engineering checkpoints and corrective updates in the CrossPayNet Evolutionary Modular Monolith.

---

## 2026-10-31 — Checkpoint 10C: Frontend Integration Prerequisites — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Prepared the backend for Next.js frontend integration with customer account summaries, payment history, browser CSRF/CORS support, and demo data seeding:
  * **Customer Account Overview API (`GET /api/v1/portal/accounts`)**:
    * Created `CustomerAccountController` secured by `@PreAuthorize("hasRole('CUSTOMER')")`.
    * Extended `BankingService` with `findAccountsByCustomer(UUID customerId)` returning pure `CustomerAccountView` records (`accountId`, `maskedAccountNumber`, `currency`, `status`, `availableBalance`, `reservedAmount`, `totalBalance`).
    * Exposes `CustomerAccountResponse` DTO with masked account numbers (`****XXXX`) and calculated total balances. Strictly excludes JPA entities, reservation tokens, and database plumbing.
  * **Customer Payment History API (`GET /api/v1/portal/payments?page=0&size=20`)**:
    * Added paginated payment history endpoint to `PaymentPortalController` returning `PagedResponse<CustomerPaymentResponse>`.
    * Implemented database-level pagination in `PaymentRepository.findBySourceAccountIdIn(..., Pageable)` ordered by `createdAt DESC, paymentId DESC`.
    * Customer identity is resolved from authenticated principal (`Authentication.getName()`) and constrained strictly to accounts owned by that customer in `banking.account`. Returns empty page if customer owns no accounts or payments.
    * Beneficiary account numbers are consistently masked (`****6819`), and all internal tokens/ledger IDs are omitted.
  * **Browser-Compatible Authentication, CSRF & CORS**:
    * Preserved CSRF protection across all state-changing endpoints without disabling it or exempting `/api/**`.
    * Configured `CookieCsrfTokenRepository.withHttpOnlyFalse()` with `SpaCsrfTokenRequestHandler` (delegating attribute handling to `XorCsrfTokenRequestAttributeHandler` and resolving raw header tokens via `X-XSRF-TOKEN`).
    * Implemented `CsrfCookieFilter` (`OncePerRequestFilter`) ensuring deferred tokens are evaluated and committed to the `XSRF-TOKEN` cookie on downstream requests.
    * Created lightweight `CsrfController` exposing `GET /api/v1/portal/csrf` and `GET /api/v1/auth/csrf` returning `{ "token": "...", "headerName": "X-XSRF-TOKEN", "parameterName": "_csrf" }` and setting the cookie.
    * Configured Spring Security CORS filter bean with configurable allowed origins (`crosspaynet.security.cors.allowed-origins: ["http://localhost:3000"]`), allowed methods (`GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`), allowed headers (`Authorization`, `Content-Type`, `X-XSRF-TOKEN`, `X-Requested-With`), and `allowCredentials(true)`.
  * **Local Demo Data Initializer (`LocalDemoDataInitializer`)**:
    * Created idempotent seeder under `@Profile("local")` gated by `crosspaynet.demo-data.enabled: true`.
    * Pre-seeds demo customer with ID derived deterministically from username `"customer"` (`UUID.nameUUIDFromBytes("customer".getBytes(UTF_8))`).
    * Opens an active USD account with $10,000 initial balance and registers a correspondent route for `DBEUMM21XXX` / `USD`.
  * **Verification & Test Suite**:
    * Added `CustomerAccountControllerSecurityTest` (4 tests) and `CustomerAccountControllerIntegrationTest` (1 test).
    * Added `PaymentPortalControllerSecurityTest` (15 tests) and `PaymentPortalControllerIntegrationTest` (7 tests).
    * Added `BrowserSecurityIntegrationTest` (6 tests) verifying CSRF cookie generation, rejection of requests without CSRF, rejection of invalid CSRF, acceptance of valid CSRF, and CORS preflight handling.
    * Added `LocalDemoDataInitializerTest` (1 test) verifying idempotent startup seeding.
    * Full repository test suite executed: **441 tests run, 0 failures, 0 errors, 0 skipped**.

---

## 2026-10-31 — Checkpoint 10B: Docker Compose Kafka & Reproducible Local Setup — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Established a reproducible, zero-friction local development environment and runtime health observability for CrossPayNet:
  * **Multi-Container Infrastructure (`docker-compose.yml`)**:
    * **PostgreSQL 18**: Configured official `postgres:18` matching Testcontainers, using named volume `pgdata:/var/lib/postgresql`, healthcheck probe via `pg_isready -U ${POSTGRES_USER:-crosspaynet_user} -d ${POSTGRES_DB:-crosspaynet}`, and configurable host port mapping `${POSTGRES_PORT:-5432}:5432` to eliminate local port conflicts.
    * **Apache Kafka 3.8.0 (KRaft mode)**: Configured official `apache/kafka:3.8.0` in pure KRaft mode (no ZooKeeper required), aligned with Testcontainers configuration. Configured dual listeners: `PLAINTEXT://${KAFKA_ADVERTISED_HOST:-localhost}:${KAFKA_PORT:-9092}` for host processes and `INTERNAL://kafka:29092` for in-compose services. Added broker readiness healthcheck via `/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092` with `service_healthy` startup ordering.
  * **Configuration & Profiles (`application-local.yml` & `application.yml`)**:
    * Created dedicated `src/main/resources/application-local.yml` profile configuring developer-friendly simulator defaults (`show-sql: true`, `format_sql: true`, default credentials).
    * Updated base `src/main/resources/application.yml` with fallback environment property expressions (`${SPRING_DATASOURCE_URL:...}`, `${SPRING_KAFKA_BOOTSTRAP_SERVERS:...}`) and disabled verbose SQL logging for standard/test profiles.
    * Added `spring-boot-starter-flyway` dependency to `pom.xml` ensuring runtime Spring Boot automatically executes all 18 Flyway schema migrations on startup.
    * Added JVM default timezone normalization (`TimeZone.setDefault(TimeZone.getTimeZone("UTC"))`) in `CrossPayNetApplication.main(args)` preventing Windows host JVM timezone mismatch with PostgreSQL 18 container parameters.
  * **Health Probes & Actuator Integration (`spring-boot-starter-actuator`)**:
    * Added `spring-boot-starter-actuator` to `pom.xml`.
    * Configured actuator web exposure for `health` and `info` endpoints, enabling Kubernetes-compatible liveness and readiness probes (`/actuator/health/liveness`, `/actuator/health/readiness`).
    * Configured `management.health.kafka.enabled: false` to ensure transient broker blips do not trigger destructive application crash-loops while the transactional outbox safely buffers events in PostgreSQL.
    * Explicitly permitted unauthenticated access to `/actuator/health` and `/actuator/health/**` in `SecurityConfiguration.java` while preserving strict authentication on all business and operations APIs.
  * **Consumer Reliability & Modulith Coexistence**:
    * Updated `SampleReliableConsumer` to accept `ConsumerRecord<String, Object> record` directly, eliminating `MismatchedInputException` during Jackson message conversion under `spring-modulith-events-kafka`'s `ByteArrayJsonMessageConverter`.
    * Aligned `KafkaReliabilityAndConsumerIntegrationTest` with Modulith JDBC schema property (`spring.modulith.events.jdbc.schema=system`) and byte-payload serialization.
  * **Local Development Artifacts & Documentation**:
    * Created `.env.example` documenting all configuration variables with clear security warnings against committing real credentials.
    * Expanded `README.md` with complete step-by-step Quickstart guide, Docker Compose life-cycle commands, port configuration, Actuator health verification, and troubleshooting instructions.
  * **Verification**:
    * Full repository test suite executed: **422 tests run, 0 failures, 0 errors, 0 skipped** across all modules.
    * Architecture test suite executed: **30 tests run, 0 failures, 0 errors, 0 skipped** verifying all module boundaries.

---

## 2026-10-31 — Checkpoint 10A: Customer Payment REST API — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Exposed customer-facing payment initiation and query capabilities through secured REST endpoints:
  * **Target Endpoints**:
    * `POST /api/v1/portal/payments`: Initiate a cross-border payment on behalf of the authenticated customer.
    * `GET /api/v1/portal/payments/{paymentId}`: Retrieve status and masked details of an authenticated customer's payment.
  * **Security, Identity Resolution & Anti-Enumeration**:
    * Protected by `@PreAuthorize("hasRole('CUSTOMER')")` at class level on `PaymentPortalController`.
    * Unauthenticated requests are rejected with `401 Unauthorized`.
    * Non-customer roles (`ROLE_OPERATIONS`, `ROLE_ADMIN`, `ROLE_COMPLIANCE`, `ROLE_TELLER`) receive `403 Forbidden`.
    * Authenticated customer identity is resolved strictly from `Authentication.getName()` principal UUID and validated against debit account ownership in `banking.account` via `SecuredPaymentPortalService` (calling `BankingService.isAccountOwnedBy`). Source account mismatch returns `403 Forbidden`.
    * Privacy-safe anti-enumeration policy: If a customer attempts to query another customer's payment on `GET /api/v1/portal/payments/{paymentId}`, `PaymentPortalController` catches the resulting `AccessDeniedException` and raises `PaymentNotFoundException`, returning a uniform `404 Not Found` to prevent valid payment UUID discovery.
  * **Contracts, DTOs & Masking (`com.crosspaynet.security.portal.web`)**:
    * `InitiatePaymentRequest`: Strongly validated input DTO for payment instructions (`sourceAccountId`, `paymentReference`, `amount`, `currency`, `beneficiaryName`, `beneficiaryAccountNumber`, `beneficiaryBankBic`) with strict positive amount and supported currency enforcement.
    * `CustomerPaymentResponse`: Customer-facing representation exposing payment lifecycle status, reason codes, timestamps, and payment reference. Automatically masks `beneficiaryAccountNumber` using `DataMasker.maskAccountNumber(...)` (e.g. `****6819`), and completely excludes internal technical plumbing (`reservationToken`, `ledgerTransactionId`, `routeReference`, `externalDispatchReference`).
    * `ApiErrorResponse`: Standardized JSON error DTO (`timestamp`, `status`, `error`, `message`, `path`).
    * `PaymentPortalExceptionHandler`: `@RestControllerAdvice` mapping domain and security exceptions to RFC-standard HTTP statuses (`409 Conflict` for reference conflicts, `404 Not Found` for missing/inaccessible payments, `400 Bad Request` for validation failures and type mismatches, `403 Forbidden` for role/ownership violations, and sanitized `500 Internal Server Error`).
  * **HTTP Semantics & Idempotency**:
    * `POST /api/v1/portal/payments` returns `201 Created` with `Location: /api/v1/portal/payments/{paymentId}` header.
    * Exact identical retries return `201 Created` with the existing payment representation without duplicate fund reservations.
    * Reused payment references with conflicting parameters return `409 Conflict`.
  * **Architecture & Modulith Boundary Verification**:
    * `SecurityModulithArchitectureTest`: Verified Spring Modulith boundaries and enforced ArchUnit rule that `com.crosspaynet.security.portal.web` controllers must not directly access persistence layers or JPA repositories, strictly delegating through `SecuredPaymentPortalService`.
  * **Tests & Verification**:
    * `PaymentPortalControllerSecurityTest`: 9 MockMvc security tests verifying 401 unauthenticated, 403 non-customer roles, 201 Created with Location header and masked representation, 409 Conflict on reference collisions, 400 Bad Request on invalid payloads, 200 OK on GET, 404 Not Found on foreign payments, 404 Not Found on unknown payments, and 400 Bad Request on malformed UUIDs.
    * `PaymentPortalControllerIntegrationTest`: 6 end-to-end integration tests with PostgreSQL 18 and Apache Kafka Testcontainers verifying full initiation-to-query lifecycle, Location header navigation, database balance reservation integrity, conflict-safe idempotent replays, 409 conflict rejection on reused references, 403 prevention of cross-account initiation, 404 privacy enforcement on foreign payment queries, and 201 Created with `REJECTED (INSUFFICIENT_FUNDS)` on overdrawn initiation.
    * Total 17 Checkpoint 10A tests and full 12-test security regression suite passing with 0 failures, 0 errors.

---

## 2026-10-28 — Phase 9B Checkpoint 3: Discrepancy Observation History REST API — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Exposed historical discrepancy observations through a secured, read-only REST endpoint:
  * **Target Endpoint**: `GET /api/v1/operations/reconciliation/discrepancies/{discrepancyId}/observations`
  * **Security & Authorization**:
    * Protected by `@PreAuthorize("hasAnyRole('OPERATIONS', 'ADMIN')")`.
    * Unauthenticated requests are rejected with `401 Unauthorized`.
    * Authenticated callers lacking `ROLE_OPERATIONS` or `ROLE_ADMIN` receive `403 Forbidden`.
  * **Contracts & DTOs**:
    * Created `ReconciliationDiscrepancyObservationView`: Immutable record in `com.crosspaynet.operations.contracts` capturing observation metadata, status, summary, details, timestamps, and provenance (`LIVE`, `LEGACY_RECONSTRUCTED`).
    * Added `findObservations(DiscrepancyId discrepancyId, int page, int size)` to `ReconciliationService` contract returning `PagedResult<ReconciliationDiscrepancyObservationView>`.
    * Created `ReconciliationDiscrepancyObservationResponse` in `com.crosspaynet.operations.infrastructure.web` with static factory `from(ReconciliationDiscrepancyObservationView)`. Preserves `null` details when absent and keeps provenance transparent without exposing JPA entities.
  * **Persistence & Mapping (`operations`)**:
    * Extended `ReconciliationDiscrepancyObservationRepository` with `Page<ReconciliationDiscrepancyObservationEntity> findByDiscrepancyId(UUID discrepancyId, Pageable pageable)` delegating pagination and sorting directly to PostgreSQL (`LIMIT` / `OFFSET` / `ORDER BY`).
    * Added mapping methods `toView(ReconciliationDiscrepancyObservation)` and `toView(ReconciliationDiscrepancyObservationEntity)` in `OperationsMapper`.
  * **Application Service (`ReconciliationServiceImpl`)**:
    * Implemented `findObservations(DiscrepancyId discrepancyId, int page, int size)`:
      * Validates pagination parameters: `page >= 0`, `size >= 1 && size <= 100`.
      * Validates discrepancy existence via `discrepancyRepository.existsById(discrepancyId.getValue())`. Throws `OperationsNotFoundException` (`404 Not Found`) if the discrepancy does not exist.
      * If discrepancy exists, queries observations with deterministic sort `Sort.by(Sort.Direction.ASC, "observedAt", "observationId")`.
      * Returns empty `PagedResult` (`totalElements: 0`, `totalPages: 0`, `content: []`) when the discrepancy exists but has zero observations.
  * **Web Controller (`ReconciliationController`)**:
    * Exposed `@GetMapping("/discrepancies/{discrepancyId}/observations")`.
    * Validates UUID string and maps bad parameter exceptions to `400 Bad Request` via existing `GlobalExceptionHandler`.
    * Enforces standard pagination default (`page = 0, size = 20`).
  * **Tests & Verification**:
    * `ReconciliationServiceTest`: 3 unit tests verifying successful retrieval and mapping, `OperationsNotFoundException` when discrepancy does not exist, and argument validation on negative page or out-of-bounds size.
    * `ReconciliationControllerSecurityTest`: 6 tests verifying 401 for unauthenticated calls, 403 for non-operations roles (`ROLE_CUSTOMER`), 200 for `ROLE_OPERATIONS`, 200 for `ROLE_ADMIN`, 400 for malformed UUID, and 404 for non-existent discrepancy.
    * `ReconciliationControllerIntegrationTest`: 3 Spring Web MVC + PostgreSQL Testcontainers tests verifying full pagination across multiple pages with deterministic chronological ordering, preservation of null details, preservation of `LEGACY_RECONSTRUCTED` provenance, empty paginated response when discrepancy has no observations, and 404 for unknown discrepancy.
    * `OperationsPersistenceIntegrationTest`: Verified Spring Data JPA repository query execution, total count calculation, and multi-page pagination with sorting against real PostgreSQL 18 container.
    * Ran complete Operations test suite (108 tests passing, 0 failures, 0 errors, 0 skipped) and `OperationsModulithArchitectureTest`.

---

## 2026-10-28 — Phase 9B Checkpoint 2: Production-Practical Scheduled Reconciliation with ShedLock — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Implemented periodic background operational reconciliation with multi-instance concurrency protection:
  * **Dependencies**: Added `shedlock-spring` (6.3.0) and `shedlock-provider-jdbc-template` (6.3.0) to `pom.xml` with managed property `<shedlock.version>6.3.0</shedlock.version>`.
  * **Flyway Migration `V18__operations_shedlock.sql`**:
    * Created `operations.shedlock` table owned by the Operations module:
      * `name VARCHAR(64) PRIMARY KEY`, `lock_until TIMESTAMPTZ NOT NULL`, `locked_at TIMESTAMPTZ NOT NULL`, `locked_by VARCHAR(255) NOT NULL`.
  * **Configuration & Scheduling Infrastructure (`com.crosspaynet.operations.infrastructure.scheduling`)**:
    * Created `OperationsSchedulingProperties`:
      * Bound to prefix `crosspaynet.operations.reconciliation.scheduling`.
      * Defaults: `enabled = false` (strictly opt-in), `cron = "0 */15 * * * *"`, `lockAtMostFor = Duration.ofMinutes(30)`, `lockAtLeastFor = Duration.ofMinutes(5)`.
    * Created `OperationsSchedulingConfiguration`:
      * Conditionally activated on `crosspaynet.operations.reconciliation.scheduling.enabled=true`.
      * `@EnableScheduling`, `@EnableSchedulerLock(defaultLockAtMostFor = "30m")`.
      * Configured `JdbcTemplateLockProvider` using DB time (`.usingDbTime()`) and explicit schema-qualified table `"operations.shedlock"`.
    * Created `ReconciliationScheduler`:
      * Conditionally activated on `crosspaynet.operations.reconciliation.scheduling.enabled=true`.
      * Scheduled method `scheduledReconciliationRun()` annotated with `@Scheduled(cron = "${crosspaynet.operations.reconciliation.scheduling.cron:0 */15 * * * *}")` and `@SchedulerLock(name = "operations_reconciliation_scheduler", lockAtMostFor = "${crosspaynet.operations.reconciliation.scheduling.lock-at-most-for:30m}", lockAtLeastFor = "${crosspaynet.operations.reconciliation.scheduling.lock-at-least-for:5m}")`.
      * Invokes existing application service: `reconciliationService.triggerRun(new TriggerReconciliationRunCommand("SYSTEM_SCHEDULER"))`.
      * Conflict & error handling: catches `OperationsConflictException` (e.g. concurrent manual or background run already in progress) and logs at `INFO` level as an expected skipped attempt; catches unexpected `Exception` and logs at `ERROR` level with stack trace without propagating, preventing schedule cancellation.
  * **Application Configuration & Environment Placeholders**:
    * Updated `application.yml` and `.env.example` with documented scheduling properties and environment variable bindings (`CROSSPAYNET_OPERATIONS_RECONCILIATION_SCHEDULING_*`).
  * **Tests & Verification**:
    * Added `ReconciliationSchedulerTest` (4 unit tests): verified invocation with `SYSTEM_SCHEDULER`, graceful conflict handling on `OperationsConflictException`, graceful handling of unexpected exceptions, and constructor validation.
    * Added `ReconciliationSchedulingConditionalTest` (3 context tests): verified scheduling configuration, scheduler, and `LockProvider` beans are absent by default or when `enabled=false`, and present when `enabled=true`, along with property binding.
    * Added `OperationsShedLockIntegrationTest` (4 PostgreSQL Testcontainers tests): verified Flyway V18 schema and primary key creation, verified two independent lock providers sharing the database prevent concurrent lock acquisition, verified lock release upon unlock with zero `lockAtLeastFor`, and verified enforcement of `lockAtLeastFor` retention window against PostgreSQL.
    * Executed full Operations module suite (97 tests, 0 failures, 0 errors, 0 skipped) and `OperationsModulithArchitectureTest` (7 tests, 0 failures).

---

## 2026-10-28 — Phase 9B Checkpoint 1 Corrective Tests: Real V16-to-V17 Flyway Migration & Atomic Rollback Verification — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Addressed acceptance review test coverage gaps for Phase 9B Checkpoint 1 without modifying production code:
  * **Genuine V16-to-V17 Flyway Migration Integration Test (`OperationsFlywayV17MigrationIntegrationTest`)**:
    * Created dedicated test with clean PostgreSQL Testcontainer instance migrating initially up to target version `16` (`flyway.target("16")`).
    * Inserted valid legacy V16 fixtures: single-run discrepancy (`first_run_id == latest_run_id`) and multi-run discrepancy (`first_run_id != latest_run_id`).
    * Captured full pre-migration state of `operations.reconciliation_discrepancy` rows.
    * Executed actual Flyway migration to version `17` (`flyway.target("17").migrate()`).
    * Verified exactly 1 observation created for single-run discrepancy with preserved details and status; verified exactly 2 observations created for multi-run discrepancy (first run honestly sets `details = NULL` and `status = OPEN`; latest run uses latest snapshot; 0 intermediate runs fabricated).
    * Verified all reconstructed rows use `LEGACY_RECONSTRUCTED` provenance.
    * Verified pre-existing `reconciliation_discrepancy` rows and all field values remained 100% unchanged.
    * Verified foreign keys, unique constraint `uk_discrepancy_run_observation`, and check constraints post-migration.
  * **Atomic Transaction Rollback Integration Tests (`DiscrepancyRecorderIntegrationTest`)**:
    * Utilized Spring Boot `@MockitoSpyBean` on `ReconciliationDiscrepancyObservationRepository` to inject deterministic runtime failures on observation persistence.
    * Added `newDiscrepancyRollsBackAggregateWhenObservationSaveFails()`: verified that when observation save throws a runtime exception, the new discrepancy aggregate insert rolls back atomically (neither record exists in DB).
    * Added `repeatDetectionRollsBackAggregateUpdateWhenObservationSaveFails()`: verified that when observation save throws on a repeat run detection, the discrepancy aggregate update rolls back completely (consecutive detections, latest run ID, last observed timestamp, and summary remain unchanged in DB; no observation row exists for the failed run).
  * **Verification**:
    * Executed targeted tests (`OperationsFlywayV17MigrationIntegrationTest`, `DiscrepancyRecorderIntegrationTest`) passing 7/7 tests with zero failures.
    * Verified `OperationsModulithArchitectureTest` passing 7/7 tests.

---

## 2026-10-28 — Phase 9B Checkpoint 1: Observation History & Persistence — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Implemented durable observation history and persistence for operational reconciliation discrepancies in the `operations` module:
  * **Flyway Migration `V17__operations_discrepancy_observations.sql`**:
    * Created table `operations.reconciliation_discrepancy_observation` with:
      * `observation_id UUID PRIMARY KEY`, `discrepancy_id UUID NOT NULL REFERENCES operations.reconciliation_discrepancy(discrepancy_id) ON DELETE RESTRICT`, `run_id UUID NOT NULL REFERENCES operations.reconciliation_run(run_id) ON DELETE RESTRICT`.
      * `discrepancy_type VARCHAR(64) NOT NULL` with CHECK constraint (`STALE_IN_FLIGHT_WORKFLOW`, `RESERVATION_PAYMENT_MISMATCH`, `PAYMENT_LEDGER_BREAK`, `INCOMPLETE_EVENT_PUBLICATION`).
      * `status_at_observation VARCHAR(32) NOT NULL` with CHECK constraint (`OPEN`, `RESOLVED_EXTERNALLY`, `ACKNOWLEDGED`).
      * `summary VARCHAR(500) NOT NULL`, `details JSONB NULL`, `observed_at TIMESTAMPTZ NOT NULL`, `provenance VARCHAR(32) NOT NULL` with CHECK constraint (`LIVE`, `LEGACY_RECONSTRUCTED`), `created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()`.
      * Unique constraint `uk_discrepancy_run_observation (discrepancy_id, run_id)` guaranteeing at most one observation per discrepancy per run.
      * Indexes: `idx_reconciliation_observation_run (run_id)`, `idx_reconciliation_observation_discrepancy (discrepancy_id)`, `idx_reconciliation_observation_observed (observed_at)`.
    * Implemented honest legacy backfill for existing discrepancies:
      * When `first_run_id == latest_run_id`: backfilled 1 observation row per discrepancy marked `LEGACY_RECONSTRUCTED` with current status, summary, and details.
      * When `first_run_id <> latest_run_id`: backfilled exactly 2 observation rows per discrepancy marked `LEGACY_RECONSTRUCTED` (the first run with `observed_at = first_detected_at`, `status_at_observation = 'OPEN'`, `details = NULL`; the latest run with `observed_at = last_observed_at`, current status, and current details). Unrecorded intermediate runs are not fabricated.
  * **Domain Model (`com.crosspaynet.operations.domain`)**:
    * Created `DiscrepancyObservationId` UUID value object with strict parsing/generation.
    * Created `ObservationProvenance` enum (`LIVE`, `LEGACY_RECONSTRUCTED`).
    * Created immutable `ReconciliationDiscrepancyObservation` domain record with comprehensive validation, invariant checks, and factory method `createLive(...)`.
  * **Persistence Layer (`com.crosspaynet.operations.infrastructure.persistence`)**:
    * Created `ReconciliationDiscrepancyObservationEntity` JPA entity with JSONB details mapping and audit timestamps.
    * Created `ReconciliationDiscrepancyObservationRepository` Spring Data JPA repository with lookup methods by discrepancy ID and existence check by `(discrepancyId, runId)`.
    * Updated `OperationsMapper` with entity-to-domain and domain-to-entity mapping for discrepancy observations.
  * **Discrepancy Recording & Per-Run Idempotency (`DiscrepancyRecorder`)**:
    * Extended `DiscrepancyRecorder.recordDiscrepancy` to persist a `LIVE` observation record alongside new discrepancies and repeat detections.
    * Implemented same-run idempotency: if a discrepancy is recorded multiple times within the same reconciliation run, observation recording and `consecutiveDetections` incrementing are skipped idempotently, preventing duplicate observations.
    * Enforced database unique constraint `uk_discrepancy_run_observation` as durable backstop.
  * **Evolved Historical Run Filter (`ReconciliationServiceImpl`)**:
    * Updated `findDiscrepancies(DiscrepancyQueryFilter)` criteria query so filtering by `runId` subqueries `operations.reconciliation_discrepancy_observation`.
    * Discrepancies observed during any run (first, intermediate, or latest) are now truthfully retrieved when filtering by `runId`.
  * **Tests & Verification**:
    * Added `DiscrepancyObservationTest` (6 unit tests) verifying value objects, domain invariants, and factories.
    * Added `DiscrepancyRecorderIntegrationTest` (4 integration tests) testing initial live observation, multi-run repeat detection, same-run idempotency, and database unique constraint enforcement.
    * Extended `OperationsPersistenceIntegrationTest` verifying observation entity persistence, foreign key restrictions, check constraints, and legacy backfill honesty.
    * Extended `ReconciliationControllerIntegrationTest` verifying intermediate-run discrepancy queries via REST API.
    * Full suite verified: 83 Operations tests passed (unit, architecture, security, persistence, and REST integration) with zero failures.

---

## 2026-10-28 — Phase 9A Checkpoint 4: Operations Reconciliation REST API & RBAC Security — *Implemented Locally (Pending Acceptance Review)*

* **Scope**: Implemented secured REST API endpoints, request/response DTOs, exception handling, and security integration tests for operational reconciliation in the `operations` module:
  * **Secured REST API Controller (`com.crosspaynet.operations.infrastructure.web.ReconciliationController`)**:
    * Mounted endpoints under `/api/v1/operations/reconciliation/**`.
    * Enforced `@PreAuthorize("hasAnyRole('OPERATIONS', 'ADMIN')")` at the class level.
    * Implemented five secured REST endpoints:
      1. `POST /runs`: Synchronously triggers a new reconciliation run across all four check executors. Derives operator identity strictly from authenticated Spring Security principal (`authentication.getName()`). Returns `201 Created` with `Location: /api/v1/operations/reconciliation/runs/{runId}`.
      2. `GET /runs`: Lists historical reconciliation runs with deterministic ordering (`startedAt DESC, runId DESC`) and bounded pagination (`page >= 0`, `1 <= size <= 100`).
      3. `GET /runs/{runId}`: Retrieves detailed outcomes for a single reconciliation run, including its individual check executions. Returns `404 Not Found` if missing.
      4. `GET /discrepancies`: Lists operational discrepancies with deterministic ordering (`lastObservedAt DESC, discrepancyId DESC`), bounded pagination, and optional filters (`status`, `type`, `runId`).
      5. `GET /discrepancies/{discrepancyId}`: Retrieves details of an individual discrepancy. Returns `404 Not Found` if missing.
  * **Public Contracts & Domain Query Extensions (`com.crosspaynet.operations.contracts`)**:
    * Added `PagedResult<T>` generic pagination container.
    * Added `DiscrepancyQueryFilter` parameter object with strict validation.
    * Extended `ReconciliationService` and `ReconciliationServiceImpl` with `findRuns(int page, int size)`, `findDiscrepancy(DiscrepancyId)`, and `findDiscrepancies(DiscrepancyQueryFilter)` (using Spring Data `JpaSpecificationExecutor` and dynamic predicates).
  * **Web DTOs & Error Sanitization (`com.crosspaynet.operations.infrastructure.web`)**:
    * Introduced public DTOs: `ReconciliationRunResponse`, `ReconciliationCheckExecutionResponse`, `ReconciliationRunDetailResponse`, `ReconciliationDiscrepancyResponse`, and `PagedResponse<T>`.
    * Implemented `OperationsExceptionHandler` (`@RestControllerAdvice`) mapping `OperationsConflictException` -> `409 Conflict`, `OperationsNotFoundException` -> `404 Not Found`, `IllegalArgumentException` / `MethodArgumentTypeMismatchException` -> `400 Bad Request`, `AccessDeniedException` -> `403 Forbidden`, and unexpected exceptions -> sanitized `500 Internal Server Error` with `ApiErrorResponse`.
  * **Module Boundary & Architectural Enforcement (`OperationsModulithArchitectureTest`)**:
    * Added ArchUnit rule `operationsControllersMustNotDependOnPersistenceLayerDirectly()` ensuring controllers never access JPA repositories or persistence entities directly, strictly delegating through `ReconciliationService`.
  * **Tests & Verification**:
    * Added `ReconciliationControllerSecurityTest` (13 tests) verifying RBAC access control (401 unauthenticated, 403 non-operations, 201/200 for OPERATIONS/ADMIN), principal derivation, 400 validation, 404 missing resources, and 409 active run conflict.
    * Added `ReconciliationControllerIntegrationTest` (5 tests) verifying end-to-end HTTP execution and PostgreSQL Testcontainers persistence, deterministic ordering, active run contention locking, and multi-parameter discrepancy filtering.
    * Full suite verified: 71 Operations tests passed (unit, architecture, security, persistence, and REST integration) and 210 full-project tests passed with zero failures.

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
