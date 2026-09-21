# Domain Overview: CrossPayNet

## 1. Project Definition
CrossPayNet is an educational simulator inspired by concepts used in financial messaging and cross-border payment systems. It is designed to demonstrate software engineering concepts such as distributed systems, financial ledgers, event-driven architectures, and AI operations. It is **not** a real financial network, does **not** connect to real banks, and does **not** process real money.

## 2. The Simulated Problem
CrossPayNet conceptually simulates the flow of a cross-border payment. A simulated customer at a Sending Bank initiates a payment to a customer at a Receiving Bank. This instruction is validated, translated into a financial message, routed through a simulated network (potentially involving a Correspondent Bank), undergoes rule-based compliance and risk checks, is processed by the destination, and finally settled on a simulated double-entry ledger.

## 3. Actors
### Human Actors (Simulated or Real User Interfaces)
* **Customer**: The initiator or receiver of a payment. (Inside simulation as an entity, outside as a real human).
* **Bank Operator / Operations Analyst**: Monitors payment flows, handles exceptions, and reviews compliance holds. (Inside simulation).
* **Network Administrator**: Manages the routing, nodes, and health of the simulated network. (Inside simulation).

### System Actors/Components
* **Sending Bank**: The financial institution originating the payment on behalf of the customer. (Inside simulation).
* **Receiving Bank**: The destination financial institution serving the beneficiary. (Inside simulation).
* **Correspondent Bank**: An intermediary bank providing routing and settlement services between banks without direct relationships. (Inside simulation).
* **Payment Network**: The central or distributed routing and messaging fabric connecting the banks. (Inside simulation).
* **Compliance Officer (System)**: Automated rule engine that flags or holds payments based on simulated risk rules. (Inside simulation).
* **AI Operations Copilot**: An intelligent assistant for Bank Operators to query payment status and resolve anomalies. (Inside simulation).

## 4. System Boundaries
### Inside CrossPayNet
* Simulated banks, branches, and routing identifiers (BIC equivalents).
* Simulated accounts and ledger balances.
* Payment instructions (from customer to bank).
* Financial messages (between banks).
* Routing logic and simulated correspondent relationships.
* Simulated settlement mechanisms.
* Simulated rule-based compliance checks (AML/Sanctions rules).
* Audit records, observability data, and AI operations tooling.

### Outside CrossPayNet (Explicitly Excluded)
* Real banking core systems and real accounts.
* Real SWIFT infrastructure or actual central bank clearing systems.
* Real-world regulatory decision systems and legal reporting.
* Real money movement and FX markets.
* Real customer KYC verification processes.

## 5. Core Domain Concepts
* **Bank**: A simulated financial institution that holds accounts and participates in the network.
* **Customer**: A simulated entity that owns accounts and initiates payments.
* **Account**: A record of balance for a Customer or for a Bank (e.g., Nostro/Vostro accounts) denominated in a specific currency.
* **Currency**: The simulated denomination of value (e.g., USD, EUR).
* **Payment Instruction**: The initial request from a Customer to their Bank to send funds. This is distinct from the message sent over the network.
* **Payment Message**: The standardized financial message (inspired by ISO 20022) sent between Banks over the Payment Network to execute the payment instruction.
* **Ledger**: The authoritative double-entry accounting record that tracks all financial movements.
* **Ledger Entry**: An individual debit or credit applied to an Account within a transaction boundary.
* **Settlement**: The final, irrevocable transfer of simulated value between Banks' accounts (often Nostro/Vostro) that extinguishes the obligation.
* **Correspondent Relationship**: A simulated bilateral agreement between two Banks, often supported by Nostro/Vostro accounts, allowing them to route messages and settle funds.
* **Compliance Check**: An evaluation against simulated rules (e.g., velocity limits, banned names).

## 6. Business Concepts vs. Technical Concepts
It is critical to distinguish between domain concepts and implementation details:
* **Business**: *Payment*, *Account*, *Settlement*, *Ledger*.
* **Technical (To be decided later)**: HTTP requests, Kafka topics, PostgreSQL tables, Redis caches, Microservices. 
Currently, we are only defining the Business concepts.

## 7. Business Relationships
* A `Customer` **owns** one or more `Account`s.
* A `Bank` **serves** `Customer`s and **maintains** `Account`s.
* A `Customer` **initiates** a `Payment Instruction`.
* A `Payment Instruction` **produces** a `Payment Message`.
* A `Bank` **has a** `Correspondent Relationship` with another `Bank`.
* A `Payment Message` **is routed via** the `Payment Network`.
* A `Payment Message` **results in** `Settlement`.
* `Settlement` **produces** balancing `Ledger Entries`.

## 8. Payment Lifecycle Observations
A payment moves through various conceptual states. These states may apply to the *Instruction*, the *Message*, or the *Settlement*.
* **Created**: Instruction received from Customer.
* **Validated**: Instruction syntax and limits checked.
* **Processing / AML Hold**: Evaluating against compliance rules.
* **Submitted**: Message generated and sent to network.
* **Routing**: Network determines path (direct or correspondent).
* **Received**: Destination bank acknowledges receipt.
* **Settled**: Funds reflect in Nostro/Vostro and end-customer accounts.
* **Failed / Rejected**: Error at validation, compliance, or destination.
* **Returned**: Destination bank cannot apply funds and returns them.

*Observation:* Payment Instruction state (Customer -> Bank) is distinct from Payment Message state (Bank -> Bank). Failure at the message level must propagate back to the instruction level.

## 9. Major Business Flows
* **Flow A (Internal/Domestic)**: Customer -> Bank -> Recipient (on same bank or simulated local clearing).
* **Flow B (Direct Cross-Border)**: Customer -> Sending Bank -> Payment Network -> Receiving Bank -> Recipient.
* **Flow C (Correspondent Route)**: Sending Bank -> Correspondent Bank -> Receiving Bank.
* **Flow D (Compliance Hold)**: Payment -> Compliance -> Hold -> Review -> Release/Reject.
* **Flow E (Return Flow)**: Payment -> Receiving Bank (Invalid Account) -> Return Message -> Sending Bank -> Customer Refund.

## 10. Domain Invariants
* **Accounting Balance**: The sum of all debits and credits in a ledger transaction must always equal zero.
* **Immutability of History**: A settled payment or posted ledger entry cannot be modified or deleted. Corrections require a compensatory reversing entry.
* **Idempotency**: Submitting the identical Payment Instruction or Payment Message multiple times must not result in duplicate settlements.
* **Message Integrity**: A Payment Message cannot be altered in transit without invalidating its signature/integrity check.
* **Routing Validity**: Messages can only be routed directly between Banks that hold a valid Correspondent Relationship.

## 11. Explicit Exclusions (What We Are NOT Simulating)
* Real money and live FX conversion markets.
* Real SWIFT network connectivity and live BIC validation against real registries.
* Legally compliant AML systems or integrations with real sanctions databases (OFAC, etc.).
* Real banking core systems (interest calculation, loans, real KYC).
* Production-grade financial certification or regulatory reporting.

## 12. Assumptions
* Banks, Customers, and Balances are purely simulated.
* Network failures (e.g., dropped messages, timeouts) will be intentionally simulated for educational purposes.
* Compliance is rule-based and deterministic within the simulation.
* "ISO 20022-inspired" means using similar structural concepts (e.g., pacs.008) but without strict schema validation unless explicitly built for educational value.

## 13. Open Architectural Questions
These questions are identified for carryover into subsequent Phase 1 subphases, where system architecture and technology choices will be evaluated:
1. **Monolith vs. Microservices**: Should the initial simulation be a modular monolith, or should we immediately decouple into services (e.g., `ledger-service`, `payment-service`)?
2. **Synchronous vs. Asynchronous**: Which parts of the payment flow require synchronous HTTP/gRPC, and which parts should rely on event-driven asynchronous messaging (e.g., Kafka)?
3. **Data Consistency**: Where is strong consistency absolute (e.g., Ledger), and where can we tolerate eventual consistency (e.g., read models, dashboards)?
4. **State Management**: Should Payment and Payment Message state be managed centrally (an orchestrator) or choreographically across services?
5. **Idempotency Mechanisms**: How will we technically enforce idempotency across distributed components?

## 14. Real-World Concepts vs. Our Simulation

| Real-world concept | CrossPayNet equivalent | Fidelity level |
| :--- | :--- | :--- |
| Bank | Simulated Bank Entity | Simplified |
| Customer | Simulated Customer | Simplified |
| BIC (Bank Identifier Code) | Simulated Routing Identifier | Conceptual |
| SWIFT Message (MT/MX) | ISO-inspired JSON/XML Message | Educational |
| Correspondent Banking | Simulated Routing Table & Nostro/Vostro | Simplified |
| Settlement | Double-Entry Database Transaction | Educational / High |
| Compliance (AML/KYC) | Hardcoded Rule Engine | Simplified |
