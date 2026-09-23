# Backend Language & Framework Evaluation

## 1. Objective
To evaluate candidate backend ecosystems for CrossPayNet to determine their fitness for an evolutionary modular monolith, strict financial domain modeling, transaction handling, and future distributed systems learning.

## 2. Candidates Evaluated
*(Note: Versions reflect current stable ecosystems as of late 2026)*
1. **Java (21+) + Spring Boot (3.x)**
2. **Python (3.12+) + FastAPI**
3. **TypeScript + Node.js (22+) + NestJS**
4. **Go (1.22+) (Standard Library + Minimal Routing)**
5. **C# (.NET 8/9+) + ASP.NET Core**

## 3. Evaluation Criteria
We distinguish between the **Language** (syntax, typing), the **Runtime** (memory management, concurrency engine), the **Framework** (opinionated structure, dependency injection), and **Tooling/Libraries** (ORMs, testing frameworks). 
Evaluations focus on: modular monolith fit, transactional support (ACID boundaries), observability, and AI integration.

---

## 4. Individual Ecosystem Analyses

### 4.1 Java + Spring Boot
* **Language/Runtime**: Strongly statically typed, JVM provides exceptional garbage collection, JIT compilation, and highly mature multi-threading (including Virtual Threads in Java 21+ for high-throughput I/O).
* **Framework (Spring Boot)**: Highly opinionated. Built-in Dependency Injection (DI). Spring Modulith directly enforces module boundaries within a single codebase.
* **Transaction Handling**: `@Transactional` is the industry standard for declarative database transaction boundaries, seamlessly propagating transactions across service methods.
* **Modular Monolith Fit**: Excellent. Spring Modulith exists specifically to prevent modular monoliths from degrading into Big Balls of Mud.
* **Distributed Evolution**: Spring Cloud provides a massive ecosystem for eventually moving to microservices (Kafka integration, Service Discovery).
* **Testing & Observability**: JUnit, Mockito, and Testcontainers are top-tier. Micrometer provides seamless distributed tracing (OpenTelemetry).

### 4.2 Python + FastAPI
* **Language/Runtime**: Dynamically typed (with type hints), interpreted. The Global Interpreter Lock (GIL) traditionally limits CPU concurrency, though async/await handles I/O well.
* **Framework (FastAPI)**: Minimalist, unopinionated routing framework. Relies heavily on Pydantic for validation. No built-in modular architecture enforcement.
* **Transaction Handling**: Managed manually via ORMs like SQLAlchemy. Lacks the seamless declarative transaction boundaries (`@Transactional`) found in enterprise frameworks.
* **Modular Monolith Fit**: Poor to Fair. Python's dynamic nature and lack of strict framework opinions make enforcing strict internal module isolation highly dependent on developer discipline rather than compiler checks.
* **AI Integration**: Excellent. The undisputed king of AI/ML ecosystems.
* **Testing & Observability**: Pytest is powerful. Observability libraries exist but are often stitched together manually.

### 4.3 TypeScript + NestJS
* **Language/Runtime**: Structurally typed superset of JavaScript running on V8 (Node.js). Single-threaded event loop handles I/O exceptionally well but struggles with heavy CPU-bound tasks.
* **Framework (NestJS)**: Highly opinionated, heavily inspired by Angular/Spring. Uses DI and decorators.
* **Transaction Handling**: Relies on ORMs like TypeORM or Prisma. Prisma handles transactions well but lacks the automatic propagation magic of Spring/ASP.NET.
* **Modular Monolith Fit**: Good. NestJS revolves around `@Module` decorators, encouraging modular design, though runtime isolation isn't enforced strictly.
* **Testing & Observability**: Jest provides great testing. Observability is good but the single-threaded nature requires careful profiling.

### 4.4 Go
* **Language/Runtime**: Statically typed, compiled to native binaries. Explicit error handling (no exceptions). Goroutines provide incredibly lightweight and powerful concurrency.
* **Framework (Standard Library)**: Go is anti-framework. It relies on its robust standard library (e.g., `net/http`) and minimal routers. This "unopinionated" approach is a trade-off: high flexibility but requires the team to build their own architectural scaffolding.
* **Transaction Handling**: Highly explicit. The `sql.Tx` object must be manually passed down the call stack to ensure multiple queries execute within the same transaction boundary. 
* **Modular Monolith Fit**: Fair. Go packages provide boundaries, but avoiding cyclical dependencies requires careful architectural planning.
* **Distributed Evolution**: Excellent. Go is the language of cloud-native infrastructure (Kubernetes, Docker).

### 4.5 C# + ASP.NET Core
* **Language/Runtime**: Strongly statically typed, running on the modern cross-platform .NET Core runtime. Excellent performance and asynchronous I/O (`async/await`).
* **Framework (ASP.NET Core)**: Opinionated but highly performant. Built-in DI, configuration, and logging.
* **Transaction Handling**: Entity Framework (EF) Core provides a robust Unit of Work pattern and explicit transaction scopes.
* **Modular Monolith Fit**: Excellent. .NET Projects/Assemblies within a Solution provide hard compiler-enforced boundaries between modules.
* **Distributed Evolution**: MassTransit provides exceptional support for outbox patterns, message brokers, and sagas.

---

## 5. CrossPayNet Scenario Analysis

1. **Customer creates a payment (Validation)**: 
   * *NestJS* and *Spring Boot* shine with declarative DTO validation. *Go* requires manual validation logic.
2. **Concurrent account updates**: 
   * All ecosystems handle HTTP concurrency, but *Java* and *C#* ORMs provide mature Optimistic/Pessimistic locking mechanisms for database consistency.
3. **Payment processing interacting with ledger**: 
   * Passing a transaction boundary from the `Payment` module to the `Ledger` module is seamless in *Spring* (`@Transactional`). In *Go*, the `Tx` object must be explicitly injected, coupling the interface to the DB technology.
4. **Asynchronous event processing**: 
   * *Spring Boot* (`@Async`, `ApplicationEventPublisher`) and *.NET* (MediatR, MassTransit) provide robust in-memory event buses ideal for a modular monolith.
5. **Module extraction to a service**: 
   * Strongly typed, interface-driven ecosystems (*Java, C#, Go*) make refactoring easier. *Python* refactoring across network boundaries is riskier due to dynamic typing.
6. **AI anomaly detection integration**: 
   * *Python* is native here. All other languages would need to make network calls to a Python service.
7. **Payment failure debugging**: 
   * *Java/Spring* and *C#/.NET* have the most mature distributed tracing instrumentation (OpenTelemetry) out of the box.

---

## 6. Single-Language vs. Polyglot Architecture

Given the AI requirements (Anomaly Detection, LLM Copilot), we must evaluate polyglot architectures.

* **Option A: Single Language (Python)**
  * *Pros*: One codebase, ML models live alongside business logic.
  * *Cons*: Python lacks the enterprise tooling, strict compiler boundaries, and declarative transaction management that make building complex, financially consistent modular monoliths safe and maintainable.
* **Option B: Polyglot (Core in Enterprise Language, AI in Python)**
  * *Pros*: We use the right tool for the job. Java/C# ensure strict financial domain integrity and module boundaries. Python acts as a separate microservice handling ML inference.
  * *Cons*: Requires managing two ecosystems, increasing deployment complexity.
* **Trade-off Decision**: A core payment ledger requires strict types, safe refactoring, and rigorous transaction boundaries. Forcing it into Python merely to accommodate a future AI feature is a poor architectural trade-off. We should use a robust typed language for the core, and a polyglot approach (extracting AI to Python) in Phase 10+.

---

## 7. Provisional Recommendation

**Recommendation: Java + Spring Boot (or C# + ASP.NET Core)**

* **Why?** Both ecosystems excel at building Modular Monoliths. They offer strong static typing, exceptional DI, declarative transaction management, and mature testing ecosystems. Specifically, **Spring Modulith** directly aligns with our Phase 1.B.1 architecture strategy of enforcing strict in-memory boundaries before extracting to microservices.
* **What we sacrifice**: The simplicity of Go, the unified AI ecosystem of Python, and the frontend code-sharing of TypeScript.
* **Evolution**: Spring Boot supports an internal Application Event bus to simulate asynchronous decoupling (Outbox pattern). In Phase 7, this translates easily to Kafka using Spring Cloud Stream.
* **AI Note**: The core will be built in Java/C#. The Anomaly Detection (Phase 10) will be built as a separate Python microservice, validating our distributed systems learning goals.

*(This recommendation remains **provisional** and must be reviewed and explicitly approved. No final ADR is generated yet).*

---

## 8. Assumptions & Unresolved Questions

### Assumptions
* The educational value of learning enterprise design patterns (DI, modularity, transaction boundaries) outweighs the desire for "minimalist" code.
* The team is willing to manage a polyglot deployment when AI is introduced.

### Unresolved Questions
1. **Java vs C#**: Both are exceptional choices. Which ecosystem does the developer team prefer to learn and operate?
2. **Database Selection**: How will the chosen ORM map the Ledger aggregate to relational tables while guaranteeing ACID properties? (To be decided in Phase 1.B.2.B).
