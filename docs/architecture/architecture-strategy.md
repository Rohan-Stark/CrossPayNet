# Architecture Strategy: Evaluating Approaches for CrossPayNet

## 1. Architecture Problem Statement
CrossPayNet requires an architecture that can support its domain logic (Payments, Ledger, Settlement) while balancing its primary goal as an educational simulator. The architecture must permit failure simulation, distributed systems learning, AI integration, and financial consistency, without introducing overwhelming operational complexity too early. 

## 2. Evaluation Criteria
We evaluate architectural candidates based on:
* **Domain Fit**: Does it naturally support the boundaries of Banking, Payment, and Ledger?
* **Financial Consistency**: Can it handle varying consistency requirements across different operations?
* **Complexity & Operational Overhead**: How hard is it to deploy, run locally, and debug?
* **Learning Value**: Does it effectively demonstrate distributed systems concepts (idempotency, retries, eventual consistency)?
* **Future Extensibility & AI Integration**: Can it seamlessly integrate specialized components like an Anomaly Detection model?

---

## 3. Architecture Analyses

### A. Traditional Monolith
A single, unified application where all components (Banking, Payment, Ledger, Compliance) are tightly coupled in the same codebase, running in a single process, and sharing a single database schema.

* **Module Boundaries**: Often weakly enforced, leading to a "Big Ball of Mud" where Payment logic might directly query Ledger tables.
* **Financial Transaction Handling**: Very easy. A single ACID transaction can update the payment status and ledger balance simultaneously.
* **Internal Communication**: In-memory function calls. Extremely fast, no network latency.
* **Deployment & Testing**: Simple to deploy (one artifact). End-to-end testing is straightforward.
* **Failure Handling**: Binary. If the process crashes, the entire system is down. It is difficult to simulate partial failures (e.g., Compliance is down, but Ledger is up).
* **Observability**: Simple. Stack traces and localized logs provide the full context of a request.
* **Learning Value**: Low for distributed systems. Does not teach network partitions, retries, or message delivery semantics.

### B. Modular Monolith
A single deployable application that strictly enforces internal module boundaries. The `Payment` module cannot directly query the `Ledger` database tables; it must use well-defined internal interfaces (APIs or in-memory events).

* **Module Ownership**: Clear separation of concerns while staying in one codebase.
* **Internal Interfaces**: Modules communicate via strictly defined contracts, preventing spaghetti code.
* **Financial Consistency**: Can leverage local ACID transactions for intra-module integrity, while using internal asynchronous events to practice eventual consistency between bounded contexts.
* **Testability**: Excellent. Modules can be unit-tested in isolation, and the whole system can be tested without complex orchestration.
* **Future Extraction**: If a module needs to scale independently, its strict boundaries make it trivial to extract into a microservice later.
* **Failure Simulation**: We can simulate failures in-memory by injecting fault-proxies between modules (e.g., delaying an event or throwing intentional exceptions).

### C. Microservices
Decomposing CrossPayNet into independently deployable services (e.g., `PaymentService`, `LedgerService`, `ComplianceService`), each owning its own database and communicating over the network.

* **Service Boundaries**: Hard network boundaries force decoupling.
* **Service-to-Service Communication**: Network-based (e.g., HTTP REST, gRPC, or async message brokers). Introduces latency and serialization overhead.
* **Network Failures**: Inherent to the architecture. Services must handle timeouts, retries, and unavailability gracefully.
* **Consistency**: Strong consistency across services is practically impossible without severe performance penalties (like Two-Phase Commit). Requires embracing eventual consistency and compensating transactions.
* **Observability & Debugging**: High complexity. Requires distributed tracing (e.g., OpenTelemetry) and centralized logging to understand a single payment's lifecycle across multiple services.
* **Security Boundaries**: Each service can enforce its own security policies.
* **Deployment Complexity**: Requires containers, orchestrators (Kubernetes/Docker Compose), and complex CI/CD.

---

## 4. CrossPayNet-Specific Trade-offs

### 4.1 Financial Consistency Analysis
CrossPayNet does **not** require uniform strong consistency across the entire system. We must evaluate consistency at specific boundaries:
* **Strong Consistency Required (Intra-Domain)**: Inside the `Ledger` domain. A debit and credit must absolutely balance in a single atomic operation. The Ledger aggregate cannot tolerate eventual consistency within its own boundaries. Local ACID transactions are non-negotiable here.
* **Eventual Consistency Tolerated (Inter-Domain)**: Between `Payment` and `Ledger`. When a payment is validated, it can asynchronously request the Ledger to settle. If the Ledger rejects it due to insufficient funds, the Payment state eventually transitions to `Failed`. 

### 4.2 Distributed Transaction Patterns (Candidate Approaches)
To coordinate a payment across `Compliance`, `Routing`, and `Ledger`, several patterns can be analyzed:
* **Choreography / Events**: Services broadcast domain events (e.g., `PaymentCreated`). Other services listen and react. 
  * *When it makes sense*: For decoupled actions like audit logging or notifications.
* **Orchestration / Sagas**: A central `Payment Orchestrator` manages the state machine, commanding `Ledger` to debit, and if it fails, commanding `Compliance` to rollback. 
  * *When it makes sense*: For the core cross-border payment flow, as the financial lifecycle requires strict visibility and deterministic compensation.
* **Transactional Outbox**: Used to reliably publish events when updating local state. When `PaymentService` updates a payment in its DB, it writes an event to an `Outbox` table in the same local ACID transaction. 
  * *When it makes sense*: Anytime a module must update its own database AND notify another module, preventing "dual-write" inconsistencies.

### 4.3 Failure Handling & Simulation
* A Traditional Monolith is insufficient for simulating network timeouts, duplicate messages, or delayed processing.
* Microservices naturally exhibit these failures, providing a rich environment for learning, but at a high operational cost that distracts from initial domain modeling.
* A Modular Monolith can emulate these failures via internal fault-injection (e.g., delaying an in-memory event queue or dropping messages intentionally) while keeping infrastructure simple.

### 4.4 Distributed-System Learning Value
* **Monolith**: Fails to teach retries, idempotency, or eventual consistency.
* **Microservices**: Teaches all distributed concepts but front-loads the complexity, potentially overwhelming the core goal of modeling a payment network.
* **Modular Monolith**: Allows developers to learn idempotency and eventual consistency (by enforcing them across internal module boundaries) before introducing the actual pain of network infrastructure.

### 4.5 AI Integration
* **Payment Operations Copilot**: Needs an aggregated view of system state, which is easier to query in a monolith or via a CQRS read-model in a distributed system.
* **Anomaly Detection**: AI models often require Python and specialized GPU compute. Regardless of the core architecture, this component will likely necessitate extraction into an independent service.

### 4.6 Testing
* **Monolith**: E2E testing is easy. Unit testing can be messy if boundaries are weak.
* **Modular Monolith**: Excellent unit testing. Integration testing across modules is fast and doesn't require standing up multiple Docker containers.
* **Microservices**: Requires complex contract testing to ensure services agree on API payloads, and E2E testing is notoriously flaky.

---

## 5. Evolutionary Architecture Strategy
Do we need to choose one architecture forever? No. The most successful complex systems often utilize an evolutionary architecture. Starting with Microservices often results in a "Distributed Monolith" because domain boundaries are rarely correct on day one, and refactoring across network boundaries is extremely expensive.

**Proposed Evolution for CrossPayNet:**
1. **Phase 2-6 (Modular Monolith)**: Enforce strict boundaries between `Payment`, `Ledger`, and `Compliance`. Communicate via defined interfaces or internal event buses. This keeps local development fast and refactoring cheap while domain knowledge crystallizes.
2. **Phase 7 (Distributed Systems Integration)**: Once boundaries are proven, extract specific modules (e.g., `Payment` vs `Ledger`) into independent microservices communicating over a message broker (e.g., Kafka). This introduces real network failures, fulfilling the distributed systems learning goal.
3. **Phase 10+ (Polyglot AI Services)**: Introduce AI Anomaly Detection as an independent microservice, communicating with the core system via APIs/Events.

*Candidate for early extraction:* AI components, due to varying technology stack requirements (Python vs backend language).

---

## 6. Provisional Recommendation
**Recommendation: Evolutionary Modular Monolith.**

* **Why?** It balances the need for initial simplicity with the strict discipline required for future distributed extraction. It allows us to focus on pure domain modeling (Ledger integrity, Payment state machines) in the early phases without fighting network infrastructure or complex CI/CD pipelines.
* **What we gain**: Fast local development, easy refactoring, simple deployment, and guaranteed financial consistency where needed during the initial phases.
* **What we give up (initially)**: The immediate gratification of working with message brokers and distributed tracing, which are intentionally deferred.
* **Evolution**: We will design internal modules to be "extraction-ready," avoiding shared database tables and conceptually utilizing the Transactional Outbox pattern even within the monolith.

*(This recommendation remains **provisional** and must be reviewed and explicitly approved. No final ADR is generated yet).*

---

## 7. Assumptions
* The initial focus is on correct domain logic, not handling 10,000 transactions per second.
* Developers working on this project want to learn *how* to decouple systems, making the journey from Modular Monolith to Microservices highly educational.
* Infrastructure complexity should be introduced only when the learning objectives of a specific Phase require it.

## 8. Unresolved Questions (For Phase 1.B.2)
1. What programming language and ecosystem best supports a Modular Monolith architecture for this domain?
2. Which database engine will provide the required ACID guarantees for the Ledger module?
3. How will we implement the internal event bus to practice eventual consistency before introducing a real message broker?
