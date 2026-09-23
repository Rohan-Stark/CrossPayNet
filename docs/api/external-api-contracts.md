# External API Contracts

This document defines the conceptual, client-oriented REST API boundaries for CrossPayNet. 

*Note: No formal executable OpenAPI specification (`openapi.yaml`) is generated in this phase, as the data payloads require conceptual validation before strict schema generation. Hypermedia (HATEOAS/HAL) is deferred; clients must rely on out-of-band documentation for valid state transitions.*

## 1. Design Principles

* **Resource Abstraction**: Endpoints expose domain concepts (e.g., `PaymentReference`), not internal database IDs, JPA entities, or ledger line items.
* **Validation Layers**:
  * *API/Input Validation*: Schema-level checks (e.g., required fields, positive numbers, 3-letter currency codes) resulting in `400 Bad Request`.
  * *Domain/Business Validation*: Rule checks (e.g., insufficient funds) resulting in `422 Unprocessable Entity`.
  * *Compliance/Risk Evaluation*: Asynchronous policy checks resulting in state changes (`COMPLIANCE_HOLD`), not immediate HTTP errors.

## 2. Resources and Operations

### 2.1 Payments API

#### `POST /payments` - Initiate Cross-Border Payment
* **Purpose**: Customer intent to transfer funds externally.
* **Authorization**: `ROLE_CUSTOMER`.
* **Idempotency**: *Non-idempotent operation requiring protection.* A unique `Idempotency-Key` header is mandatory. Duplicate requests with the same key return the previously cached response (`201 Created` or identical error) without creating a new payment or duplicate financial effect.
* **Request Payload**:
  * `sourceAccountNumber`: String
  * `beneficiaryName`: String
  * `beneficiaryAccountNumber`: String
  * `beneficiaryBankBic`: String
  * `amount`: Decimal (Positive)
  * `currency`: String (ISO 4217)
  * `paymentReference`: String (Client-provided tracking ID)
* **Response Semantics**: `201 Created`. The resource is created immediately in a `VALIDATING` or `COMPLIANCE_HOLD` state. True settlement happens asynchronously.
* **Response Payload**:
  * `paymentId`: String (Public reference, not DB ID)
  * `status`: String (`VALIDATING`, `COMPLIANCE_HOLD`, etc.)
  * `createdAt`: Timestamp

#### `GET /payments/{paymentId}` - Track Payment
* **Purpose**: Retrieve the current status of a payment.
* **Authorization**: `ROLE_CUSTOMER` (must own the source account) or `ROLE_OPERATIONS`.
* **Idempotency**: *Naturally repeatable.* Safe read operation.
* **Response Semantics**: `200 OK`.
* **Response Payload**: Contains current status, amount, currency, and timestamps.

#### `POST /payments/{paymentId}/cancellations` - Cancel Payment
* **Purpose**: Customer request to cancel an in-flight payment.
* **Authorization**: `ROLE_CUSTOMER`.
* **Idempotency**: *Idempotent mutation.* Explicit rules for cancellation:
  * *First successful cancellation*: Stops the payment, releases the reservation, and returns `200 OK`.
  * *Repeated identical cancellation request* / *Already-cancelled payment*: Safely returns `200 OK` (guaranteeing that repeated requests do not create another financial effect or fail incorrectly).
  * *Already-settled payment*: Returns `422 Unprocessable Entity` (cannot cancel a completed settlement).
  * *Non-cancellable payment states*: Any state past the Financial Commitment Point (e.g., `EXECUTING`) returns `422 Unprocessable Entity`.
* **Response Semantics**: 
  * `200 OK` if successfully halted and funds released (or if already cancelled).
  * `422 Unprocessable Entity` if the payment is already past the Financial Commitment Point (e.g., `EXECUTING` or `SETTLED`).

### 2.2 Compliance API

#### `POST /compliance-reviews/{reviewId}/decisions` - Submit Review Decision
* **Purpose**: Compliance Officer releases or rejects a payment on hold.
* **Authorization**: `ROLE_COMPLIANCE`.
* **Idempotency**: *Idempotent mutation.* Submitting the same decision twice yields `200 OK`. Submitting a conflicting decision yields `409 Conflict`.
* **Request Payload**:
  * `decision`: String (`RELEASE` or `REJECT`)
  * `justification`: String
* **Response Semantics**: `200 OK`. The decision is recorded. (The Payment module responds asynchronously to the domain event).

### 2.3 Administrative API (Simulation Bootstrap)

#### `POST /admin/simulation/accounts/{accountNumber}/funds` - Fund Account
* **Purpose**: Bootstrap a simulated account with funds for testing. *Not a real business use case.*
* **Authorization**: `ROLE_ADMIN`.
* **Idempotency**: *Non-idempotent operation requiring protection.* Requires `Idempotency-Key` to prevent accidental double-funding during test setup.
* **Request Payload**:
  * `amount`: Decimal
  * `currency`: String
* **Response Semantics**: `200 OK`. The synthetic funds are immediately available on the ledger.
