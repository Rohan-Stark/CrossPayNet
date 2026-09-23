# CrossPayNet Domain Glossary

This glossary defines the ubiquitous language for the CrossPayNet project. Every term explicitly details its conceptual meaning, what it is *not*, and why it is separated from related concepts.

---

### Account
* **What it IS**: A logical container representing a financial liability of a Bank to a Customer, or between Banks.
* **What it is NOT**: It is not a physical vault, nor is it the actual money. It is not the Ledger itself.
* **Why it is separate**: An account represents ownership and status (e.g., Active, Frozen), distinct from the immutable history of transactions that affected its balance.
* **Related**: Ledger, Customer.
* **Example**: "Alice's Checking Account (Acc: 98765)".

### Aggregate
* **What it IS**: A cluster of domain objects treated as a single unit for data changes, defining a strict transactional consistency boundary.
* **What it is NOT**: It is not merely a collection of related nouns or a database table relationship.
* **Why it is separate**: It exists to guarantee that business invariants are never violated during state changes.
* **Related**: Entity, Value Object, Domain Invariant.
* **Example**: "The Ledger Aggregate ensures debits always equal credits before saving."

### Bank
* **What it IS**: A financial institution participating in the network, capable of holding accounts and routing payments.
* **What it is NOT**: It is not a physical building or a customer.
* **Why it is separate**: Identifies the legal/network entity responsible for authorizing and settling funds.
* **Related**: Customer, Correspondent Relationship.
* **Example**: "First National Bank (BIC: FNBKUS33)".

### Compliance Check
* **What it IS**: The evaluation of a Payment against regulatory rules (e.g., AML, Sanctions screening).
* **What it is NOT**: It is not the Payment itself, nor is it a routing decision.
* **Why it is separate**: Compliance logic is highly volatile, dependent on external lists, and distinct from the mechanical process of moving money.
* **Related**: Payment, Risk Assessment.
* **Example**: "Sanctions screening against the OFAC list".

### Correspondent Relationship
* **What it IS**: A mutual financial agreement where one Bank holds a deposit account (Nostro/Vostro) for another Bank to facilitate cross-border settlement.
* **What it is NOT**: It is not the network cable, nor is it the Payment Message.
* **Why it is separate**: It models the actual financial ties and liquidity that make cross-network routing physically possible.
* **Related**: Bank, Route, Settlement.
* **Example**: "Bank A holds a Nostro account at Bank C".

### Currency
* **What it IS**: The systemic denomination of value (e.g., defined by ISO 4217).
* **What it is NOT**: It is not an amount of money.
* **Why it is separate**: Ensures that arithmetic operations are only performed on matching currency types.
* **Related**: Money.
* **Example**: "USD", "EUR".

### Customer
* **What it IS**: An individual or corporate entity holding accounts at a Bank.
* **What it is NOT**: It is not an Account.
* **Why it is separate**: One customer can own multiple accounts across different currencies.
* **Related**: Account, Bank.
* **Example**: "Alice Smith", "Acme Corp".

### Domain Event
* **What it IS**: A business-relevant fact that has occurred in the past, carrying domain meaning.
* **What it is NOT**: It is not a technical notification (e.g., `DatabaseRowUpdated`), nor is it a command (`CreatePayment`).
* **Why it is separate**: It decouples aggregates conceptually, allowing other modules to react to business facts without tight coupling.
* **Related**: Aggregate.
* **Example**: `PaymentSettled`, `ComplianceHoldApplied`.

### Ledger
* **What it IS**: The authoritative, append-only record of all financial transactions within a Bank.
* **What it is NOT**: It is not a simple `balance` column on an Account table.
* **Why it is separate**: Enforces the double-entry accounting invariant mathematically independent of application state.
* **Related**: Ledger Entry, Account.
* **Example**: "The General Ledger of First National Bank".

### Ledger Entry
* **What it IS**: A single debit or credit line item on the Ledger.
* **What it is NOT**: It is not a complete transfer. 
* **Why it is separate**: A transfer always requires at least two entries (one debit, one credit) to balance.
* **Related**: Ledger.
* **Example**: "Credit $50 to Account Y".

### Money
* **What it IS**: A precise representation of an amount coupled with its currency.
* **What it is NOT**: It is not a floating-point primitive or an un-denominated integer.
* **Why it is separate**: Encapsulates arithmetic rules safely, preventing the addition of USD to EUR.
* **Related**: Currency, Value Object.
* **Example**: "USD 100.00".

### Payment
* **What it IS**: The validated, orchestrating entity representing the end-to-end lifecycle of transferring value between parties.
* **What it is NOT**: It is not the raw API request (Instruction), nor is it the wire payload (Message).
* **Why it is separate**: Acts as the state machine tracking progress across compliance, ledger reservations, and network routing.
* **Related**: Payment Instruction, Payment Message, Settlement.
* **Example**: "Payment ID 12345 currently in state ROUTING".

### Payment Instruction
* **What it IS**: The raw, unvalidated intent from a Customer to move funds.
* **What it is NOT**: It is not a guaranteed or executing transfer.
* **Why it is separate**: Captures user intent *before* the bank accepts the liability or validates funds.
* **Related**: Payment.
* **Example**: "Alice submitted a request via the web app to send $100 to Bob".

### Payment Message
* **What it IS**: The standardized data payload (e.g., ISO 20022 `pacs.008`) sent between institutions over a network.
* **What it is NOT**: It is not the Payment itself.
* **Why it is separate**: A single Payment might require multiple messages (e.g., an initial failure requiring a retry on a different route).
* **Related**: Payment, Route.
* **Example**: "MT103 message sent via SWIFT".

### Risk Assessment
* **What it IS**: A calculated decision regarding the likelihood of fraud or operational risk for a Payment.
* **What it is NOT**: It is not a binary compliance block (like Sanctions).
* **Why it is separate**: Models probabilistic fraud detection (often AI-driven) distinctly from deterministic regulatory rules.
* **Related**: Compliance Check.
* **Example**: "Fraud Score: 85 (High Risk)".

### Route
* **What it IS**: A selected path through one or more correspondent banks to reach the destination bank.
* **What it is NOT**: It is not the relationship itself.
* **Why it is separate**: Multiple correspondent relationships exist, but a specific route is chosen dynamically per Payment.
* **Related**: Correspondent Relationship.
* **Example**: "Bank A -> Bank C -> Bank B".

### Settlement
* **What it IS**: The actual, final movement of liquidity (funds) between Banks to satisfy obligations created by Payments.
* **What it is NOT**: It is not the customer's account being updated (which happens earlier).
* **Why it is separate**: Cross-border payments often settle asynchronously via batching or correspondent accounts, long after the customer sees the funds in their app.
* **Related**: Ledger, Payment, Correspondent Relationship.
* **Example**: "Bank A's Nostro account at Bank C is debited to settle Payment 12345".

### Value Object
* **What it IS**: An immutable object whose identity is based solely on its attributes.
* **What it is NOT**: It is not an Entity; it has no unique ID or lifecycle.
* **Why it is separate**: Safely encapsulates small, cohesive concepts that can be freely passed and discarded.
* **Related**: Entity, Money.
* **Example**: "Money", "Address".
