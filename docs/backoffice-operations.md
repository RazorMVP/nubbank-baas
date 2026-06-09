# baas-backoffice — Operations Reference

Living operations doc for the **NubBank BaaS operations console** (deliverable D8). The canonical
*design* lives in [`docs/superpowers/specs/2026-06-07-baas-backoffice-design.md`](superpowers/specs/2026-06-07-baas-backoffice-design.md);
this file tracks what is **running** — routes, RBAC codes consumed, env vars, auth modes — and is
updated every session that touches `baas-backoffice/` (BaaS skill SESSION COMPLETION GATE item 4).

---

## Overview

| Property | Value |
|----------|-------|
| Purpose | Bank-staff operations console for the BaaS platform (customers, accounts, loans, payments, accounting, compliance) |
| Port (dev) | `3001` (`vite --port 3001`) |
| Backend | `baas-engine` REST at `VITE_API_BASE_URL` (default `http://localhost:8080`) |
| Auth | Keycloak OIDC (PKCE) in prod; dev-token provider for local/CI/e2e |
| Status | **Foundation complete** (Phase 1C). Domain screens land via per-domain sub-plans. |

## Stack

React 19 · Vite 6 · TypeScript 5 · Tailwind CSS 4 (CSS-first `@theme`, no `tailwind.config.js`) ·
shadcn/ui (copied-in Radix + Tailwind primitives under `src/components/ui/`) · TanStack Query 5 ·
TanStack Table 8 · React Router 7 · Zustand 5 · React Hook Form 7 + Zod 3 ·
`openapi-typescript` (types) + `openapi-fetch` (runtime client) · Vitest 3 + RTL · Playwright (e2e).

## Authentication modes

Provider is selected at runtime by env (`src/auth/create-provider.ts`):

| Mode | Trigger | Behaviour |
|------|---------|-----------|
| **Dev** | `VITE_DEV_AUTH=true` | `createDevAuthProvider` — fixed token + authorities from `VITE_DEV_AUTHORITIES`. Used locally, in CI, and by Playwright e2e. `isReady()` is always `true`. |
| **PKCE** | `VITE_DEV_AUTH` unset/false | `createPkceAuthProvider` (`oidc-client-ts` v3) — Authorization Code + PKCE against Keycloak. `isReady()` flips `true` after silent-signin bootstrap resolves; the `/auth/callback` route completes the redirect via `completeRedirectLogin()`. |

Both providers satisfy the `AuthProvider` contract (`src/auth/types.ts`):
`isAuthenticated() · isReady() · getUser() · getAuthorities() · getToken() · login() · completeRedirectLogin() · logout()`.

`RequireAuth` renders a `Loading…` gate while `isReady() === false` so a mid-bootstrap PKCE provider
never triggers a spurious redirect to `/login`.

## Environment variables (`.env.example`)

| Var | Purpose | Default |
|-----|---------|---------|
| `VITE_API_BASE_URL` | baas-engine REST base | `http://localhost:8080` |
| `VITE_OPENAPI_URL` | OpenAPI doc for `npm run gen:api` | `http://localhost:8080/v3/api-docs` |
| `VITE_DEV_AUTH` | `true` → dev provider; else PKCE | `true` (dev) |
| `VITE_DEV_TOKEN` | Bearer token in dev mode | `dev-token` |
| `VITE_DEV_AUTHORITIES` | Comma-separated permission codes granted in dev | full set |
| `VITE_OIDC_AUTHORITY` | Keycloak realm issuer URL (PKCE) | — |
| `VITE_OIDC_CLIENT_ID` | Public OIDC client id | `baas-backoffice` |
| `VITE_OIDC_REDIRECT_URI` | PKCE redirect target | `http://localhost:3001/auth/callback` |

## RBAC — permission codes consumed

Authority codes from the engine's V2 migration (no `ROLE_` prefix; match
`@PreAuthorize("hasAuthority('CODE')")`). Mirrored in `src/lib/rbac.ts` as `PERMISSIONS`; never use
string literals at call sites. `hasPermission(authorities, code)` returns `true` when `code` is
`undefined` (always-visible items).

`READ_CUSTOMER · CREATE_CUSTOMER · UPDATE_CUSTOMER · READ_ACCOUNT · CREATE_ACCOUNT · DEPOSIT ·
WITHDRAW · READ_LOAN · CREATE_LOAN · APPROVE_LOAN · DISBURSE_LOAN · INITIATE_PAYMENT · RUN_REPORT`

## Routes

| Path | Element | Guard | Status |
|------|---------|-------|--------|
| `/login` | `Login` | public | ✅ live |
| `/auth/callback` | `AuthCallback` | public | ✅ live (PKCE redirect completion) |
| `/` (index) | `Dashboard` | `RequireAuth` → `AppShell` | ✅ live |

Domain routes below are **scaffolded in the sidebar nav** (`src/layout/nav-config.ts`) but not yet
wired into the router — each lands with its per-domain sub-plan. The nav filters items by the
operator's authorities via `visibleNav()`.

| Nav group | Item | Route | Required permission |
|-----------|------|-------|---------------------|
| Overview | Dashboard | `/` | — (always visible) |
| Banking | Customers | `/customers` | `READ_CUSTOMER` |
| Banking | Accounts | `/accounts` | `READ_ACCOUNT` |
| Banking | Deposits | `/deposits` | `READ_ACCOUNT` |
| Banking | Loans | `/loans` | `READ_LOAN` |
| Banking | Payments | `/payments` | `INITIATE_PAYMENT` |
| Banking | Teller / Cash | `/teller` | `DEPOSIT` |
| Banking | Charges | `/charges` | `READ_ACCOUNT` |
| Finance | Accounting | `/accounting` | `RUN_REPORT` |
| Finance | Reports | `/reports` | `RUN_REPORT` |
| Finance | Compliance | `/compliance` | `RUN_REPORT` |
| Admin | Offices / Staff | `/offices` | `UPDATE_CUSTOMER` |
| Admin | Roles | `/roles` | `UPDATE_CUSTOMER` |
| Admin | Audit | `/audit` | `RUN_REPORT` |

## API client & error seam

`createApiClient` (`src/api/client.ts`) wraps `openapi-fetch` with an auth middleware that injects
`Authorization: Bearer <token>` from the active provider. Responses use the engine envelope
`{ data, meta{requestId,timestamp}, errors[{code,message,field,docsUrl}] }` with Spring `Page<T>`
inside `data`. Consumers call `unwrapResult<T>(result)` (`src/api/envelope.ts`) which surfaces any
non-2xx status or non-empty `errors[]` as a thrown `ApiError` — never silently destructures `data`.
Mutation errors are toasted globally via the `MutationCache` `onError` in `src/app/providers.tsx`.

## Local development

```bash
cd baas-backoffice
cp .env.example .env            # dev-auth on by default
npm ci
npm run dev                     # http://localhost:3001
npm run gen:api                 # regenerate src/api/schema.d.ts from live OpenAPI
```

## Verification

```bash
npm run typecheck               # tsc -b (composite project)
npm test                        # vitest run
npm run build                   # tsc -b && vite build
npm run test:e2e                # Playwright (webServer self-starts dev server with dev-auth)
```

## CI / deployment

`.github/workflows/baas-backoffice-ci.yml` mirrors `baas-engine-ci.yml`:

- **test** — Node 22, `npm ci`, `typecheck`, `npm test`
- **e2e** — Node 22, `npm ci`, `npx playwright install --with-deps chromium`, `npm run test:e2e`
  (uploads `playwright-report/` on failure)
- **build-and-push** (push to `main` only) — multi-stage Docker (`node:22-alpine` build →
  `nginx:1.27-alpine` serve), published to GHCR with provenance + SBOM
- **security-scan / pr-security-scan** — Trivy (image on push, filesystem on PR) → GitHub Security tab

Kubernetes manifests live in `infrastructure/k8s/base/` (Kustomize). Runtime config is injected by the
cluster (env / ConfigMap on the deploying side), not baked into the image.
