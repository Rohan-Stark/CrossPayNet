# Learning Notes: Architecture Patterns

As we define CrossPayNet, understanding architectural patterns is crucial. Architecture should always follow system needs, not industry trends.

## 1. Traditional Monolith
A single unit of deployment containing all business logic.
* **Pros**: Simple to deploy, easy to test end-to-end, fast in-memory communication.
* **Cons**: Tends to degrade into a "Big Ball of Mud" where `Payment` code directly modifies `Ledger` database tables. A bug in the `Compliance` check can crash the entire `Ledger` system because they share the same memory space.

## 2. Modular Monolith
A single deployable unit, but with strictly enforced internal boundaries.
* **How it works in CrossPayNet**: The `Payment` module and `Ledger` module live in the same codebase. However, `Payment` cannot access the `Ledger`'s database tables. It must call a `LedgerService.processTransfer()` interface.
* **Why it matters**: It provides the deployment simplicity of a Monolith while forcing developers to think about clean contracts (APIs) between domains. It makes future extraction into microservices vastly easier.

## 3. Microservices
Independently deployable services communicating over a network.
* **Distributed Systems Complexity**: Calling a local function is instantaneous and guaranteed. Calling a microservice over the network introduces latency, timeouts, and network partitions. 
* **Failure Amplification**: In a monolith, if a method fails, you get an exception. In microservices, if `Payment` calls `Compliance` and the network drops, `Payment` doesn't know if `Compliance` received the request or not. Should it retry? This introduces the need for **Idempotency** (ensuring a retry doesn't duplicate a financial action).

## 4. Coupling and Cohesion
* **Cohesion**: Things that change together should live together. (e.g., `Ledger` and `LedgerEntry` are highly cohesive).
* **Coupling**: The degree of interdependence between modules. We want loose coupling. If the `Compliance` rules change, the `Payment` routing logic shouldn't need to be rewritten.

## 5. Synchronous vs. Asynchronous Communication
* **Synchronous (HTTP/gRPC)**: The caller waits for a response. If a Customer submits a payment, the `CustomerService` might synchronously call `PaymentService` to get an immediate success/fail response.
* **Asynchronous (Events/Messaging)**: The caller fires a message and forgets about it. `PaymentService` might publish a `PaymentValidated` event. The `LedgerService` listens to this event and processes it when it has capacity. This provides better resilience but requires handling **Eventual Consistency**.

## 6. Evolutionary Architecture
The most successful microservice architectures often started as well-structured monoliths. By starting with a Modular Monolith for CrossPayNet, we can focus on getting the financial domain logic right. Once the logic is solid, we can selectively extract modules (like a high-traffic `Routing` service) into a microservice to learn distributed systems concepts, without having to build a complex distributed architecture from day one.
