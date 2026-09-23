# Learning Notes: Technology Decisions

Architecture and technology selection is about aligning tools with the specific engineering constraints and domain requirements of the system. This document explains the rationale behind the CrossPayNet technology stack.

## 1. How to Choose a Technology Stack
A technology should never be chosen merely because it is trendy, "enterprise", or popular in tutorials. It must be evaluated against:
* **Domain Fit**: Does it naturally express the business rules? (e.g., Relational databases for Ledgers).
* **Architectural Goals**: Does it support the desired boundaries? (e.g., Modular monolith support).
* **Learning/Team Context**: Does it fulfill the educational or operational goals of the team?

## 2. Decoupling the Stack Terminology
It is critical to distinguish between the layers of the application stack:
* **Language (Java)**: Defines the syntax, static typing, and compiler checks.
* **Runtime (JDK 25)**: Defines how the code executes, memory management (Garbage Collection), and concurrency (Virtual Threads).
* **Framework (Spring Boot)**: An opinionated structure that calls your code (Inversion of Control). It provides dependency injection and configuration management.
* **Library (Hibernate)**: Code you call to perform a specific task (e.g., mapping objects to SQL).

## 3. Persistence: ORM vs. Database
* **The Database (PostgreSQL)**: The actual engine storing data on disk, enforcing ACID properties (Atomicity, Consistency, Isolation, Durability), foreign keys, and constraints.
* **The ORM (JPA/Hibernate)**: A persistence abstraction layer sitting inside the application. It translates Java objects into SQL queries. **The ORM is not the database.** While PostgreSQL handles the low-level locking, the application domain logic must still handle authorization and ensure business invariants are met before asking the ORM to save data.

## 4. Why PostgreSQL for a Ledger?
Financial systems require absolute consistency. If an account is debited, another must be credited simultaneously. PostgreSQL excels here due to:
* **Strict Relational Constraints**: Preventing orphaned records.
* **ACID Transactions**: Guaranteeing that partial updates cannot occur.
* **Concurrency Control**: Managing simultaneous requests attempting to modify the same account balance.

## 5. API Style vs. Implementation Framework
* **API Style (REST)**: A conceptual architectural style for designing networked applications (stateless, resource-based URIs, standard HTTP methods).
* **API Framework (Spring Web)**: The specific code library used to implement those RESTful endpoints in Java.
* **API Contract (OpenAPI)**: A language-agnostic specification defining exactly what the REST API looks like, allowing frontend and backend to agree on the payload shape.

## 6. Modular Monolith Tooling
Without discipline, monoliths turn into tightly coupled "Big Balls of Mud." 
* **Spring Modulith** mechanically verifies that Java packages (modules) do not illegally depend on each other. It ensures the `Payment` module communicates with the `Ledger` module only through approved public interfaces or events, enforcing encapsulation *before* we ever consider network boundaries.

## 7. Strategic Deferment (Why not Kafka now?)
**Kafka** is a powerful distributed streaming platform. However, introducing it on Day 1 shifts the developer's focus from "How does a financial ledger work?" to "How do I configure ZooKeeper/KRaft and debug consumer group rebalancing?" 
By intentionally deferring Kafka to Phase 7, we can master the domain logic first via internal in-memory events, and then introduce Kafka specifically to learn about distributed messaging.

## 8. Single-Language vs. Polyglot Architecture
Why doesn't the future AI requirement dictate the core language?
* **Single-Language (Python everywhere)**: Optimizes for AI integration but sacrifices the robust static typing, mature declarative transaction management (`@Transactional`), and enterprise modularity tooling of Java—features critical for a financial ledger.
* **Polyglot (Java Core + Python AI)**: We use the best tool for the specific job. The core Ledger requires absolute transactional integrity (Java/Spring). The future Anomaly Detection requires deep ML ecosystem support (Python). They will communicate across a defined network boundary, fulfilling our distributed systems learning goals.

## 9. Technology Lifecycle: LTS vs. Latest
We selected **Java 25 LTS** (Long-Term Support). 
* **Latest releases** (e.g., Java 26) offer cutting-edge features but may only be supported for 6 months.
* **LTS releases** provide years of guaranteed security patches and stability. For foundational core systems like payment networks, stability and predictable upgrade paths are vastly more important than immediate access to niche language features.
