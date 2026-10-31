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

### 2.1 Customer Payment Portal API (`/api/v1/portal/payments`)

#### `POST /api/v1/portal/payments` — Initiate Cross-Border Payment
* **Purpose**: Customer intent to transfer funds across borders.
* **Authorization**: `ROLE_CUSTOMER`. The authenticated caller's identity is derived strictly from the Spring Security `Authentication.getName()` principal (UUID string). The caller must own the source debit account in `banking.account`.
* **Idempotency**: Natural conflict-safe idempotency via `(sourceAccountId, paymentReference)`. Duplicate requests with identical instructions return `201 Created` with the existing payment representation without duplicate fund reservations. Conflicting instruction parameters on a reused reference return `409 Conflict`.
* **Request Payload**:
```json
{
  "sourceAccountId": "550e8400-e29b-41d4-a716-446655440000",
  "paymentReference": "PAY-20261031-0001",
  "amount": 500.0000,
  "currency": "USD",
  "beneficiaryName": "British Telecom PLC",
  "beneficiaryAccountNumber": "GB29NWBK60161331926819",
  "beneficiaryBankBic": "NWBKGB2LXXX"
}
```
* **Validation Rules**:
  * `sourceAccountId`: Required valid UUID.
  * `paymentReference`: Required, non-blank, max 64 chars, no ISO control chars.
  * `amount`: Required strictly positive number, max scale 4, up to `999999999999999.9999`.
  * `currency`: Required ISO-4217 code supported by platform (`USD`, `EUR`, `GBP`, `INR`, `JPY`, `CHF`, `SGD`, `AED`).
  * `beneficiaryName`: Required, non-blank, max 140 chars.
  * `beneficiaryAccountNumber`: Required, non-blank, max 34 chars.
  * `beneficiaryBankBic`: Required valid 8 or 11 character ISO 9362 BIC.
* **Response Semantics**: `201 Created` with `Location: /api/v1/portal/payments/{paymentId}` header.
* **Response Payload (`CustomerPaymentResponse`)**:
```json
{
  "paymentId": "73c6838a-3603-4b68-b715-c2db21469e38",
  "sourceAccountId": "550e8400-e29b-41d4-a716-446655440000",
  "paymentReference": "PAY-20261031-0001",
  "amount": 500.0000,
  "currency": "USD",
  "beneficiaryName": "British Telecom PLC",
  "beneficiaryAccountNumber": "****6819",
  "beneficiaryBankBic": "NWBKGB2LXXX",
  "status": "COMPLIANCE_HOLD",
  "statusReasonCode": null,
  "statusReasonDetail": null,
  "createdAt": "2026-10-31T01:45:00Z",
  "updatedAt": "2026-10-31T01:45:00Z"
}
```
* **Privacy & Security Masking**:
  * `beneficiaryAccountNumber` is automatically masked via `DataMasker.maskAccountNumber(...)` (e.g. `****6819`).
  * Internal technical plumbing fields (`reservationToken`, `ledgerTransactionId`, `routeReference`, `externalDispatchReference`) are completely excluded from the public response contract.
* **Status Codes**:
  * `201 Created`: Payment accepted or idempotently replayed.
  * `400 Bad Request`: Schema validation failure or unsupported parameters.
  * `401 Unauthorized`: Unauthenticated request.
  * `403 Forbidden`: Authenticated user lacks `ROLE_CUSTOMER` or does not own the debit account.
  * `409 Conflict`: `paymentReference` already exists for this source account with conflicting instruction details.

---

#### `GET /api/v1/portal/payments/{paymentId}` — Track Payment
* **Purpose**: Retrieve status and summary of a payment belonging to the authenticated customer.
* **Authorization**: `ROLE_CUSTOMER`.
* **Privacy Policy (Anti-Enumeration)**: Inaccessible payments owned by other customers return `404 Not Found` (rather than `403 Forbidden`) to prevent enumeration of valid payment UUIDs.
* **Response Semantics**: `200 OK`.
* **Response Payload (`CustomerPaymentResponse`)**: Same schema as POST response.
* **Status Codes**:
  * `200 OK`: Payment details retrieved successfully.
  * `400 Bad Request`: Malformed `paymentId` UUID format.
  * `401 Unauthorized`: Unauthenticated request.
  * `403 Forbidden`: Authenticated user lacks `ROLE_CUSTOMER`.
  * `404 Not Found`: Payment does not exist or belongs to another customer.

---

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
