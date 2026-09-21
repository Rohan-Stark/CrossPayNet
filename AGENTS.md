# CrossPayNet Engineering Constitution for AI Agents

## Project Identity
**CrossPayNet** is an educational simulation inspired by concepts used in international financial messaging and payment systems such as SWIFT. 
**Important Disclaimer:** It is NOT intended to be a real payment network or production financial system. It does not process real money or connect to real financial institutions.

## Architectural Discipline
* Do not change architecture without explicit approval.
* Do not introduce a new architectural pattern without documenting why.
* Prefer simple designs until complexity is justified.
* Do not prematurely create microservices.
* Do not introduce a technology merely because it is popular.

## File-Structure Discipline
* Every file must have a clear owner.
* Do not create duplicate implementations.
* Do not create vague folders such as `misc`, `stuff`, `temp`, `new`, `final`, `helpers2`, etc.
* Do not place business logic inside controllers/routes.
* Do not place database-specific logic throughout unrelated modules.
* Keep responsibilities separated.

## Dependency Discipline
* Do not add a dependency without explaining its purpose.
* Prefer existing project dependencies where appropriate.
* Avoid multiple libraries solving the same problem.

## Configuration and Secrets
* Never hard-code secrets.
* Never commit credentials.
* Use environment configuration.
* `.env` must not be committed.
* `.env.example` must contain placeholders only.

## Database Discipline (Future)
* Schema changes must use migrations.
* Never manually modify production-like schemas without migration history.
* Database access should be isolated from business logic.

## Testing Discipline
Every meaningful feature introduced later must include appropriate tests.
Categories include:
* Unit tests
* Integration tests
* Contract/API tests where appropriate
* Security tests
* Load/performance tests when appropriate

## Documentation Discipline
Architectural decisions must be documented (using ADRs).
Important behavior must be documented.

## AI-Agent Discipline (Extremely Important)
AI agents MUST adhere to the following rules:
1. Inspect existing code before creating new files.
2. Reuse existing abstractions when appropriate.
3. Never create duplicate services/classes/functions simply because an existing implementation is inconvenient.
4. Explain architectural changes.
5. Report all files created, modified, and deleted.
6. Report all dependencies added or removed.
7. Report tests executed and their results.
8. Report deviations from the approved implementation plan.
9. Report unresolved issues.
10. Never silently make major architectural decisions.
11. Never delete working code without explicitly reporting it.
12. Never modify unrelated files just to "clean things up."
13. Keep changes scoped to the requested task.
