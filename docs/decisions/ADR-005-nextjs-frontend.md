# ADR-005: Next.js Frontend Stack

## Status
**Accepted**

## Date
2026-09-22

## Context
CrossPayNet requires a frontend dashboard to visualize the simulation, allowing users to initiate payments, view ledger balances, inspect compliance checks, and monitor system health. The frontend stack needs to be productive, robust, and aligned with modern web development standards.

## Problem
What technology stack should be used to build the frontend web application?

## Decision
We will implement the frontend using **Next.js 16 (React Framework)** with **TypeScript**. For styling, we will use **Tailwind CSS**, and for UI components, we will use **shadcn/ui**.

*   **Next.js 16**: Provides a robust React framework with built-in routing, server-side rendering (SSR), and API routes if needed.
*   **TypeScript**: Ensures type safety across the frontend application, catching errors early and improving developer experience, particularly when interacting with backend OpenAPI contracts.
*   **Tailwind CSS**: A utility-first CSS framework that allows rapid, responsive styling without writing custom CSS classes.
*   **shadcn/ui**: A collection of re-usable components built on top of Tailwind and Radix UI. It provides accessible, highly customizable primitives (buttons, tables, modals) without locking the project into an inflexible component library.

## Alternatives considered
*   **Angular**: A comprehensive, opinionated frontend framework.
*   **Vue.js**: A progressive, approachable framework.

## Trade-offs
*   **Pros**: The React/Next.js ecosystem is massive, with extensive documentation and community support. Tailwind and shadcn/ui allow for extremely fast UI iteration while maintaining a professional aesthetic.
*   **Cons**: React requires careful management of state and side-effects. The ecosystem changes rapidly, requiring the team to stay updated on best practices (e.g., React Server Components vs. Client Components).

## Consequences
*   The frontend will be developed as a separate application within the repository (or potentially a separate repo depending on final workspace structure), communicating with the Java backend strictly via the REST API.

## Rejected alternatives
*   **Angular / Vue**: While both are excellent frameworks, the React/Next.js ecosystem was selected due to broader familiarity and the specific desire to leverage the highly productive shadcn/ui and Tailwind ecosystem for the dashboard.
