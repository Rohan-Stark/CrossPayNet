# Apps Directory

This directory is strictly reserved for user-facing applications and dashboards (e.g., frontends, admin panels, observability dashboards).

**Rule:** Do NOT place backend microservices or shared business logic in this directory.

## Applications

### `apps/web` — CrossPayNet Customer Portal (Next.js 16)
The institutional customer-facing portal for CrossPayNet, built with **Next.js 16 (App Router)**, **React 19**, **TypeScript (strict mode)**, **Tailwind CSS v4**, and **Lucide React**.

#### Key Capabilities
* **In-Memory Authentication (`/login`)**: Validates `ROLE_CUSTOMER` credentials against `GET /api/v1/portal/accounts` and bootstraps CSRF protection via `GET /api/v1/portal/csrf`. Credentials are held strictly in React Context memory and are never written to `localStorage`, `sessionStorage`, `IndexedDB`, URLs, or logs.
* **Account Overview & Dashboard (`/portal/dashboard`)**: Displays customer accounts, available/reserved/total balances, honest summary metrics, and recent payment activity.
* **Payment Initiation (`/portal/payments/new`)**: Form with client-side BIC, currency, and monetary precision validation, explicit review/confirmation modal, and CSRF-protected submission (`POST /api/v1/portal/payments` with `X-XSRF-TOKEN`).
* **Payment History (`/portal/payments`)**: Paginated transaction history (`GET /api/v1/portal/payments?page=0&size=20`) with current-page reference search and lifecycle state filters.
* **Payment Detail & Lifecycle Tracking (`/portal/payments/[paymentId]`)**: End-to-end 9-state workflow timeline (`VALIDATING`, `COMPLIANCE_HOLD`, `ROUTING`, `EXECUTING`, `PENDING_EXTERNAL`, `SETTLED`, `REJECTED`, `CANCELLED`, `FAILED`), masked beneficiary details, manual status refresh, and anti-enumeration `404` handling.

#### Commands
```bash
cd apps/web
npm install
npm run dev        # Start development server at http://localhost:3000
npm run typecheck  # Run TypeScript strict compiler check
npm run lint       # Run ESLint
npm test           # Run Vitest unit & component tests
npm run build      # Create production build
```
