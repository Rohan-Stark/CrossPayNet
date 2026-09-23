# ADR-004: REST API and OpenAPI Contract

## Status
**Accepted**

## Date
2026-09-22

## Context
CrossPayNet requires an external interface for clients (e.g., the frontend dashboard, simulated external banks) to interact with the core system. We must define the architectural style of this interface and the method for documenting its contract.

## Problem
What architectural style should be used for the initial external API, and how should it be documented?

## Decision
We will use **REST (Representational State Transfer)** as the architectural style for the external API. We will document the API contract using **OpenAPI (v3)**.

*   **REST**: The industry standard for HTTP-based integration. It utilizes standard HTTP methods (GET, POST, PUT, DELETE) and resource-based URIs, which maps well to domain aggregates (e.g., `/payments`, `/accounts`).
*   **OpenAPI**: Provides a machine-readable specification of the API. It acts as the definitive contract between the backend application and any clients, enabling automated documentation generation (e.g., Swagger UI) and client SDK generation.

## Alternatives considered
*   **gRPC**: A high-performance RPC framework using Protocol Buffers.
*   **GraphQL**: A query language for APIs providing flexible, client-driven data fetching.

## Trade-offs
*   **Pros**: REST is universally understood, easy to debug using standard browser tools, and perfectly suited for the initial client-facing boundaries of the simulator. OpenAPI ensures that the contract is explicitly defined and reviewable.
*   **Cons**: REST lacks the strict, binary efficiency of gRPC, and can suffer from over-fetching/under-fetching compared to GraphQL.

## Consequences
*   The backend controllers will be implemented using Spring Web MVC annotations mapped to RESTful endpoints.
*   The OpenAPI specification must be maintained as the source of truth for the API contract.

## Rejected alternatives
*   **gRPC**: Rejected for the initial *external* API due to browser support complexities and higher initial learning curve. It is intentionally deferred and may be reconsidered in later phases for *internal* service-to-service communication if microservices are extracted.
*   **GraphQL**: Rejected because the primary interactions are commanding actions (initiating payments) rather than complex, graph-like data fetching. The added complexity is unwarranted for the initial phases.
