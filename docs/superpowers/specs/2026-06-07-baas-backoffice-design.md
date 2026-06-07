# Phase 1C — `baas-backoffice` (D8) — Build Design Spec

> **Status:** ratified design (brainstormed 2026-06-07). Feeds the implementation plan(s).
> **Scope:** the `baas-backoffice` React app only (deliverable **D8** in the 2026-05-29 Phase-1C spec).
> **Complements, does not replace:** `docs/superpowers/specs/2026-05-29-nubbank-baas-phase1c-backoffice-design.md`
> — that spec locks the Phase-1C architecture (two audiences, operator identity, Hybrid RBAC, the shared
> frontend stack, ports). This spec covers the genuinely-open build decisions for D8 and the design language.

---

## 1. Purpose

Build the partner-operations console at **`app.nubbank.com`** (`baas-backoffice`): the UI through which partner
staff run their tenant's day-to-day banking. The backend enablers it consumes are already built and tested —
operator identity (Keycloak multi-issuer), Hybrid RBAC wired to `@PreAuthorize`, and the engine's `/baas/v1/**`
module APIs (Phase 1A/1A-ext, Session 8). Today there is **no UI**; this is the headline remaining Phase-1C
deliverable.

Out of scope here: `baas-platform-admin` (D9, NubBank-staff console — separate track/spec), `baas-portal` (1D),
and any live integrations (stub-mode backend only, per the 2026-05-29 spec §3).

## 2. Relationship to the locked Phase-1C stack

From the 2026-05-29 spec §7.1 (locked) and reconfirmed here:

| Concern | Choice |
|---|---|
| Framework / build | React 19 + Vite |
| Styling | Tailwind CSS 4 |
| Server state | TanStack Query |
| Routing | React Router 7 |
| Client state | Zustand |
| Forms | React Hook Form + Zod |
| API client | OpenAPI codegen (typed client from each service's OpenAPI doc) |
| Auth | PKCE OIDC via `oidc-client-ts` against the partner Keycloak realm |
| Testing | Vitest + React Testing Library; Playwright (e2e) |
| Port | `baas-backoffice` = **3001** |

## 3. Decisions taken in this brainstorm

| # | Decision | Choice |
|---|---|---|
| B1 | First-increment scope | **Full build** — all ~12 screen domains, full CRUD/commands. The *spec* defines the complete target; the *implementation* is decomposed into a shared-foundation plan + one sub-plan per domain (each independently shippable). A single bite-sized TDD plan cannot hold 12 full-CRUD domains. |
| B2 | Component library (closes **DEF-1C-11**) | **shadcn/ui** — Radix primitives + Tailwind, components copied into the repo (owned code, no version lock). Tables via **TanStack Table** (same family as TanStack Query). |
| B3 | Dev/test auth | **Hybrid `AuthProvider`** — real `oidc-client-ts` PKCE (prod) **and** a dev provider (configurable/mock operator token) selected by env. Local dev + CI + Playwright use the dev provider; prod uses PKCE. The BaaS dev stack has no Keycloak today, so the dev provider is required for a workable loop. |
| B4 | Visual design language | **Adapted from the Nubeero-Jobs Figma** (see §4). Full token set, not shadcn defaults. |
| B5 | Theme | Light theme now; **dark theme deferred** (token structure leaves room for it). |
| B6 | Sidebar active-accent | Brand **blue `#0078D4`** (the Figma used red; blue chosen for cohesion). |

## 4. Design language (locked — adapted from Nubeero)

Source: Figma `Nubeero-Jobs` (file `PsnGRotaBYfV0V13KG9Bak`) — dashboard `1662:27651`, jobs list `1662:29071`,
admin login `1657:20493`, plus the design-system swatch area. Captured: Instrument Sans; blue `#0078D4` +
light-blue `#28A8EA` + near-black `#1A1A1A`; black icon-rail sidebar; white topbar with pill search; light-blue
KPI tiles; rounded cards; pill status badges. One deliberate adaptation: the jobs page's rainbow-pastel cards
are dropped in favor of a blue/neutral family (too playful for a banking console).

### 4.1 Tokens (CSS variables; the source of truth)

| Token | Value | Use |
|---|---|---|
| `--brand-primary` | `#0078D4` | CTAs, links, active nav, primary buttons |
| `--brand-accent` | `#28A8EA` | highlights, gradients, accent figures on dark |
| `--ink` | `#1A1A1A` | sidebar, primary text, dark (ink) buttons |
| `--surface` | `#ffffff` | default card / panel surface |
| `--surface-ink` | `#23262E` | dark "slate ink" card surface (hero/focus/KPI-accent cards) |
| `--bg-app` | `#f5f7fa` | app canvas |
| `--tint-blue` | `#e8f3fc` | light-blue KPI tiles, subtle fills |
| `--muted` | `#6b7280` | secondary text |
| `--border` | `#e6e9ef` | hairlines, card borders, table rows |
| `--success` / `--success-bg` | `#1f9d57` / `#e3f6ec` | positive status pills |
| `--warning` / `--warning-bg` | `#b7791f` / `#fdf3d6` | pending status pills |
| `--danger` / `--danger-bg` | `#d64545` / `#fdeaea` | failed/rejected status pills, destructive |

- **Type:** Instrument Sans (self-hosted; system-ui fallback). Headings 600 weight, tight `-0.01em` tracking.
- **Radius:** card `14px` · control `8px` · pill `999px`.
- **Density:** comfortable (compact tables, tight forms, generous section spacing).
- **Sidebar:** pure `--ink` icon rail; active item marked with a `--brand-primary` left accent.
- Tokens are defined once (Tailwind 4 `@theme` + CSS variables) so a dark theme or rebrand drops in without
  touching components.

## 5. App shell & information architecture (§A)

Black icon-rail sidebar + white topbar (pill search · notifications · user menu). Nav grouped to the engine modules:

- **Overview** — Dashboard
- **Banking** — Customers · Accounts · Deposits · Loans · Payments · Teller/Cash · Charges
- **Finance** — Accounting · Reports · Compliance
- **Admin** — Offices/Staff · Roles (operator role management) · Audit (own tenant)

One operator session = one partner realm = one tenant (no cross-tenant view). **Every nav item and route is
RBAC-gated** — rendered/reachable only if the operator's authorities (JWT/role join) permit it; unauthorized
deep-links resolve to a "not permitted" state, never a blank screen.

## 6. Screen pattern (§B — one repeatable shape)

Every domain follows the same three-part shape, built once on the foundation and copied per domain:

1. **List view** — filter/search bar + paginated table (TanStack Table) + primary "+ New" CTA.
2. **Detail view** — header card + tabbed sections (overview / related lists / history).
3. **Command modals** — create / edit / lifecycle actions (React Hook Form + Zod), gated by RBAC.

Mutation lifecycle: submit → pending/optimistic → TanStack Query invalidation → toast → (engine writes audit).
This uniformity is what makes the full 12-domain build tractable.

## 7. Auth & data flow (§C)

```
PKCE (prod) / dev provider (local·CI·e2e)
        → AuthProvider (token + refresh)
        → Bearer on every request
        → typed client (OpenAPI codegen from baas-engine /v3/api-docs)
        → TanStack Query (cache + invalidation)
        → render (RBAC-gated)
```

- **`AuthProvider`** is the single auth seam: `getToken()` / `login()` / `logout()` / `authorities`. Prod impl =
  `oidc-client-ts` PKCE against the partner realm; dev impl = configured/mock operator token. Env-selected.
- **OpenAPI codegen**: baas-engine ships springdoc-openapi 2.8.6 → typed client generated from `/v3/api-docs`,
  regenerated as a build step. No hand-written request types.
- **Response envelope**: the engine's `{ data, meta, errors }` is unwrapped centrally; `meta` drives pagination.
- **Errors**: `401` → re-authenticate; `403` → "not permitted" (RBAC) surface; field-level errors → form;
  transport/5xx → error boundary + toast. One API base, one error boundary, one toast system.

## 8. Testing (§D)

- **Vitest + React Testing Library** — components, hooks, route guards, the envelope/error unwrap, RBAC gating.
- **Playwright e2e** — full flows against the **dev-auth provider** (no Keycloak needed in CI): login → list →
  detail → create/edit → see result. Density targets per the 2026-05-29 spec §11.

## 9. Project structure & implementation decomposition (§E)

`baas-backoffice/` (Vite, port 3001):

```
baas-backoffice/
├── src/
│   ├── app/            # router, providers (Query, Auth, Theme), error boundary
│   ├── auth/           # AuthProvider + pkce/dev implementations
│   ├── api/            # generated OpenAPI client + envelope/query helpers
│   ├── components/     # shadcn/ui + shared primitives (DataTable, PageHeader, StatusBadge, CommandModal, FormField)
│   ├── layout/         # Sidebar, Topbar, AppShell, nav config (RBAC-gated)
│   ├── lib/            # tokens, utils, rbac helpers
│   └── features/       # one folder per domain: customers/ accounts/ deposits/ loans/ ...
└── (Vite/Tailwind/tsconfig/test config)
```

**Decomposition** (each row → its own implementation plan, reviewed by PR):

1. **Foundation plan** — scaffold (Vite + Tailwind 4 + tokens + shadcn), `AuthProvider` (pkce + dev), OpenAPI
   codegen + envelope/query helpers, app shell (Sidebar/Topbar/RBAC nav), reusable primitives
   (DataTable, PageHeader, StatusBadge, CommandModal, FormField), error boundary + toasts, and the **Dashboard**
   as the first real screen. This is the foundation every domain consumes.
2. **Per-domain sub-plans** — Customers, Accounts, Deposits, Loans, Payments, Teller/Cash, Charges, Accounting,
   Reports, Compliance, Offices/Staff, Roles, Audit. Each: list + detail + commands, following §6, on the
   foundation. Independently shippable.

## 10. Non-goals / deferred

- `baas-platform-admin` (D9), `baas-portal` (1D) — separate specs.
- Live integrations (BVN/NIN live, NIP, scheme cert) — stub-mode backend only.
- Dark theme (B5) — token structure ready; not built this phase.
- Rainbow-pastel marketplace cards — intentionally not adopted (B4).
- Maker-checker UI beyond what the engine supports (2026-05-29 spec §13).

## 11. References

- `docs/superpowers/specs/2026-05-29-nubbank-baas-phase1c-backoffice-design.md` — Phase-1C architecture (D1–D10).
- Nubeero-Jobs Figma `PsnGRotaBYfV0V13KG9Bak` — design-language source (nodes `1662:27651`, `1662:29071`, `1657:20493`).
- `CLAUDE.md` § Operator Identity & RBAC (Session 8) — the backend enablers D8 consumes.
- `docs/deferred-items.md` — DEF-1C-11 (component library) resolved here = shadcn/ui.
