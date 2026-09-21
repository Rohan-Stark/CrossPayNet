# Learning Notes: Domain Modeling for CrossPayNet

Before selecting technologies like PostgreSQL or Kafka, we must understand the core business we are modeling. This is known as **Domain-Driven Design (DDD)**.

## What is a Domain?
A "Domain" is the sphere of knowledge and activity around which the application logic revolves. For CrossPayNet, the domain is *Cross-Border Payments and Settlement*.

## What is Domain Modeling?
Domain modeling is the process of conceptualizing the real-world problem into software representations. It focuses on the language, rules, and behaviors of the business rather than how data is stored in a database.

## Key DDD Concepts Applied to CrossPayNet

### 1. Domain Entity
An object that is defined primarily by its identity, not its attributes. Its identity persists over time and across different states.
* **Example in CrossPayNet**: An `Account`. Even if the balance changes from $100 to $50, it is still the same `Account` (e.g., Account ID 12345). A `Customer` is also an Entity.

### 2. Value Object
An object that contains attributes but has no conceptual identity. It is immutable; if you change a value object, you are actually replacing it with a new one.
* **Example in CrossPayNet**: `Currency` (e.g., USD). Or a `Money` object representing an amount and currency (e.g., "100 USD"). Two "100 USD" objects are entirely indistinguishable and interchangeable.

### 3. Aggregate and Aggregate Root
A cluster of domain objects that can be treated as a single unit for data changes. The **Aggregate Root** is the only object inside the cluster that outside objects are allowed to hold a reference to, ensuring business rules are enforced.
* **Example in CrossPayNet**: A `Ledger Transaction` might be an aggregate containing multiple `Ledger Entries` (debits and credits). You cannot save a single `Ledger Entry` on its own; you must save the entire `Ledger Transaction` to ensure debits equal credits.

### 4. Business Invariant
A rule that must always be true to maintain the integrity of the domain.
* **Example in CrossPayNet**: "The sum of all debits and credits in a ledger transaction must exactly equal zero." The Aggregate Root is responsible for enforcing this invariant before allowing the transaction to persist.

### 5. System Boundary (Bounded Context)
A boundary within which a particular domain model is defined and applicable. Terms can mean different things in different contexts.
* **Example in CrossPayNet**: A `Payment` in the "Customer Interface" context might mean a simple instruction to send money. A `Payment` in the "Network Routing" context is a complex message containing intermediary bank paths and settlement rules. Recognizing these boundaries prevents creating "God Classes" that try to do everything.

## Domain Concept vs. Technical Implementation
Why do we model the domain first? Because the domain dictates the technology, not the other way around.
* **Domain Concept**: We need an immutable `Ledger Entry` that enforces double-entry accounting invariants.
* **Technical Implementation**: We might choose to store this in a relational database (PostgreSQL) using ACID transactions, or in an event-store (Event Sourcing) using append-only logs.

By defining the domain first, we protect the business logic from becoming tightly coupled to the database schema or the web framework, allowing the system to evolve safely.
