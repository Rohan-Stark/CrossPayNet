# System Architecture Design

This document defines the high-level system boundaries, deployment architecture, and internal structuring principles for the CrossPayNet Evolutionary Modular Monolith.

---

## 1. System Boundaries

CrossPayNet is an educational simulation. We must strictly define what is inside our system boundary and what exists outside.

### Inside CrossPayNet
* **Banking Operations**: Simulated customer accounts, balances, and profiles.
* **Payment Orchestration**: The state machine managing the transfer of value.
* **Ledger**: The authoritative double-entry accounting engine.
* **Simulation Engines**: Logic that simulates external components (e.g., automated correspondent banks, simulated SWIFT endpoints, mock sanctions lists).
* **Compliance & Routing Logic**: Rules evaluating whether a payment can proceed and how it gets there.
* **Operations/Audit**: Interfaces for simulated bank staff to investigate payments.

### Outside CrossPayNet
* **Real Banking Networks**: We do not connect to the actual SWIFT network.
* **Real Money**: No actual funds move.
* **Real Regulatory Systems**: We do not connect to live government databases.
* **External Banks**: Any "external" bank is a simulated participant running within our boundary, not a physical remote system.

---

## 2. Security & Trust Boundaries

* **Frontend to Backend**: The primary external trust boundary. The Next.js frontend is untrusted. All requests must be authenticated and authorized at the REST API boundary of the Spring Boot backend.
* **User to System**: Role-Based Access Control (RBAC) enforces what operations a user can perform (e.g., Customer vs. Compliance Officer).
* **Module to Module**: Internally, modules trust each other (they run in the same process memory), but they enforce *business* rules at their APIs to maintain invariants.

---

## 3. Conceptual Deployment Architecture

While packaged as a single deployable unit initially, the architecture is designed logically around distinct containers:

1. **Web Frontend Container**: Next.js 16 application serving the dashboard UI to Customers and Bank Operators.
2. **Backend Monolith Container**: The Spring Boot 4.1.1 application containing all isolated business modules (Banking, Payment, Ledger, etc.).
3. **Database Container**: A single PostgreSQL 18 instance.

*Evolution Path*: Because the backend enforces strict logical boundaries (via Spring Modulith), if the Network/Messaging module requires independent scaling in the future, it can be extracted into its own Container with minimal code rewriting.

---

## 4. Internal Layer Flow & Dependency Direction

Within a module, the code is structured conceptually using layered or clean architecture principles. The flow of dependencies points **inward** toward the domain.

* **API / Presentation Layer**: Translates external HTTP/REST requests into application commands. (Depends on Application Layer).
* **Application / Use-Case Layer**: Orchestrates business use cases (e.g., "Initiate Payment"). Fetches entities, coordinates actions, and manages local database transactions. (Depends on Domain Layer).
* **Domain Layer**: Contains the core business logic, entities, value objects, and domain services. **Must have no dependencies on outer layers or specific frameworks** (other than language primitives).
* **Infrastructure / Persistence Layer**: Implements database access (JPA/Hibernate) or external API clients. (Depends on Domain Layer interfaces, utilizing Dependency Inversion).

*Note*: This is a candidate structure. Simple modules may collapse Application and Domain layers, but the dependency rule (Domain depends on nothing) remains strict.

---

## 5. Cross-Module Communication

To prevent the monolith from becoming a "Big Ball of Mud," communication between modules is strictly regulated:

* **Synchronous Calls**: Permitted when immediate consistency is required. This is done by calling a public Java Interface exposed by the target module. Examples:
  * `Payment` $\rightarrow$ `Banking.ReserveFunds`
  * `Payment` $\rightarrow$ `Routing.SelectRoute`
* **Asynchronous Events**: Used when eventual consistency is acceptable or to decouple side effects. Examples:
  * `Payment` $\rightarrow$ `Compliance` evaluation
  * `Payment` $\rightarrow$ `Network/Messaging` dispatch
* **Durable Event Publication**: Any workflow-critical asynchronous event must use durable transactional publication (e.g., the Outbox pattern). This durable publication is a durability mechanism that guarantees the event is not lost if the application crashes; it is not the definition of the domain event itself (which is simply a business fact).
* **Future Transports**: Kafka remains a future transport mechanism for these events and is NOT implemented in Phase 1.

---

## 6. Cross-Module Database Access Rules

Modules are physically co-located in the same PostgreSQL database, but logically isolated.
* **Transactional Writes**: Modules **must not** write to tables owned by another module. They must use the owning module's Java API or events.
* **Business/Domain Access**: Modules **must not** perform SQL joins across module boundaries for business logic. 
* **Reporting / Analytics Reads**: Recognizing that strict boundaries make cross-domain queries difficult, an explicit exception may be made for dedicated read models (CQRS) or reporting views, provided this access is read-only and strictly managed outside the core transactional domain paths.
