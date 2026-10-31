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
* **Operations Module (`operations`, `operations :: contracts`)**: Operational reconciliation engine evaluating cross-module consistency across Payment workflows, Banking reservations, double-entry Ledger postings, and Modulith event publications. Persistence in `operations` schema (`V16`–`V18`) with durable discrepancy tracking, historical observation recording (`V17`), abandoned-run recovery, secured REST API (`/api/v1/operations/reconciliation/**`) with `ROLE_OPERATIONS` authorization, and periodic scheduled background scanning via Spring `@Scheduled` + ShedLock distributed locking (`operations.shedlock`, `V18`) for multi-instance deployments.

## Planned Capabilities
* Real-time operational dashboards
* Failure recovery and replay
* Observability
* AI-based anomaly detection
* RAG-based Payment Operations Copilot

## Quickstart & Local Development

CrossPayNet includes a reproducible, production-practical local container environment using Docker Compose for PostgreSQL 18 and Apache Kafka 3.8.0 (KRaft mode).

### Prerequisites
* **Java**: JDK 25 LTS installed and on `PATH`
* **Docker & Docker Compose**: Docker Engine v24+ with Compose v2
* **Build Tool**: Maven 3.9+ or the included Maven Wrapper (`./mvnw` / `.\mvnw.cmd`)

### Step 1: Clone the Repository
```bash
git clone https://github.com/crosspaynet/crosspaynet.git
cd crosspaynet
```

### Step 2: Configure Environment Variables
Copy the documented template to create your local `.env` file:
```bash
cp .env.example .env
```
*(On Windows PowerShell: `Copy-Item .env.example .env`)*

Review `.env` to verify port assignments and credentials. The default configuration connects to PostgreSQL on port `5432` and Kafka on port `9092`.

### Step 3: Start Infrastructure Services
Start PostgreSQL and Kafka in the background:
```bash
docker compose up -d
```
Verify both containers report healthy status:
```bash
docker compose ps
```
Expected output:
* `crosspaynet-db` (PostgreSQL 18) — `Up (healthy)` on `0.0.0.0:5432->5432`
* `crosspaynet-kafka` (Apache Kafka 3.8.0 KRaft) — `Up (healthy)` on `0.0.0.0:9092->9092`

### Step 4: Run the Application
Launch the Spring Boot application using the `local` profile:
```bash
# Linux / macOS
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Windows PowerShell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```
On startup, Flyway automatically applies all 18 database migrations (`V1` through `V18`), and the application connects to Kafka.

### Step 5: Verify Application Health
Verify that the Spring Boot Actuator health and probe endpoints report `UP`:
```bash
# Overall Health
curl -s http://localhost:8080/actuator/health

# Readiness Probe
curl -s http://localhost:8080/actuator/health/readiness

# Liveness Probe
curl -s http://localhost:8080/actuator/health/liveness
```
Expected response:
```json
{"groups":["liveness","readiness"],"status":"UP"}
```

### Useful Operational Commands
```bash
# View live logs from infrastructure containers
docker compose logs -f

# View logs from a specific container (e.g., Kafka)
docker compose logs -f kafka

# Stop containers while preserving data volumes
docker compose down

# Stop containers and wipe database/Kafka volumes (clean reset)
docker compose down -v
```

### Local Demo Data & Frontend Integration

When started with the `local` profile (`-Dspring-boot.run.profiles=local`), the application automatically seeds demo records via `LocalDemoDataInitializer` (`crosspaynet.demo-data.enabled: true`):
* **Demo Customer User**: Username `customer` (Role: `ROLE_CUSTOMER`, password `customer` in dev in-memory auth).
* **Demo USD Account**: Active account pre-funded with `$10,000.0000 USD`.
* **Correspondent Route**: Active route for BIC `DBEUMM21XXX` in `USD`.

#### Available Portal APIs
* `GET /api/v1/portal/csrf`: Returns active CSRF token metadata and sets `XSRF-TOKEN` cookie (`HttpOnly=false`).
* `GET /api/v1/portal/accounts`: Returns account overview and balances for authenticated customer.
* `GET /api/v1/portal/payments?page=0&size=20`: Returns paginated payment history for customer accounts.
* `POST /api/v1/portal/payments`: Submits a cross-border payment instruction with CSRF protection and debit ownership validation.
* `GET /api/v1/portal/payments/{paymentId}`: Tracks payment lifecycle status (with anti-enumeration protection).

#### CORS Configuration
Frontend Single Page Applications running at `http://localhost:3000` (e.g., Next.js) are pre-configured via `crosspaynet.security.cors.allowed-origins`. Credentials (`Cookie`, `Authorization`) are permitted with standard headers (`Content-Type`, `X-XSRF-TOKEN`).

### Troubleshooting

* **Host Port Conflict on Port 5432 (PostgreSQL)**:
  If a native PostgreSQL instance is already running on port `5432`, adjust `POSTGRES_PORT` in `.env`:
  ```bash
  POSTGRES_PORT=5434
  SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/crosspaynet
  ```
  Restart containers with `docker compose up -d`.

* **Host Port Conflict on Port 9092 (Kafka)**:
  If port `9092` is already in use, update `KAFKA_PORT` in `.env`:
  ```bash
  KAFKA_PORT=9094
  SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9094
  ```
  Restart containers with `docker compose up -d`.

* **Kafka Broker Rebalancing / Consumer Assignment**:
  On initial container boot, Kafka KRaft initialization requires 5–10 seconds. The Compose healthcheck gates readiness via `kafka-broker-api-versions.sh`. If running in resource-constrained environments, allow a few seconds for partition assignment.

---

## Validation
Run the full unit, architecture, PostgreSQL 18, and Apache Kafka Testcontainers integration test suite with:
```powershell
.\mvnw.cmd clean test
```

## Development Philosophy
This project is developed incrementally in distinct phases. The repository structure is designed to support the eventual introduction of microservices, distributed messaging, and databases. All major architectural decisions are documented via Architectural Decision Records (ADRs).
