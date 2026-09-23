# Learning Notes: Backend Languages and Frameworks

When designing systems like CrossPayNet, it is crucial to separate the characteristics of a language from the opinions of a framework.

## 1. Language vs. Runtime vs. Framework vs. Tooling
* **Language**: The syntax and typing rules (e.g., Java, Python, Go). Static typing (Java/Go) prevents classes of errors at compile time, which is vital for financial ledgers. Dynamic typing (Python) offers faster prototyping but requires rigorous testing to catch type errors.
* **Runtime**: The execution environment. The JVM (Java) or CLR (.NET) provides garbage collection and JIT compilation. Node.js (TypeScript) provides a single-threaded asynchronous event loop. 
* **Framework**: An opinionated structure for your code (e.g., Spring Boot, NestJS). A framework calls your code (Inversion of Control).
* **Tooling/Libraries**: ORMs (Entity Framework, SQLAlchemy) or testing tools (JUnit, Pytest) that provide specific capabilities.

## 2. Concurrency Models
Handling multiple simultaneous payment requests requires understanding concurrency.
* **Thread-per-request (Traditional Java/C#)**: Each HTTP request gets a dedicated OS thread. Easy to debug, but memory-intensive under high load. Modern runtimes (Java 21 Virtual Threads) solve this scaling limit.
* **Event Loop (Node.js)**: A single thread handles all I/O asynchronously. Great for network-heavy apps, but a CPU-intensive task (like hashing a password) blocks the entire application.
* **Goroutines (Go)**: Extremely lightweight threads managed by the Go runtime. Very efficient and simple to reason about.

## 3. Transaction Boundaries
A financial system like CrossPayNet absolutely requires database transactions. If we debit Account A, we must credit Account B in the same atomic operation.
* **Declarative (Spring Boot)**: You annotate a method with `@Transactional`. The framework automatically manages opening, committing, or rolling back the database connection.
* **Explicit (Go)**: You manually create a `sql.Tx` object, pass it to every function involved in the transfer, and manually call `tx.Commit()` or `tx.Rollback()`. This is safer from "magic" errors but clutters business logic with database concerns.

## 4. Framework Opinionation: Modularity
* **Highly Opinionated (Spring Boot, NestJS)**: Provide built-in ways to define modules and dependency injection. This makes building a Modular Monolith easier because the framework enforces boundaries.
* **Unopinionated (Go Standard Library, FastAPI)**: They don't tell you how to structure your folders or inject dependencies. This offers immense freedom, but means the team must invent and rigidly enforce their own architectural rules, or the codebase turns into a Big Ball of Mud.

## 5. Polyglot Architecture
Why not write the whole system in Python since we need AI later?
* Architecture involves trade-offs. Python is the king of ML, but lacks the enterprise refactoring safety and declarative transaction boundaries of Java/C#. 
* By separating the core ledger (Java/C#) from the AI Copilot (Python), we create a **Polyglot Architecture**. We use the best tool for the specific domain, communicating across strict network boundaries.
