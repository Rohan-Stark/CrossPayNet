# ADR-001: Evolutionary Modular Monolith Architecture

## Status
**Accepted**

## Date
2026-09-22

## Context
CrossPayNet is an educational simulator designed to model complex financial domains (Payments, Double-Entry Ledgers, Settlement) and eventually teach distributed systems concepts (eventual consistency, message brokers, failure recovery). We need an architectural approach that balances initial learning of domain logic with the eventual introduction of network complexity.

## Problem
How should the backend application be structured to ensure logical boundaries between domains while avoiding premature operational complexity?

## Decision
We will implement CrossPayNet using an **Evolutionary Modular Monolith** architecture.
The system will start as a single deployable unit. However, strict internal logical boundaries will be enforced between domains (e.g., `Payment`, `Ledger`, `Compliance`). Internal communication will occur via defined APIs or in-memory asynchronous events. 

In later phases (Phase 7+), as specific learning objectives dictate (e.g., learning Kafka), selected modules may be extracted into independent microservices. 

## Alternatives considered
* **Microservices (Day 1)**: Deploying `PaymentService` and `LedgerService` independently over a network from the start.

## Trade-offs
* **Pros**: Keeps local development fast, simplifies E2E testing, avoids CI/CD and orchestration complexity initially, and allows the team to focus entirely on correct financial domain modeling.
* **Cons**: Delays the gratification of working with message brokers and distributed tracing infrastructure.

## Consequences
* Developers must exhibit strict discipline to not bypass logical module boundaries. We must rely on tooling (e.g., Spring Modulith) to enforce these boundaries mechanically at build time.
* The system is explicitly designed for future extraction, meaning modules should not share database tables directly.

## Rejected alternatives
* **Traditional Monolith**: Rejected because it naturally degrades into a tightly coupled system, making future service extraction nearly impossible.
* **Day-1 Microservices**: Rejected because defining the correct bounded contexts is difficult without prior domain experience. Refactoring across network boundaries is extremely expensive.
