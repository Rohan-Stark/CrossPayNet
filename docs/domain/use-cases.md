# Business Use Cases

This document defines the major actors and business use cases for CrossPayNet, focusing on conceptual domain boundaries and business goals rather than technical API endpoints.

---

## 1. Actor Classification

It is critical to distinguish between human users, simulated network participants, and internal system capabilities.

### Human Actors
* **Customer**: An individual or corporate user who initiates payments and views account balances.
* **Bank Operator / Operations Analyst**: A bank employee responsible for investigating failures, monitoring routes, and handling disputes.
* **Compliance Officer**: A specialized bank employee authorized to review and override compliance holds.
* **Bank Administrator**: A super-user who configures the simulated bank (e.g., creating accounts for testing).

### Domain Participants (Simulated Entities)
These represent external entities participating in the financial network, modeled as domain concepts within our simulation.
* **Sending Bank**: The institution initiating the cross-border payment on behalf of the Customer.
* **Receiving Bank**: The destination institution holding the beneficiary's account.
* **Correspondent Bank**: An intermediary institution holding Nostro/Vostro accounts to facilitate settlement between banks without a direct relationship.

### Internal System Components
* **Future AI Operations Copilot**: A future internal assistant capability. It is *not* a distinct human actor; rather, it acts on behalf of an authorized human (e.g., an Operations Analyst) to suggest routing fixes or summarize payment timelines.
* **Payment Engine**: The core internal orchestration component.

---

## 2. Core Business Use Cases

### 2.1 "Initiate Cross-Border Payment" (Primary)
* **Primary Actor**: Customer
* **Goal**: Transfer funds from the Customer's account to a beneficiary at a foreign bank.
* **Preconditions**: Customer holds an active account with sufficient funds.
* **Trigger**: Customer submits a Payment Instruction.
* **Main Flow**:
  1. Bank validates instruction format and limits.
  2. Ledger reserves funds (Customer available balance is reduced; actual ledger balance remains unchanged).
  3. Compliance evaluates the payment (cleared).
  4. Routing selects a valid correspondent path.
  5. Payment transitions to `EXECUTING` (financially committed).
  6. Payment Message is generated and sent.
  7. Receiving Bank processes and acknowledges the message.
  8. Settlement occurs via Nostro/Vostro accounts.
  9. Payment is marked `SETTLED`.
* **Postconditions**: Funds are irrevocably transferred. Beneficiary bank has acknowledged receipt.
* **Invariants**: Funds must be reserved before routing; Compliance must clear before commitment.
* **Idempotency Requirement**: Yes. Submitting the exact same instruction must return the existing payment status, preventing accidental duplicate financial effects.
* **Authorization**: Customer (owning the debit account).
* **Owning Module**: Payment Module.

### 2.2 "Release Payment from Compliance Hold"
* **Primary Actor**: Compliance Officer
* **Goal**: Allow a payment suspected of violating a rule (e.g., a false positive Sanctions match) to proceed.
* **Preconditions**: A Payment is in the `COMPLIANCE_HOLD` state.
* **Main Flow**:
  1. Officer reviews the held payment.
  2. Officer approves the release, citing a justification.
  3. Payment resumes its lifecycle, entering the Routing phase.
* **Authorization**: Strictly limited to Compliance Officer.
* **Owning Module**: Compliance Module.

### 2.3 "Fund Account (Simulation Bootstrap)"
* **Primary Actor**: Bank Administrator
* **Goal**: Add synthetic funds to an account to allow testing.
* **Important Note**: This is an **administrative simulation operation**, *not* a real-world core banking use case. In a real system, funding occurs via deposits, payroll, or inbound transfers. For CrossPayNet, this bypasses the standard transfer lifecycle to bootstrap the simulation.
* **Owning Module**: Banking Module.

### 2.4 "Process Payment Return"
* **Primary Actor**: Receiving Bank (Domain Participant)
* **Goal**: Return funds for a payment that was successfully received but could not be applied (e.g., account closed).
* **Concept**: A Return is modeled as a **compensating financial operation / new related payment** rather than a continuation of the original payment. The original payment successfully reached `SETTLED` (the message was delivered and funds moved between banks). A Return is a new, reversed flow carrying a reference to the original payment.
* **Owning Module**: Payment Module.

---

## 3. Financial Commitment Points

For a Cross-Border Payment, understanding exactly *when* money moves and *when* the bank is committed is crucial. We must explicitly distinguish the following concepts:
* **Ledger balance**: The authoritative accounting position based on settled, immutable ledger entries.
* **Reserved/held amount**: Funds temporarily held for an outstanding obligation (a pending payment).
* **Available balance**: The amount still available for the customer to spend (Ledger balance minus Reserved amount).
* **Actual settlement**: The final accounting movement of funds on the ledger.

1. **Funds Reserved**: Occurs immediately after validation. The Ledger Aggregate places a hold. The customer's **available balance** is reduced, but the **ledger balance** is unchanged because actual settlement has not yet occurred. *If a failure occurs here (e.g., compliance rejection), the reservation is simply released.*
2. **Financially Committed**: Occurs when the bank selects a route and transitions the Payment to `EXECUTING`. The bank has committed to the correspondent bank that funds will settle. *If a failure occurs after this point, operational intervention is required; the reserved funds cannot be automatically released back to the customer.*
3. **Settlement Occurs**: The actual financial accounting movement. This occurs asynchronously when the final debit is posted to the customer's ledger balance and the correspondent bank debits/credits the Nostro account.
4. **Message Success, Settlement Failure**: If the Payment Message is successfully delivered to the Receiving Bank, but the subsequent Nostro settlement fails, the Payment is technically completed from the customer's perspective. The settlement failure represents an inter-bank dispute handled by Operations, not a failure of the Customer's payment instruction.

---

## 4. Retry Semantics

We explicitly distinguish between different types of "retries":
* **Duplicate User Submission**: Handled via **Idempotency Keys** at the API layer. If a user clicks "Submit" twice, the system returns the state of the first request without creating a new payment.
* **Network / Message Retry**: An internal technical operation. If a network timeout occurs while sending a `pacs.008` message, the Payment Engine generates a *new* Payment Message for the *same* Payment ID and tries again. This is not a business use case; it is a system resilience mechanism.
* **Payment Continuation**: If a payment is placed on `COMPLIANCE_HOLD`, it pauses. When released, it automatically continues. This is state machine progression, not a retry.
* **Operator-Initiated Reprocessing**: If a payment gets stuck in a fatal state due to a system bug, an Operations Analyst might issue a command to reprocess it. This is a privileged operational use case.
