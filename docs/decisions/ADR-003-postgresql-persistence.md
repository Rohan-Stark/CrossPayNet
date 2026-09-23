# ADR-003: PostgreSQL for Primary Persistence

## Status
**Accepted**

## Date
2026-09-22

## Context
CrossPayNet simulates a financial messaging and settlement network. A core component is the double-entry financial ledger, which tracks account balances and transactions. This data requires rigorous integrity, consistency, and concurrency control to ensure balances are never left in an invalid state.

## Problem
What database engine and data-access strategy should be used to persist the core domain data?

## Decision
We will use **PostgreSQL 18** as the primary relational database, **JPA/Hibernate** as the persistence/data-access layer, and **Flyway** for schema migrations.

*   **PostgreSQL 18**: Provides the database transaction/ACID capabilities, required relational model, strict constraints, foreign keys, and row-level locking capabilities necessary for high-integrity financial records.
*   **JPA/Hibernate**: Serves as the persistence/data-access layer used to interact with the database, abstracting JDBC interactions and mapping domain models to database tables. It does not imply that Hibernate itself provides ACID guarantees independently.
*   **Flyway**: Ensures that all database schema changes are version-controlled, reproducible, and executed automatically on startup.

*Note: PostgreSQL guarantees data integrity at the storage level, but the application domain logic remains strictly responsible for enforcing financial invariants, authorization, and valid state transitions before passing data to the ORM.*

## Alternatives considered
*   **MongoDB (NoSQL Document Store)**

## Trade-offs
*   **Pros**: PostgreSQL provides mathematically sound relational constraints, preventing orphaned records and ensuring atomic updates across multiple tables within a single transaction boundary.
*   **Cons**: Relational mapping (via Hibernate) introduces the "Object-Relational Impedance Mismatch," requiring developers to carefully manage lazy loading, entity state, and complex queries to avoid performance bottlenecks (e.g., N+1 query problems).

## Consequences
*   Developers must write and commit `.sql` Flyway migration scripts for any schema change. Manual database alterations are prohibited.
*   Future vector search capabilities (for AI/RAG) are planned to be colocated using the `pgvector` extension within this same PostgreSQL instance to minimize infrastructure sprawl.

## Rejected alternatives
*   **MongoDB / NoSQL**: Rejected for the core ledger. Financial ledgers inherently possess a relational structure (Accounts have many Transactions; Transactions affect two Accounts). Enforcing these constraints in application code rather than the database engine is error-prone and dangerous for financial data.
