# Project Status

Current Phase: Phase 4 — Payment Engine & Orchestration
Current Subphase: Checkpoint 2 Accepted & Published (Checkpoint 3 `PaymentService` Orchestration Next)

## Completed Phases
* Phase 0 — Engineering Constitution
* Phase 1.A — Domain Discovery, Problem Definition & System Boundaries
* Phase 1.B.1 — Monolith vs Modular Monolith vs Microservices
* Phase 1.B.2.A — Backend Language & Framework Evaluation
* Phase 1.B.3 — Final Technology Stack & Architecture Decisions
* Phase 1.B.4 — Domain Model Refinement
* Phase 1.B.5 — Use Cases & Interaction Flows
* Phase 1.B.6 — System Architecture Design
* Phase 1.B.7 — API & Contract Design
* Phase 1.B.8 — Persistence Architecture
* Phase 1.B.9 — Architecture Review
* Phase 1.B.10 — Phase 1 Acceptance
* **Phase 2 — Banking Foundation** ✅
  * Checkpoint 1 — Foundation ✅
  * Checkpoint 2 — Persistence & Event Registry ✅
  * Checkpoint 3 — Pure Domain & Shared Kernel ✅
  * Checkpoint 4 — Infrastructure & Capabilities ✅
  * Checkpoint 5 — Verification ✅
  * Banking Boundary Completion (Public Contracts & Reservation Lifecycle) ✅
* **Phase 3 — Double-Entry Financial Ledger (ADR-006)** ✅
  * Checkpoint 1 — Architecture & Contract Alignment ✅
  * Checkpoint 2 — Pure Ledger Domain & Public Contracts ✅
  * Checkpoint 3 — Flyway V4–V6 Migrations, JPA Persistence & Triggers ✅
  * Checkpoint 4/5 — `LedgerService`, Conflict-Safe Idempotency, Reversals & Modulith Verification ✅

## Current/Next Phase
* **Phase 4 — Payment Engine & Orchestration** ▶ IN PROGRESS
  * Checkpoint 1 — Pure Payment Domain, State Machine, Fingerprinting & Public Contracts ✅ (Accepted & Pushed: `3fa69c9`)
  * Checkpoint 2 — Flyway V7–V9 Payment Persistence Schema, UTF-16 Length Alignment & PostgreSQL Integrity Constraints ✅ (Accepted & Pushed: `b8688ef`, `676d7eb`)
  * Checkpoint 3 — `PaymentService` Application Orchestration & End-to-End Integration Verification ▶ NEXT

## Upcoming Phases
* Phase 5 — Financial Messaging ○
* Phase 6 — Routing & Correspondent Banking ○
* Phase 7 — Distributed Systems ○
* Phase 8 — Security & Compliance
* Phase 9 — Observability & Operations
* Phase 10 — AI Anomaly Detection
* Phase 11 — RAG Payment Operations Copilot
* Phase 12 — Failure Simulation & Payment Replay Lab
* Phase 13 — Testing, Performance & CI/CD

## Important Decisions
* **Phase 0.A**: The project is strictly an educational simulator, not for real transactions.
* **Phase 1.B**: CrossPayNet is built as an Evolutionary Modular Monolith using Java 25 LTS, Spring Boot 4.1.1, and PostgreSQL 18.
* **Phase 3 (ADR-006)**: Immutable double-entry financial ledger using conflict-safe `INSERT ... ON CONFLICT DO NOTHING` idempotency claims, unconstrained `NUMERIC` with `scale(trim_scale(...)) <= 4` checks, and deferred PostgreSQL commit triggers.
* **Phase 4 (CP1–CP2)**: Payment aggregate state machine (`VALIDATING`, `COMPLIANCE_HOLD`, `ROUTING`, `EXECUTING`, `PENDING_EXTERNAL`, `SETTLED`, `REJECTED`, `CANCELLED`, `FAILED`), Financial Commitment Point at `ROUTING -> EXECUTING`, and database-level state/reason/UTF-16 length invariants (`V7`–`V9`).

## Verification Status
* **Test Suite Status**: 138 full-suite tests across 19 Surefire test suites passing against PostgreSQL 18 Testcontainers (`0` failures, `0` errors, `0` skipped) with Flyway migrations `V1`–`V9`.
* **Chronological Change Log**: See root [`changes.md`](../changes.md) for detailed checkpoint and corrective commit records.
