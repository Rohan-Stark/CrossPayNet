# CrossPayNet Technology Stack

This document defines the approved technology baseline for the CrossPayNet simulator. It clearly distinguishes between technologies finalized for the current implementation phase, those planned for later phases, and those intentionally deferred or rejected.

*(Version information verified using official documentation as of September 2026).*

---

## 1. Finalized Baseline (Current Stack)

These technologies are explicitly approved for the initial implementation of the core application.

### Architecture
* **Evolutionary Modular Monolith**: The system is designed as a single deployable unit with strict, logical internal module boundaries. It is designed to evolve into a distributed system (microservices) only when concrete engineering or learning requirements justify extraction.

### Backend
* **Language/Runtime**: **Java 25 LTS**. Selected for its robust static typing, exceptional JIT compilation, and long-term support lifecycle.
* **Framework**: **Spring Boot 4.1.1**. Provides the opinionated inversion-of-control container, dependency injection, and declarative transaction management.
* **Modularity Verification**: **Spring Modulith 2.1.1**. Used to mechanically verify and enforce logical module boundaries within the monolith, preventing cyclic dependencies and architectural decay.
* **Build System**: **Maven**. The industry-standard declarative build and dependency management tool for Java enterprise applications.

### Database / Persistence
* **Database**: **PostgreSQL 18**. The primary relational database. PostgreSQL provides the database transaction/ACID capabilities, strict constraints, and concurrency controls necessary for a double-entry ledger. *(Note: PostgreSQL provides data integrity, but domain rules like authorization and state transitions must still be enforced by the application layer).*
* **ORM / Data Access**: **JPA / Hibernate**. Provides the persistence/data-access layer used to interact with the database, mapping Java domain entities to the relational database. It does not provide ACID guarantees independently.
* **Schema Migration**: **Flyway**. Enforces version-controlled, reproducible database schema migrations. No manual schema modifications are permitted.

### API
* **Style**: **REST**. The architectural style for the initial external client-facing boundaries.
* **Documentation/Contract**: **OpenAPI (v3)**. Used to explicitly define and document the HTTP API contracts.

### Frontend
* **Framework**: **Next.js 16**. React framework for server-side rendering and routing.
* **Language**: **TypeScript**. Provides type safety for the frontend.
* **Styling**: **Tailwind CSS**. Utility-first CSS framework for rapid UI styling.
* **UI Components**: **shadcn/ui**. Accessible, customizable component primitives.

### Local Tooling
* **Git**: Version control.
* **Docker**: Local infrastructure containerization (using `docker-compose.yml` to spin up supporting services).

---

## 2. Planned Later (Future Technologies)

These technologies are explicitly reserved for future phases. **They are not to be implemented or configured during the initial core buildup.**

### Messaging & Distributed Systems (Phase 7+)
* **Apache Kafka**: Will be introduced when the system requires durable asynchronous events, independent consumers, and failure simulation (e.g., cross-bank message routing).

### Security & Identity (Phase 8)
* **Spring Security**: The planned application-level security framework.
* **OAuth2 / OpenID Connect (OIDC)**: The planned authentication/authorization standards.
* **Keycloak**: The planned external Identity Provider (IdP).

### Observability (Phase 9)
* **OpenTelemetry**: For standardized distributed tracing, metrics, and logs.
* **Prometheus**: Planned metrics backend.
* **Grafana**: Planned visualization and operations dashboard.

### AI & Polyglot Architecture (Phase 10+)
* **Python & FastAPI**: Reserved specifically for future AI/ML components (e.g., Anomaly Detection, inference endpoints). CrossPayNet deliberately separates the core financial backend (Java) from the AI experimentation layer (Python).
* **Spring AI**: Potential abstraction for integrating LLM/RAG capabilities within the Java Copilot layer.
* **pgvector**: Planned PostgreSQL extension for vector similarity search, colocating embeddings with the relational database to simplify infrastructure.

### Caching
* **Redis**: Planned for future requirements involving short-lived state, rate limiting, or distributed coordination.

---

## 3. Deferred / Not Selected

These technologies were considered but intentionally rejected for the current project scope.

* **Microservices (Initial)**: Rejected as the starting architecture. Distributes complexity too early, risking a "distributed monolith" before domain boundaries are proven.
* **MongoDB (or NoSQL)**: Rejected for the core ledger. Financial double-entry ledgers heavily benefit from strict relational constraints and ACID transactions, which PostgreSQL provides natively.
* **gRPC**: Deferred. REST is sufficient for external APIs. gRPC may be considered later for internal service-to-service communication if microservices are extracted.
* **Kubernetes**: Deferred. Local Docker Compose is sufficient for the educational simulation. Orchestration will only be introduced if deployment scaling becomes a specific learning objective.
* **Separate Vector Database (e.g., Pinecone/Milvus)**: Deferred in favor of `pgvector` to minimize infrastructure sprawl, as PostgreSQL can handle the anticipated vector workload.
