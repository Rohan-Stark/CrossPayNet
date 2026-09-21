# Project Structure Rules

## Directory Ownership
* `apps/`: User-facing applications and dashboards.
* `services/`: Backend services with clearly defined responsibilities.
* `packages/`: Shared libraries/contracts/types that are genuinely reused.
* `infrastructure/`: Docker, databases, Kafka, monitoring, and deployment config.
* `tests/`: Cross-project test suites.
* `docs/`: Architecture, domain knowledge, ADRs, API documentation, etc.
* `scripts/`: Developer and maintenance scripts.
* `.agents/`: AI agent rules and project-specific skills.

## Naming Conventions
* Use clear, descriptive names for directories and files.
* Use `kebab-case` for directories and file names unless language-specific standards dictate otherwise.

## Code Organization
* **Business Logic**: Must reside in dedicated service, domain, or package modules. Do NOT place business logic inside controllers or route handlers.
* **Database Logic**: Must be isolated in data access layers (repositories, DAOs). Do NOT leak database-specific logic or ORM imports into business logic.
* **Shared Code**: Belongs in `packages/` only if it is genuinely reused by multiple services or apps.
* **Tests**: Cross-project tests belong in `tests/`. Service-specific tests belong alongside the service code.

## File/Directory Creation Rules
* **No Arbitrary Folders**: Do NOT create vague folders such as `misc`, `stuff`, `temp`, `new`, `final`, `helpers2`. If code doesn't fit existing structures, discuss and formally approve a new directory.
* **No Premature Services**: Do NOT create directories for speculative services before their architecture is approved.
