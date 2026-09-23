# ADR-002: Java and Spring Boot for Core Backend

## Status
**Accepted**

## Date
2026-09-22

## Context
The core of CrossPayNet requires modeling a highly consistent financial domain within an Evolutionary Modular Monolith. The chosen ecosystem must support strict internal boundaries, robust transaction management, and an eventual evolution toward a distributed architecture. Furthermore, future phases will introduce AI/ML components, raising the question of a single-language vs polyglot architecture.

## Problem
Which programming language, runtime, and framework should be selected for the core backend to meet these constraints?

## Decision
We will use **Java 25 LTS** as the programming language and runtime, **Spring Boot 4.1.1** as the application framework, **Spring Modulith 2.1.1** for architectural verification, and **Maven** as the build system.

*   **Java 25 LTS**: Provides strong static typing (catching classes of defects at compile time) and a highly performant runtime with Virtual Threads. (LTS chosen for stability).
*   **Spring Boot 4.1.1**: Provides the inversion-of-control container, dependency injection, and declarative transaction management (e.g., `@Transactional`), which simplifies coordinating operations across the ledger.
*   **Spring Modulith 2.1.1**: Mechanically verifies logical module boundaries, preventing the monolith from degrading.
*   **Maven**: The industry-standard declarative build tool for Java.

The future AI/ML components (e.g., Anomaly Detection) will be implemented as separate microservices using **Python**, creating an intentional Polyglot architecture.

## Alternatives considered
*   **Python (FastAPI) exclusively**: Using Python for both the core ledger and the AI components.
*   **C# (.NET Core)**: An excellent, highly capable alternative for enterprise modular monoliths.
*   **Go**: A highly performant language with excellent concurrency primitives.

## Trade-offs
*   **Pros**: Java/Spring provides unparalleled tooling for transaction boundaries and modularity enforcement. Separating the AI into Python allows using the best tool for each specific job.
*   **Cons**: Java/Spring carries a steeper learning curve regarding its "magic" (annotations, DI context) compared to minimalist frameworks. Managing a Polyglot architecture later increases CI/CD complexity.

## Consequences
*   The team will benefit from robust enterprise patterns but must learn how the Spring Framework abstracts underlying complexities (like JDBC connections and proxy generation).
*   Future AI integration will require managing cross-network communication rather than in-memory function calls.

## Rejected alternatives
*   **Python exclusively**: Rejected for the core ledger because it lacks the strict compiler boundaries, enterprise refactoring tooling, and declarative transaction management abstractions found in the Java ecosystem.
*   **Go**: Rejected because its lack of opinionated architectural frameworks shifts the burden of inventing and enforcing modular monolith rules entirely onto the developer.
