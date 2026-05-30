# Phase 1C — BaaS Backoffice & Platform Admin — Design Spec

| | |
|---|---|
| **Status** | Draft — awaiting user review (brainstorming step 8) |
| **Date** | 2026-05-29 |
| **Phase** | 1C |
| **Author** | NubBank BaaS session (brainstormed with user, 5 sections approved) |
| **Supersedes** | n/a |
| **Repo HEAD at authoring** | `c4f7f0e` |
| **Execution model** | Pattern B — git worktrees + parallel Claude Code sessions (see companion playbook) |
| **Companion** | `docs/superpowers/playbooks/2026-05-29-phase1c-parallel-execution-playbook.md` |

---

## 1. Purpose

Phase 1C delivers the **human-operator surface** of NubBank BaaS: two React applications and the backend
machinery that backs them. Until now `baas-engine` has exposed only a machine/developer API (`/baas/v1/**`)
authenticated by HMAC-signed partner JWTs and API keys. Phase 1C adds:

1. **`baas-backoffice`** (`app.nubbank.com`) — the operations console used by **partner staff** (tellers,
   customer-service officers, loan officers, compliance, finance, partner admins) to run a tenant's
   day-to-day banking operations.
2. **`baas-platform-admin`** (`admin.nubbank.com`) — the console used by **NubBank staff** to administer
   partners (provisioning, lifecycle, billing posture, oversight). It is **read-only over partner data** and
   **never participates in a partner's day-to-day operations or close-of-business**.
3. **`baas-card`** and **`baas-fep`** backend services — card issuance/management and the ISO 8583 front-end
   processor — coupled into the same multi-tenant data domain as `baas-engine` so that **all of a tenant's
   data lives together** under one isolation boundary.
4. The **backend enablers** these surfaces require: operator identity (Keycloak), authorization (wired RBAC),
   a **custodian read-only oversight** datasource trio, an **admin-read audit** path, and a
   **tenant-initiated data export** workflow.

This spec defines the architecture, component boundaries, interfaces, data flows, error handling, testing
strategy, and the deferred-items registry. It is intentionally an **interface-first** design so the work can be
split across parallel worktrees without the tracks colliding.

---

## 2. Current state (ground truth, verified at `c4f7f0e`)

These are facts read from the code, not assumptions. The design builds on them.

| Fact | Evidence | Consequence for 1C |
|---|---|---|
| Auth is **HMAC self-issued**, not Keycloak | `PartnerJwtService` signs HS256 with `app.jwt.secret`; claims `partner_id`, `schema_name`, `tier`, `environment`, `role`, `email`, `org_name`, `sub` | Operator/admin Keycloak auth is **net-new and additive** — the existing developer/M2M path stays intact |
| `PartnerContext` is a 6-field record | `(partnerId, schemaName, tier, environment, authMode, userId)`, ThreadLocal, cleared in `finally` | Operator JWTs map into this; admin JWTs deliberately carry **no `partnerId`** → separate chain |
| Security is **filter-only**; zero method-level annotations | `grep @PreAuthorize/@Secured/hasRole` → none; `AuthEnforcementFilter` default-denies `/baas/v1/**` | Authorization for operators is wired fresh (Hybrid RBAC, §6.2) |
| RBAC scaffold exists but is **wired to nothing** | `role/` package: `Role`, `Permission`, `UserRole`, `RoleService`, `RoleController` | We activate it rather than build new |
| RBAC tables live in the **tenant schema** | `roles`, `permissions`, `role_permissions`, `user_roles` in `tenant/V2__modules_extension.sql` (no `schema=`) | Each partner owns its own operator roles → "role management proxied" is natural |
| `user_roles.user_id` is a **bare UUID, no FK** | `user_roles.role_id` → `roles(id)` cascade; `user_id` unconstrained | **Deliberate**: it federates to the Keycloak `sub`. Residual risk = orphaned grants on deprovision → handled by a reconciliation process (§6.2.4), **not** a DB FK |
| Operator data model already exists | `office/` package: `Office`, `Staff`; `compliance/` package present | Backoffice screens have entities to bind to; `Teller`/`Cashier`/`TellerSession`/`CashTransaction` to be confirmed/extended |
| `PartnerContextFilter` resolves both `ApiKey ` and `Bearer ` | sets `PartnerContext`, swallows failures (leaves null), always clears | Multi-issuer Keycloak validation slots in as a third resolution branch (Bearer with `iss` ∉ self) |
| 38 controllers, ~196 endpoints across 27 domains | `grep @RestController` → 38 | All already partner-scoped; backoffice consumes them, admin does **not** |
| Public-schema entities | `PartnerOrganization`, `PartnerUser`, `PartnerApiKey`, `VirtualAccountPool` (`@Table(schema="public")`) | Admin oversight reads metadata here + read-only over tenant schemas |
| Cross-cutting security baseline present | `AuthEnforcementFilter`, body-signed HMAC inter-service (`InternalServiceClient`), `StubModeGuard`, `PiiMaskingConverter` | Reuse, don't reinvent; new services adopt the same baseline |

---

## 3. Scope

### In scope (Phase 1C)

- `baas-backoffice` React app — partner operations console (stub-mode backend; no live integrations).
- `baas-platform-admin` React app — NubBank staff console (read-only over partner data).
- `baas-card` backend service — card products, card lifecycle, limits, basic auth-decision stub.
- `baas-fep` backend service — ISO 8583 TCP server, message router, **BIN-based tenant routing**.
- Backend enablers in `baas-engine`:
  - **Operator identity** via Keycloak (per-partner realm + admin realm), multi-issuer JWT validation.
  - **Authorization** — wire the dormant RBAC scaffold (Hybrid model) to method security.
  - **Custodian read-only oversight** — three Postgres roles + three DataSource beans + routing.
  - **Admin-read audit** — Spring AOP interceptor dual-writing to the partner audit log.
  - **Tenant data export** — state machine (`REQUESTED → APPROVED → MATERIALISING → READY → DELIVERED → EXPIRED`).
  - **Operator deprovisioning & grant reconciliation** (the §6.2.4 process).

### Out of scope (deferred — see §13 registry)

- Live integrations of any kind (BVN/NIN live, NIP routing, live card scheme certification) — **stub-mode only**.
- EMV ARQC/ARPC cryptogram validation in `baas-fep` (TDES/SM4) — **deferred to Phase 2**.
- HSM hardware adapter (software stub only in 1C).
- Settlement file export to schemes, interchange, 3DS/ACS, chargeback workflow, tokenization vault — Phase 2+.
- `baas-portal` (developer portal, Phase 1D) — separate spec.
- White-label theming and Model C database-isolation provisioner — Phase 4.
- Maker-checker UI flows beyond what the existing engine supports.

---

## 4. The two audiences — the hard isolation rule

This is the architectural spine of Phase 1C and the constraint everything else serves.

```
┌─────────────────────────────────────────────┐     ┌──────────────────────────────────────┐
│  app.nubbank.com  (baas-backoffice)          │     │  admin.nubbank.com (baas-platform-admin)│
│  PARTNER STAFF                                │     │  NUBBANK STAFF                          │
│  • runs tenant day-to-day banking ops         │     │  • provisions / lifecycles partners     │
│  • full CRUD within own tenant schema         │     │  • READ-ONLY over partner data          │
│  • runs its own close-of-business             │     │  • NEVER runs partner CoB / ops         │
│  • Keycloak realm: baas-partner-{uuid}        │     │  • Keycloak realm: baas-nubbank-admin   │
└───────────────────┬───────────────────────────┘     └──────────────────┬───────────────────┘
                    │ Bearer (iss = partner realm)                         │ Bearer (iss = admin realm)
                    │ JWT carries partner_id                               │ JWT carries NO partner_id
                    ▼                                                       ▼
        ┌──────────────────────────┐                          ┌──────────────────────────────┐
        │ /baas/v1/**              │                          │ /baas-admin/v1/**             │
        │ partner filter chain     │                          │ admin filter chain            │
        │ → baas_app datasource    │                          │ → baas_readonly_admin DS      │
        │ → PartnerContext(tenant)  │                          │ → no tenant write path        │
        │ → Hybrid RBAC @PreAuthorize│                         │ → AdminReadAuditInterceptor   │
        └──────────────────────────┘                          └──────────────────────────────┘
```

**The rule, stated precisely:** NubBank admin **does not interfere with the day-to-day of `app.nubbank.com`**.
Because NubBank custodies the data, admin **can see the data and schema for record purposes** — read-only —
but cannot mutate it. **Even end-of-day (CoB) is run by each partner without NubBank intervention.** Tenant
data can be **made available to the tenant** at the **express request of the tenant** (the export workflow,
§6.5), for reasons the tenant need not justify to NubBank.

> **Reconstructed decision flagged for confirmation (R1):** the URL split is *hybrid* — partner operators
> reuse the existing `/baas/v1/**` surface (they act inside a tenant, so the existing partner-scoped controllers
> already fit), while NubBank admin gets a **new `/baas-admin/v1/**` namespace** with its own filter chain,
> own OpenAPI doc, and the read-only datasource. This matches "two apps, total separation" and Q3 option (b)
> for the admin side. **Confirm this is the intended split before implementation.**

---

## 5. Component map & deliverables

| # | Deliverable | Repo path | Worktree track (see playbook) | Depends on |
|---|---|---|---|---|
| D1 | Operator identity (Keycloak + multi-issuer) | `baas-engine` | Track-Foundation | — |
| D2 | RBAC wiring (Hybrid) | `baas-engine` | Track-Foundation | D1 |
| D3 | Custodian read-only datasource trio + admin namespace | `baas-engine` | Track-Custodian | D1 |
| D4 | Admin-read audit (AOP) | `baas-engine` | Track-Custodian | D3 |
| D5 | Tenant data export workflow | `baas-engine` | Track-Custodian | D3 |
| D6 | `baas-card` service | `baas-card/` | Track-Card | D1 (auth baseline) |
| D7 | `baas-fep` service + BIN routing | `baas-fep/` | Track-FEP | D6 (card lookup) |
| D8 | `baas-backoffice` React app | `baas-backoffice/` | Track-Backoffice | D1, D2 |
| D9 | `baas-platform-admin` React app | `baas-platform-admin/` | Track-PlatformAdmin | D3, D4, D5 |
| D10 | Deferred-items registry | `docs/deferred-items.md` | Track-Foundation | — |

Foundation (D1, D2, D10) is **sequential and lands first** — everything else consumes its interfaces.
Tracks Card+FEP and Backoffice+PlatformAdmin then run in **parallel**. Custodian can overlap late Card/FEP.

---

## 6. Backend design

### 6.1 Operator identity — Keycloak, multi-issuer (D1)

**Decision (locked):** operators and admins authenticate against **Keycloak**, one realm per partner
(`baas-partner-{uuid}`) plus one admin realm (`baas-nubbank-admin`). This is **additive** — the existing
HMAC `PartnerJwtService` path for developer/M2M API consumers is untouched.

**Validation:** introduce a `JwtIssuerAuthenticationManagerResolver` keyed on the JWT `iss` claim.

- `iss` = a partner realm → validate against that realm's JWKS, build a `PartnerContext` with `partnerId`
  + `schemaName` resolved from `public.partner_organizations` (issuer → org lookup, **allowlisted**: only
  `status = ACTIVE` orgs are trusted), `authMode = "OPERATOR_JWT"`, `userId = sub`.
- `iss` = `baas-nubbank-admin` → validate against the admin realm JWKS, build an **admin principal**
  (not a `PartnerContext`) — no tenant routing.
- `iss` ∉ allowlist → 401, no context.
- No `Bearer` / unknown structure → fall through to the existing HMAC + API-key resolution (back-compat).

**JWKS caching:** per-issuer JWKS cache (Spring's `NimbusJwtDecoder` caches by default; set a sane TTL).
Cache invalidation on partner realm rotation is a Phase 2 concern (registry item).

**Where it slots in:** `PartnerContextFilter.resolveJwt(...)` gains a branch: parse unverified header → read
`iss` → if `iss` is a known Keycloak issuer, delegate to the resolver; else keep current HMAC verify. The
filter keeps its `finally { PartnerContext.clear(); }` guarantee.

> **Reconstructed decision flagged (R2):** "PA and one realm per partner" is read as **(a)** separate
> Keycloak realms for the human surfaces — admin realm + per-partner realm — **layered over** the existing
> HMAC path, *not* replacing it. Confirm.

### 6.2 Authorization — Hybrid RBAC (D2)

**Decision (locked):** **Hybrid** model.

- **Coarse gate** at the filter/chain level: partner chain requires a resolved tenant `PartnerContext`
  (already enforced by `AuthEnforcementFilter`); admin chain requires an admin principal.
- **Fine-grained** authorization via the **now-wired** `role/` scaffold, enforced with method security
  (`@EnableMethodSecurity` + `@PreAuthorize("hasAuthority('<permission.code>')")`) on the controller methods
  that operators reach. Authorities are the partner's `permissions.code` values, loaded from the
  tenant-schema `user_roles → role_permissions → permissions` chain for the operator's `sub`.

**JWT claim shape (locked):** **partner** JWTs carry a `partner_id` claim; **admin** JWTs do **not**. This is
the load-bearing distinction that the two filter chains key on.

**Role management — proxied (locked):** partners manage their own operator roles/grants through backoffice
screens that call `baas-engine` (which writes to the partner's own tenant-schema RBAC tables). NubBank admin
does **not** assign partner operator roles. Keycloak holds *authentication*; the engine's RBAC tables hold
*authorization* (permission grants). The operator's `sub` is the join key.

**6.2.4 Operator deprovisioning & grant reconciliation** (the item promoted from the integrity discussion):

- `user_roles.user_id` intentionally has **no FK** — it federates to the Keycloak `sub`, which lives outside
  Postgres. This is correct for the chosen model; a FK to `partner_users` would be wrong (operators ≠ partner
  users).
- **Residual risk:** a deprovisioned Keycloak operator leaves an orphaned `user_roles` grant.
- **Mitigation (in scope):**
  1. **Deprovision hook** — when a partner removes an operator (backoffice action) the engine revokes the
     `user_roles` rows for that `sub` in the same transaction.
  2. **Nightly reconciliation sweep** — a scheduled job lists Keycloak realm users per partner and revokes
     `user_roles` rows whose `sub` no longer exists / is disabled. Logged to the audit trail.
- **Deferred:** DB-level orphan guard / FK-equivalent constraint → registry item (revisit if the sweep proves
  insufficient).

### 6.3 Custodian read-only oversight — datasource trio (D3)

NubBank admin reads partner data without the ability to mutate it. Enforce at the **storage layer**, not just
the framework, so a code bug cannot grant write access.

Three Postgres roles (+ `baas_flyway` for DDL, already implied by migrations):

| Role | Grants | Used by |
|---|---|---|
| `baas_app` | full DML on `partner_*` / `sandbox_*` schemas | partner chain (`/baas/v1/**`), CoB jobs |
| `baas_readonly_admin` | **SELECT-only** on `partner_*` schemas + `public` metadata | admin chain (`/baas-admin/v1/**`) |
| `baas_audit_writer` | **INSERT-only** on `*.audit_log` | the admin-read audit path (§6.4), via `REQUIRES_NEW` |

Three `DataSource` beans (`crudDataSource`, `readOnlyDataSource`, `auditWriterDataSource`) each connecting as
the corresponding role. Routing: the admin filter chain pins the request to `readOnlyDataSource`; the audit
interceptor uses `auditWriterDataSource` in a separate `REQUIRES_NEW` transaction so the audit write survives
even though the admin's main transaction is read-only.

> **Reconstructed detail (R3):** an `AbstractRoutingDataSource` keyed by the active filter chain is the
> mechanism. The admin chain sets the routing key to `READ_ONLY`; partner chain to `CRUD`. Confirm whether you
> want routing-by-chain (simpler) or routing-by-annotation (`@ReadOnlyOversight`, more granular).

### 6.4 Admin-read audit (D4)

Every NubBank-admin **read** of partner data is audited into **that partner's** audit log — partners must be
able to see who looked at their data, when, and what.

- Spring AOP `AdminReadAuditInterceptor` around `/baas-admin/v1/**` data-read controller methods.
- Writes via `baas_audit_writer` / `REQUIRES_NEW` so it persists independent of the read transaction.
- **Admin identity is hashed/redacted (locked):** the partner-visible audit row attributes the access to a
  redacted label (e.g. **"NubBank Compliance Team"** / a stable hashed actor id), **not** the individual
  NubBank employee's identity — preserving NubBank staff privacy while giving the partner real oversight
  visibility. The un-redacted actor is retained only in NubBank's internal admin-side audit.

### 6.5 Tenant data export workflow (D5)

The only path by which partner data leaves the custody boundary — and it is **tenant-initiated**.

State machine:

```
REQUESTED ──approve──▶ APPROVED ──start──▶ MATERIALISING ──done──▶ READY ──download──▶ DELIVERED
    │                                                                   │
    └────────────────────────────── (ttl) ──────────────────────────▶ EXPIRED ◀────── (ttl)
```

- **REQUESTED** — the tenant (partner admin) requests an export of their own data; reason is optional and not
  adjudicated by NubBank.
- **APPROVED** — internal control checkpoint (maker-checker eligible). For Model A/B this may be auto-approved
  by policy; Model C partners self-approve.
- **MATERIALISING** — async job snapshots the tenant schema to an encrypted artifact.
- **READY** — artifact available; signed, time-limited download URL issued.
- **DELIVERED** — downloaded at least once.
- **EXPIRED** — TTL elapsed in `READY` or `DELIVERED`; artifact purged.

Every transition is audited. Artifacts are encrypted at rest and PII-masked per the existing
`PiiMaskingConverter` policy where masking applies. **Stub-mode in 1C:** the materialiser produces a real
encrypted artifact from the tenant schema but external delivery channels (SFTP to partner, etc.) are stubbed
behind `StubModeGuard` with the `X-NubBank-Stubbed` header.

### 6.6 `baas-card` service (D6)

Card domain, **multi-tenant from day 1** (same `PartnerContext` + schema-routing pattern as `baas-engine`).
Phase 1C scope is the lifecycle spine — **not** the full scheme stack.

In scope:
- Card products (per tenant), card issuance (virtual + physical-order stub), card lifecycle state machine,
  per-card limits, PAN stored encrypted, BIN ranges (per tenant) — the lookup table `baas-fep` needs.
- A **card authorization decision stub** endpoint that `baas-fep` calls (balance/limit/status checks against
  the tenant's data); returns an approve/decline with an ISO-8583-mappable response code.
- Reuses the security baseline (`AuthEnforcementFilter`-equivalent, HMAC inter-service auth for the
  engine↔card and fep↔card calls).

Out of scope (registry): tokenization vault, settlement, interchange, disputes/chargeback, 3DS, bureau
personalization, scheme adapters — Phase 2+.

### 6.7 `baas-fep` service + BIN-based tenant routing (D7)

ISO 8583-1987 front-end processor. Phase 1C scope: the TCP server, message packaging, router, and the
**tenant-routing** mechanism — **not** EMV cryptogram validation.

- Netty TCP server (port 8583), jPOS `GenericPackager`, MTI router for `0100/0110`, `0200/0210`,
  `0400/0410`, `0800/0810`.
- **BIN-based tenant routing (locked, "right approach"):** on inbound message, extract DE2 (PAN) → take the
  BIN (6/8 digits) → look up the owning partner via `baas-card`'s BIN-range table → resolve `partner_id` →
  set `PartnerContext` for the downstream auth call.
  - **Caffeine cache**, TTL 5 min, refreshed on partner BIN change.
  - **Unknown BIN → response code `91` (issuer unavailable), and the response echoes no PAN** (no PAN leakage
    on an unrouteable message).
- Auth flow: routed request → `baas-card` authorization-decision stub → map decision to DE39 → build `0110`.
- **Deferred to Phase 2:** EMV ARQC/ARPC (TDES + SM4), HSM hardware adapter, scheme-specific packagers and
  private DEs, settlement/advice messages (`0120/0220/0320...`).

---

## 7. Frontend design

### 7.1 Shared stack (locked)

| Concern | Choice |
|---|---|
| Framework | React 19 |
| Build | Vite |
| Styling | Tailwind CSS 4 |
| Server state | TanStack Query |
| Routing | React Router 7 |
| Client state | Zustand |
| Forms | React Hook Form + Zod |
| API client | **OpenAPI codegen** — typed clients generated from each service's OpenAPI doc |
| Auth | PKCE OIDC via `oidc-client-ts` (against the relevant Keycloak realm) |
| Testing | Vitest + React Testing Library (unit/component), Playwright (e2e) |

> **Component library (deferred to implementation, per Section 4 feedback):** decide between a headless kit
> (e.g. Radix/shadcn-style) vs. a fuller component set at the start of Track-Backoffice. Registry item.

### 7.2 `baas-backoffice` (D8) — `app.nubbank.com`

- Authenticates against the **partner realm** (`baas-partner-{uuid}`), PKCE flow; token carries `partner_id`.
- Consumes `/baas/v1/**` (existing engine endpoints) + card endpoints; screens gated by the operator's
  permissions (authorities from the JWT/RBAC join).
- Screen domains map to the existing engine modules: customers, accounts, deposits, loans, payments, teller
  /cash, office/staff, charges, accounting, reports, compliance, roles (operator role management — the
  "proxied" admin), audit (own tenant).
- Multi-tenant aware only in the sense that one operator session = one partner realm = one tenant. No
  cross-tenant view.

### 7.3 `baas-platform-admin` (D9) — `admin.nubbank.com`

- Authenticates against the **admin realm** (`baas-nubbank-admin`), PKCE; token carries **no** `partner_id`.
- Consumes **only** `/baas-admin/v1/**` (read-only). Never calls `/baas/v1/**`.
- Screen domains: partner registry & lifecycle (provision, suspend, status), partner metadata, **read-only**
  data oversight (schema/record viewing, audited), tenant export request review/approval, billing posture
  (Phase 3 hooks), platform health. **No** operational controls over partner banking, **no** CoB triggers.
- Every data-read screen makes it visible to the operator that the access is audited into the partner's log.

### 7.4 Port assignments (locked)

| Service / app | Port |
|---|---|
| `baas-engine` | 8080 |
| `baas-card` | 8081 |
| `baas-ncube` | 8082 |
| `baas-fep` (HTTP admin) | 8083 |
| `baas-fep` (ISO 8583 TCP) | 8583 |
| `baas-portal` (1D) | 3000 |
| `baas-backoffice` | 3001 |
| `baas-platform-admin` | 3003 |
| `baas-docs` (1D) | 3002 |

> **Reconstructed (R4):** `baas-platform-admin` port `3003` is assigned to avoid clashing with the planned
> `baas-docs` on `3002`. Confirm if you prefer a different number.

---

## 8. Data flow — representative paths

**Partner operator opens a customer record (backoffice):**
```
Browser (PKCE, partner realm) ─Bearer(iss=partner)→ /baas/v1/customers/{id}
  → PartnerContextFilter: iss∈allowlist → resolver → PartnerContext(partnerId, schema, OPERATOR_JWT, sub)
  → AuthEnforcementFilter: context present ✓
  → @PreAuthorize("hasAuthority('CUSTOMER_READ')") ✓ (authorities from tenant RBAC join)
  → CustomerService (baas_app DS, search_path = partner_{uuid})
  → response
```

**NubBank admin views a partner's account list (platform-admin):**
```
Browser (PKCE, admin realm) ─Bearer(iss=admin)→ /baas-admin/v1/partners/{id}/accounts
  → admin chain: admin principal, NO PartnerContext, routing key = READ_ONLY
  → AdminReadAuditInterceptor (around): write audit row to partner_{id}.audit_log
       via baas_audit_writer / REQUIRES_NEW, actor = "NubBank Compliance Team" (redacted)
  → AccountQueryService (baas_readonly_admin DS, SELECT-only)
  → response  (any attempted write → DB permission error, by design)
```

**Card transaction routes through FEP:**
```
Terminal ─ISO8583 0100─→ baas-fep:8583
  → unpack → DE2 PAN → BIN(6/8) → Caffeine cache → baas-card BIN-range lookup → partner_id
       (unknown BIN → 0110 RC=91, no PAN echo)
  → set PartnerContext(partner_id) → baas-card auth-decision stub (HMAC inter-service)
  → map decision → DE39 → pack 0110 → respond
```

---

## 9. Error handling

- **Auth failures** → JSON envelope consistent with `AuthEnforcementFilter` (`{"data":null,"errors":[{...}]}`),
  401 for missing/invalid token, never leaking which realm/issuer failed.
- **Authorization failures** (method security) → 403 with `errors[].code = "FORBIDDEN"`, no permission-name
  enumeration in the message.
- **Admin write attempt** → surfaces as a DB permission error caught and mapped to `500`/`403`
  `OVERSIGHT_READ_ONLY`; this path should be unreachable by design (it means a routing bug) and must be
  alarmed in tests.
- **FEP unrouteable BIN** → `0110` RC `91`, no PAN echo, structured log (PAN masked).
- **Export workflow** → invalid transitions rejected with `EXPORT_INVALID_STATE`; expired artifact download →
  `410 EXPORT_EXPIRED`.
- **Stub-mode** → any stubbed external call returns the `X-NubBank-Stubbed: true` header and a deterministic
  stub payload; never a silent success that looks live.

---

## 10. Multi-tenancy invariants (must hold across all 1C work)

1. Public-schema entities keep `@Table(schema="public")`; tenant entities keep **no** schema annotation.
2. `PartnerContext` is always cleared in a `finally` block in every filter that sets it.
3. The admin chain **never** acquires a tenant `PartnerContext` and **never** uses `baas_app`.
4. No cross-tenant query path exists on the partner chain.
5. New services (`baas-card`, `baas-fep`) provision `partner_{uuid}` + `sandbox_{uuid}` schemas via the same
   `TenantProvisioningService` pattern and run Flyway on both.
6. RBAC tables are tenant-schema; permission grants never leak across tenants.

---

## 11. Testing strategy

### Density targets (locked as "reasonable")

| Layer | Target |
|---|---|
| Backend service logic | unit tests on every service method with branching; mappers/validators covered |
| Backend integration | Testcontainers (real PostgreSQL) for every multi-tenancy / schema / transaction-boundary change |
| Controller contract | MockMvc on new `/baas-admin/v1/**` + card/fep endpoints |
| Frontend unit/component | Vitest + RTL on components with logic, guards, and form schemas |
| Frontend e2e | Playwright on the critical operator + admin flows |

### Multi-tenancy test floor (locked as "sufficient")

Every track that touches data access MUST include integration tests proving:
1. A partner operator cannot read/write another partner's schema.
2. The admin chain cannot write (DB permission denial is asserted, not just app-level).
3. Admin reads produce an audit row in the **correct partner's** audit log with the **redacted** actor.
4. `PartnerContext` does not leak across pooled threads (clear-in-finally verified under concurrency).
5. FEP routes a known BIN to the right tenant and returns RC 91 + no PAN echo for an unknown BIN.
6. Export transitions are legal-only and artifacts expire.

> If additional tests surface during implementation, add them — the floor is a minimum, not a ceiling.

---

## 12. Phasing → worktree tracks

Five stages (locked sequence). Full mechanics in the companion playbook.

| Stage | Tracks (worktrees) | Parallelism |
|---|---|---|
| 1 — Foundation | Track-Foundation (D1, D2, D10) | sequential — lands first |
| 2 — Backend services | Track-Card (D6) ∥ Track-FEP (D7) | parallel (FEP needs Card's BIN lookup interface) |
| 3 — Custodian | Track-Custodian (D3, D4, D5) | overlaps late Stage 2 |
| 4 — Frontends | Track-Backoffice (D8) ∥ Track-PlatformAdmin (D9) | parallel |
| 5 — Integration & hardening | `main` | sequential |

Peak concurrency: **2–3 active worktrees**. Each track is a `feature/phase1c-*` branch reviewed by PR.

---

## 13. Deferred-items registry (day-1 list)

This seeds `docs/deferred-items.md` (D10). Structure: `ID | Item | Why deferred | Earliest phase | Source`.

| ID | Item | Why deferred | Earliest phase |
|---|---|---|---|
| DEF-1C-01 | EMV ARQC/ARPC validation (TDES + SM4) | No live card scheme in 1C; large crypto surface | Phase 2 |
| DEF-1C-02 | HSM hardware adapter (Thales) | Software stub sufficient for stub-mode | Phase 2 |
| DEF-1C-03 | Scheme-specific jPOS packagers + private DEs | No scheme certification in 1C | Phase 2 |
| DEF-1C-04 | Settlement/advice MTIs (`0120/0220/0320/0322/0324`) | No settlement in 1C | Phase 2 |
| DEF-1C-05 | Tokenization vault (DPAN) | No tokenized credentials in 1C | Phase 2 |
| DEF-1C-06 | Interchange, 3DS/ACS, chargeback workflow | Scheme-grade card ops | Phase 2 |
| DEF-1C-07 | Card personalization bureau (CDP) | No physical production in 1C | Phase 2 |
| DEF-1C-08 | DB-level orphaned-grant guard for `user_roles.user_id` | Reconciliation sweep covers it for now (§6.2.4) | revisit if sweep insufficient |
| DEF-1C-09 | Keycloak JWKS rotation/invalidation on realm key roll | Default decoder cache acceptable for 1C | Phase 2 |
| DEF-1C-10 | Live external export delivery (SFTP to partner) | Stub-mode in 1C | Phase 2 |
| DEF-1C-11 | Frontend component-library choice | Decide at Track-Backoffice start | Phase 1C (early) |
| DEF-1C-12 | Routing-by-annotation (`@ReadOnlyOversight`) vs by-chain | Start with by-chain; revisit if granularity needed | Phase 1C/2 |
| DEF-1C-13 | Maker-checker UI beyond engine support | Engine support is the constraint | Phase 3 |
| DEF-1C-14 | `TRADE_FINANCE_OFFICER` role (LCs, guarantees, doc. collections) | Corporate/commercial-banking function; no corporate partner in 1C | activate when a corporate partner onboards |

> "All deferred items must be properly logged and documented for future reference and implementation." — any
> new deferral encountered during implementation is appended here with the same structure.

---

## 14. Role catalogue (30 partner + 8 NubBank admin)

Locked with the user (2026-05-29). Seeded into the realm templates; the default seed populates only the
**12 core ★** partner roles — smaller partners (Model A/B fintechs) use only these; larger banks (Model C)
activate the rest. Each role is a named bundle of the engine's existing `permissions.grouping` sets.

**Checker model — hybrid (locked):** approval authority is a **dedicated role** where audit/regulators
name-check it (`CREDIT_APPROVER`, `FINANCIAL_CONTROLLER`, `KYC_OFFICER`, `COMPLIANCE_OFFICER`), and the
existing `permissions.can_maker_checker` **capability flag** on managerial roles (`BRANCH_MANAGER`,
`OPERATIONS_MANAGER`, `HEAD_TELLER`) for the long tail of routine operational approvals. The `Checker?`
column below marks both kinds; the **Checker via** column says which mechanism applies.

### Partner-realm roles (`baas-partner-{uuid}`) — 30

#### Zone 1 — Administration & Management (5)

| Role | Core | Owns | Checker? | Checker via |
|---|:--:|---|:--:|---|
| `PARTNER_ADMIN` | ★ | Tenant super-admin: operator users, roles, partner config | — | — |
| `BRANCH_MANAGER` | ★ | Branch oversight + high-value transaction approvals | ✓ | flag |
| `OPERATIONS_MANAGER` | ★ | Back-office operations oversight + approvals | ✓ | flag |
| `PRODUCT_MANAGER` | | Product catalogue: loan/deposit/card products, charges, rates | — | — |
| `SYSTEM_CONFIGURATOR` | | Offices, staff, codes, global config, holidays, acct-number formats | — | — |

#### Zone 2 — Customer & Front Office (7)

| Role | Core | Owns | Checker? | Checker via |
|---|:--:|---|:--:|---|
| `CUSTOMER_SERVICE_OFFICER` | ★ | Account opening + full customer profile & KYC **capture** | — | — |
| `RELATIONSHIP_MANAGER` | ★ | Customer relationship/portfolio ownership, cross-sell | — | — |
| `TELLER` | ★ | Cash in/out, deposits, withdrawals, teller session | — | — |
| `HEAD_TELLER` | | Vault custody, teller settlement, cash position | ✓ | flag |
| `CUSTOMER_SUPPORT` | ★ | Support tickets, enquiries (read-mostly customer view) | — | — |
| `ACCOUNT_OFFICER` | | Account maintenance: holds, status, statements, dormancy | — | — |
| `KYC_OFFICER` | | KYC **review/approval**, document & identity verification | ✓ | role |

#### Zone 3 — Lending, Operations, Payments & Cards (10)

| Role | Core | Owns | Checker? | Checker via |
|---|:--:|---|:--:|---|
| `LOAN_OFFICER` | ★ | Loan origination & servicing | — | — |
| `CREDIT_ANALYST` | | Underwriting, credit assessment | — | — |
| `CREDIT_APPROVER` | | Loan/credit approval authority (credit committee) | ✓ | role |
| `COLLECTIONS_OFFICER` | | Arrears, recovery, restructuring, write-off proposals | — | — |
| `LOAN_OPERATIONS_OFFICER` | | Disbursement, repayment posting, loan back-office | — | — |
| `PAYMENTS_OFFICER` | ★ | Transfers, payment processing, standing instructions | — | — |
| `REMITTANCE_OFFICER` | | Inbound/outbound + cross-border / diaspora remittances (NIP, FX) | — | — |
| `TREASURY_OFFICER` | | Liquidity, placements, interbank, FX rates | — | — |
| `CARD_OPERATIONS_OFFICER` | | Card issuance/lifecycle/limits (`baas-card`) | — | — |
| `RECONCILIATION_OFFICER` | | Settlement & GL reconciliation | — | — |

#### Zone 4 — Risk, Compliance, Finance & Audit (8)

| Role | Core | Owns | Checker? | Checker via |
|---|:--:|---|:--:|---|
| `COMPLIANCE_OFFICER` | ★ | Regulatory compliance, sanctions screening | ✓ | role |
| `AML_ANALYST` | | Transaction monitoring, SAR/STR filing | — | — |
| `FRAUD_ANALYST` | | Fraud alerts & case management | — | — |
| `RISK_OFFICER` | | Operational + credit risk policy, provisioning | — | — |
| `FINANCE_OFFICER` | ★ | GL, journal entries, financial operations | — | — |
| `FINANCIAL_CONTROLLER` | | Accounting oversight, GL closures + approvals | ✓ | role |
| `INTERNAL_AUDITOR` | | Read-only audit across all modules | — | — |
| `AUDITOR_READONLY` | ★ | Pure read-only viewer (external auditor / regulator) | — | — |

**Total: 5 + 7 + 10 + 8 = 30.** Core ★ = 12 (`PARTNER_ADMIN`, `BRANCH_MANAGER`, `OPERATIONS_MANAGER`,
`CUSTOMER_SERVICE_OFFICER`, `RELATIONSHIP_MANAGER`, `TELLER`, `CUSTOMER_SUPPORT`, `LOAN_OFFICER`,
`PAYMENTS_OFFICER`, `COMPLIANCE_OFFICER`, `FINANCE_OFFICER`, `AUDITOR_READONLY`).

> **Four-way front-office split (locked):** `CUSTOMER_SERVICE_OFFICER` *captures* (account opening + profile),
> `KYC_OFFICER` *reviews/approves* onboarding, `RELATIONSHIP_MANAGER` *owns the relationship/portfolio*,
> `CUSTOMER_SUPPORT` *handles enquiries* — four distinct roles, never folded together.

> **Deferred:** `TRADE_FINANCE_OFFICER` — corporate/commercial-banking function; activate when a corporate
> partner onboards (registry `DEF-1C-14`). Not seeded in 1C.

These 30 codes become the `PartnerRole` enum, the per-partner Keycloak realm template, and the tenant-schema
RBAC seed. Names are authoritative — Track-Foundation transcribes them verbatim.

### NubBank-admin-realm roles (`baas-nubbank-admin`) — 8

`NUBBANK_PLATFORM_ADMIN`, `NUBBANK_PARTNER_PROVISIONER`, `NUBBANK_COMPLIANCE`, `NUBBANK_FINANCE`,
`NUBBANK_SUPPORT_READONLY`, `NUBBANK_AUDITOR`, `NUBBANK_SRE`, `NUBBANK_SUPER_ADMIN`.
All map to **read-only** data authority over partner schemas; write authority is limited to
**partner-lifecycle metadata** in `public`, never tenant data.

---

## 15. Flagged reconstructions — all confirmed (2026-05-29)

These were reconstructed from the architecture after context compaction and **confirmed by the user at the
review gate**. They are now design facts, not open questions.

| Ref | Item | Confirmed decision |
|---|---|---|
| R1 | URL split | ✅ Hybrid: partner ops reuse `/baas/v1/**`; admin gets new `/baas-admin/v1/**` |
| R2 | Operator identity | ✅ Keycloak realms (admin + per-partner) **layered over** existing HMAC, not replacing |
| R3 | Read-only routing mechanism | ✅ `AbstractRoutingDataSource` keyed by filter chain (by-chain, not by-annotation) |
| R4 | `baas-platform-admin` port | ✅ `3003` (avoids `baas-docs` 3002) |
| R5 | Full role list | ✅ 30 partner + 8 admin roles rebuilt with the user and locked in §14 |

---

## 16. References

- `CLAUDE.md` — architecture, module catalogue, gotchas
- `baas-log.md` — session history
- `docs/phase-gate-reviews.md` — phase-gate review log (1C gate fires when stages complete)
- `docs/regulatory/CBN-Open-Banking-Compliance-Gap-Analysis.md` — regulatory posture
- Existing plans: `docs/superpowers/plans/2026-04-27-*`, `2026-05-04-*`
- Companion: `docs/superpowers/playbooks/2026-05-29-phase1c-parallel-execution-playbook.md`
- Code anchors: `auth/PartnerJwtService`, `tenant/PartnerContext{,Filter}`, `config/SecurityConfig`,
  `config/AuthEnforcementFilter`, `role/*`, `office/*`, `partner/*`, `tenant/V2__modules_extension.sql`
