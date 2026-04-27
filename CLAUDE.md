# NubBank BaaS — Body of Knowledge

This file is the single source of truth for Claude when working on the NubBank BaaS platform. Read it fully at the start of every session before generating any code.

NubBank BaaS is a **completely separate product** from NubBank SaaS (`cba-platform`). Do NOT touch, reference, or modify anything in the `CoreBanking/` directory when working on this project.

---

## Full System Architecture

### Service Map

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          NubBank BaaS Platform                                   │
│                    github.com/RazorMVP/nubbank-baas                              │
│                                                                                   │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌────────────────────────┐  │
│  │   Partner Dev Team  │  │  Partner Ops Staff  │  │  NubBank Platform Admin│  │
│  │   (baas-portal/)    │  │  (baas-backoffice/) │  │  (baas-backoffice/     │  │
│  │   React 19 + Vite   │  │  React 19 + Vite    │  │   /platform-admin/*)   │  │
│  │   portal.nubbank.com│  │  app.nubbank.com    │  │  NUBBANK_PLATFORM_ADMIN│  │
│  └──────────┬──────────┘  └──────────┬──────────┘  └────────────┬───────────┘  │
│             └────────────────────────┴─────────────────────────-┘               │
│                                      │ HTTPS                                     │
│  ┌───────────────────────────────────▼────────────────────────────────────────┐ │
│  │                        Security & Gateway Layer                             │ │
│  │  PartnerContextFilter → resolves API key / JWT → sets PartnerContext       │ │
│  │  RateLimitFilter → Redis Lua INCR+EXPIRE → X-RateLimit-* headers           │ │
│  │  FAPI 2.0 (Keycloak) → Open Banking consent flows                          │ │
│  └──────────┬──────────────────────┬──────────────────────┬────────────────────┘ │
│             │                      │                      │                     │
│  ┌──────────▼──────┐  ┌───────────▼───────┐  ┌──────────▼────────────────┐   │
│  │  baas-engine    │  │   baas-card        │  │   baas-ncube              │   │
│  │  Port 8080      │  │   Port 8081        │  │   Port 8082               │   │
│  │                 │  │                    │  │                           │   │
│  │ Partner mgmt    │  │ Card issuance      │  │ CBN format adapter        │   │
│  │ Customers       │  │ Authorisation      │  │ Ncube consent registry    │   │
│  │ Accounts        │  │ Fraud engine       │  │ BVN/NIN verification      │   │
│  │ Loans           │  │ Settlement         │  │ NIP payment routing       │   │
│  │ Payments        │  │ Disputes           │  │ CBN OBR registration      │   │
│  │ Open Banking    │  │ Per-tenant rules   │  │ ISO 20022 mapping         │   │
│  │ Virtual accounts│  │                    │  │ CBN regulatory reports    │   │
│  │ KYC delegation  │  │                    │  │                           │   │
│  │ Metering/billing│  │                    │  │                           │   │
│  │ Sandbox engine  │  │                    │  │                           │   │
│  └──────────┬──────┘  └───────────┬────────┘  └──────────┬────────────────┘   │
│             └──────────────────────┴──────────────────────┘                    │
│                                      │                                          │
│  ┌───────────────────────────────────▼───────────────────────────────────────┐ │
│  │                              Data Layer                                    │ │
│  │                                                                            │ │
│  │  PostgreSQL 16          Redis              Keycloak 26                     │ │
│  │  ─────────────          ─────              ──────────────                  │ │
│  │  public schema          Rate limiting      BaaS realm                      │ │
│  │  + partner_abc123       Session cache      Per-partner client apps         │ │
│  │  + partner_xyz456       BIN cache          FAPI 2.0 flows                  │ │
│  │  + sandbox_abc123                          Model C: dedicated realm        │ │
│  │  (schema-per-partner)                                                      │ │
│  └───────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘

External Integrations:
  NIBSS Ncube ←→ baas-ncube  (consent registry, BVN/NIN, NIP payments)
  CBN OBR     ←→ baas-ncube  (Open Banking Registry participant management)
  Card Schemes ←→ baas-card  (Visa/Mastercard/Verve/Afrigo — ISO 8583 via FEP)
```

### Multi-Tenancy Architecture

```
HTTP Request: Authorization: ApiKey cba_baas_xxx OR Bearer {jwt}
    │
    ▼
PartnerContextFilter (OncePerRequestFilter)
    │  ├─ ApiKey → SHA-256 hash → lookup public.partner_api_keys
    │  └─ JWT → HMAC-SHA256 verify → extract claims
    │
    ▼
PartnerContext (ThreadLocal)
    fields: partnerId, schemaName, tier, environment, authMode
    │
    ▼
PartnerTenantResolver (CurrentTenantIdentifierResolver<String>)
    returns: schemaName  OR  "public"  (when no context)
    │
    ▼
PartnerSchemaProvider (MultiTenantConnectionProvider<String>)
    executes: SET search_path TO partner_abc123, public
    │
    ▼
JPA queries execute in partner_abc123 schema automatically
No WHERE partner_id = ? anywhere in application code
    │
    ▼
finally { PartnerContext.clear() }   ← prevents ThreadLocal leaks

┌─────────────────────────────────────────────────────────────┐
│  PostgreSQL Schema Structure                                 │
│                                                             │
│  public/                  partner_abc123/    sandbox_abc123/ │
│  ├─ partner_organizations  ├─ customers       ├─ customers   │
│  ├─ partner_users          ├─ accounts        ├─ accounts    │
│  ├─ partner_api_keys       ├─ transactions    ├─ transactions│
│  ├─ virtual_account_pool   ├─ payments        ├─ payments    │
│  ├─ schema_provision_log   ├─ loans           ├─ loans       │
│  ├─ billing_events         ├─ exchange_rates  └─ ...        │
│  ├─ idempotency_keys        ├─ loan_products               │
│  ├─ partner_webhooks        ├─ deposit_products            │
│  └─ webhook_deliveries      └─ audit_log                   │
└─────────────────────────────────────────────────────────────┘
```

### Partner Provisioning Flow

```
POST /baas/v1/auth/register
    │
    ▼
1. Insert public.partner_organizations (schemaName = partner_{32hex})
2. Insert public.partner_users (PARTNER_ADMIN role, BCrypt password)
3. provisionAsync(orgId, schemaName) ──────────────────────────────────┐
4. Issue Partner JWT (HMAC-SHA256, 24h)                                 │
5. Return 201 { token, partnerId, schemaName, tier: SANDBOX }           │
                                                                         │
    [Async in background] ←──────────────────────────────────────────────┘
    A. CREATE SCHEMA partner_{32hex}
    B. CREATE SCHEMA sandbox_{32hex}
    C. Flyway.migrate(schema = partner_{32hex}, location = db/migration/tenant)
    D. Flyway.migrate(schema = sandbox_{32hex}, location = db/migration/tenant)
    E. INSERT public.schema_provision_log (status = SUCCESS)
    F. Issue first sandbox API key
```

### Request Lifecycle — Full Flow

```
Partner App sends:
  POST /baas/v1/accounts
  Authorization: ApiKey cba_baas_base64key
  Idempotency-Key: uuid-v4
  { "customerId": "...", "accountTypeLabel": "Savings" }

  ┌──────────────┐
  │PartnerContext │ SHA-256(rawKey) → partner_api_keys lookup
  │Filter        │ → sets PartnerContext{schema="partner_abc",tier="PRO"}
  └──────┬───────┘
         │
  ┌──────▼───────┐
  │RateLimit     │ Redis INCR rl:baas:partner_abc → 47/500 RPM
  │Filter        │ → adds X-RateLimit-Limit: 500 header
  └──────┬───────┘
         │
  ┌──────▼───────┐
  │AccountService│ requireContext() ✅
  │.open()       │ VirtualAccountService.assignNext("partner_abc") ← PESSIMISTIC_WRITE
  │              │ → assigns NUBAN 0581000042 from virtual_account_pool
  │              │ → SET search_path TO partner_abc, public (auto via Hibernate)
  │              │ → INSERT INTO accounts ... (runs in partner_abc schema)
  └──────┬───────┘
         │
  ┌──────▼───────┐
  │BillingEvent  │ INSERT public.billing_events(partner_abc, /baas/v1/accounts, POST)
  └──────┬───────┘
         │
  201 Created { data: { id, accountNumber: "0581000042", balance: 0 }, meta, errors }
```

### CBN/Ncube Integration Flow (Phase 2)

```
                    Partner App
                        │
                POST /baas/v1/ncube/identity/verify-bvn
                        │
                    baas-ncube
                        │
              ┌─────────▼──────────┐
              │  NIBSS Ncube API   │
              │  BVN Verification  │ ←── Nigeria national identity rails
              └─────────┬──────────┘
                        │
                  BVN verified → update customer.kyc_level = STANDARD
                        │
                    Account can now be opened

Consent Flow (FAPI 2.0 + Ncube):
  Partner App → POST /baas/v1/open-banking/consents
              → Customer authorises via Keycloak PKCE
              → PUT /baas/v1/open-banking/consents/{id}/authorise
              → baas-ncube pushes consent to CBN Ncube consent registry ←── [Phase 2]
              → AISP/PISP endpoints now available with consent token
```

### Three Commercial Models

```
Model A — Fintech/Neobank          Model B — Embedded Finance        Model C — Licensed Bank
────────────────────────────       ─────────────────────────────     ──────────────────────────
Credpal, Carbon, FairMoney         Logistics cos, Marketplaces       MFBs, Cooperative banks
       │                                     │                               │
Partner API keys + Portal          Partner API keys + Portal          Full backoffice + APIs
       │                                     │                               │
Schema isolation                   Schema isolation                   Database isolation
(partner_abc schema)               (partner_xyz schema)               (dedicated PostgreSQL)
       │                                     │                               │
Under NubBank licence              Lighter compliance                 Full regulatory autonomy
       │                                     │                               │
KYC delegated or partner-owned     Virtual accounts primary           Own products + rates
BVN/NIN via Ncube mandatory        NIP disbursements primary          Own Keycloak realm
Ncube OBR registration required    Ncube optional                     White-label throughout
Revenue: per-API + per-account     Revenue: per-transaction           Revenue: monthly licence
```

---

## ⛔ SESSION COMPLETION GATE — READ BEFORE SAYING "DONE"

**You MUST NOT close a session, summarise completion, or push to GitHub until every item below is checked. This is a hard stop, not a suggestion.**

### Mandatory End-of-Session Checklist

Run through this list in order. Do not skip any item, even for tiny changes.

- [ ] **1. Build verification** — Run `cd ~/nubbank-baas/baas-engine && ./mvnw test -q` before any commit. All tests must pass. A failing build blocks the push — fix it now, not later. Only sessions that touched zero Java files may skip the test run.

- [ ] **2. `baas-log.md`** — New session entry added at the **top** of the Change History section. Must include:
  - Session number and date
  - One-line summary with final commit SHA
  - New/Updated Files table
  - Key Decisions (bullet list — architectural choices, gotchas discovered)
  - Build Verification block (test count, BUILD SUCCESS)
  - Confirmed Platform Versions block (see template in `/baas` skill)
  - Run `git log --oneline -1 -- baas-engine/` to get the correct SHA

- [ ] **3. `CLAUDE.md`** — Updated:
  - Confirmed Platform Versions table (SHA must match last commit)
  - Module Catalogue — new modules marked ✅, pending modules updated
  - Any new gotchas added to the Known Gotchas table
  - Architecture diagrams section if service boundaries changed

- [ ] **4. API docs** — If ANY `baas-engine` controller file was touched this session:
  - Run: `git diff HEAD~1 HEAD --name-only | grep -E '\.java$'` to list changed Java files
  - For each changed file, grep for `@GetMapping|@PostMapping|@PutMapping|@DeleteMapping|@PatchMapping`
  - For every new or changed endpoint: update `docs/api-reference.html` (to be created in Session 2+)
  - Only sessions that touched **zero** controller files may skip this step — no exceptions

- [ ] **5. CBN compliance gap analysis** — If ANY of the following changed this session:
  - A new API endpoint related to Open Banking, consent, KYC, or payments
  - A new field on Customer, Account, or PartnerOrganization
  - A new integration (Ncube, OBR, NIP, mTLS)
  - Update `docs/regulatory/CBN-Open-Banking-Compliance-Gap-Analysis.md`:
    - Move items from ❌ to ⚠️ or ✅ as appropriate
    - Add any newly discovered gaps
  - Sessions that touched zero Open Banking / compliance files may skip

- [ ] **6. Figma diagrams** — If the service architecture or data flows changed this session, flag which of the 4 FigJam boards needs updating:
  - [Service Architecture](https://www.figma.com/board/PRACgc6BXsGVEL7ZhB866A) — new services, data layer changes
  - [Multi-Tenancy Flow](https://www.figma.com/board/TR1AYhx9Pcmd5y5grxMv8v) — schema isolation changes
  - [Partner Provisioning Flow](https://www.figma.com/board/qHD6cSCRTQHPbkmavHtoxw) — onboarding or tier changes
  - [CBN Compliance Roadmap](https://www.figma.com/board/5KpYYAtiukv7G6o3LyjsVr) — compliance status changes
  - Note in `baas-log.md` which boards were regenerated. Regenerate using `generate_diagram` MCP tool.

- [ ] **7. `/baas` skill update** — If a Phase or sub-plan was completed this session:
  - Update `.claude/skills/baas/SKILL.md` — mark phase ✅ in the Phase Build Order table
  - If new critical gotchas were found, add them to the skill's Known Gotchas section

- [ ] **8. Deployment-agnostic check** — If a new service (`baas-card`, `baas-ncube`, `baas-portal`, `baas-backoffice`, `baas-docs`) was added this session, verify before pushing:
  - [ ] `Dockerfile` committed and tested (`docker build` succeeds locally)
  - [ ] `nginx.conf` committed (SPA routing + security headers)
  - [ ] `infrastructure/docker-compose.yml` entry added
  - [ ] CI workflow committed (`.github/workflows/{service}-ci.yml`)
  - [ ] Build uses only standard CLI (`npm run build`, `./mvnw package`) — no Vercel CLI in build step

- [ ] **9. Commit and push**

  ```bash
  git add CLAUDE.md baas-log.md docs/regulatory/
  # If API docs updated:
  git add docs/api-reference.html
  # If skill updated:
  git add .claude/skills/baas/SKILL.md
  git commit -m "docs(baas-log+claude): Session N — <one-line summary>

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
  git push origin main
  ```

  **The pre-push hook at `.claude/hooks/check-versions-before-push.sh` will block the push if `Confirmed Platform Versions` is missing from either `baas-log.md` or `CLAUDE.md`.** If the push is blocked, add the versions table to the current session entry and retry.

---

### Rationalisation Traps — These Are Not Valid Reasons to Skip

| Thought | Why it's wrong |
|---------|---------------|
| "The frontend didn't touch the backend" | `CLAUDE.md` and `baas-log.md` still need updating for every session |
| "I'll do docs next session" | The next session starts cold. Missing docs will be missed again. |
| "It was just a small fix" | Every session gets a log entry, no exceptions |
| "The tests passed locally, no need to run them again" | Run them immediately before committing — local state can drift |
| "Vercel already handles the deploy, Dockerfile is redundant" | Vercel is one target. The Dockerfile is the portability contract. Both must exist. |
| "Figma diagrams are optional" | They are the visual spec shared with stakeholders. Stale diagrams create confusion. |
| "CBN gap analysis was updated last session" | Last session's analysis doesn't cover this session's changes. |
| "The API docs can wait until we have more endpoints" | One missing endpoint breaks partner integrations silently. Document immediately. |

---

## Confirmed Platform Versions (Session 2 — 2026-04-27)

### BaaS Engine (`baas-engine/`)

| Component | Version | Notes |
|-----------|---------|-------|
| **Spring Boot** | 3.5.0 | Parent BOM |
| **Java** | 21 | LTS; records, sealed classes, pattern matching |
| **Hibernate** | 6.x (managed) | SCHEMA multi-tenancy via `MultiTenantConnectionProvider` |
| **Flyway** | 10.x (managed) | `flyway-database-postgresql` required for Spring Boot 3.3+ |
| **Nimbus JOSE+JWT** | 9.37.3 | HMAC-SHA256 Partner JWT |
| **Jasypt** | 3.0.5 | PII field-level encryption (wired, encryption in Phase 2) |
| **Lombok** | 1.18.38 | Annotation processor explicitly declared in `maven-compiler-plugin` |
| **springdoc-openapi** | 2.8.6 | OpenAPI 3.1 |
| **Testcontainers** | 1.20.1 | PostgreSQL 16 in integration tests; static initializer pattern (not `@Container`) for suite-wide reuse |
| **Last git commit** | `c6c5e47` | Session 1 — Phase 1A complete: all 16 tasks, 23 tests passing, smoke test live |

### BaaS Ncube (`baas-ncube/`)

| Component | Version | Notes |
|-----------|---------|-------|
| **Spring Boot** | 3.5.0 | No DB, no Redis, no Flyway — pure adapter |
| **Java** | 21 | Records, sealed classes, pattern matching |
| **NPS spec** | v1.2 | pacs.008.001.12, acmt.023.001.04 |
| **Last git commit** | `97544ce` | Session 2 — Phase 1B complete: 21 tests, smoke test live |

### BaaS Backoffice Portal (`baas-backoffice/`) — NOT YET BUILT

| Component | Version | Notes |
|-----------|---------|-------|
| **React** | 19.x | Planned — Sub-plan 1C |
| **Vite** | 6.x | Planned |
| **Tailwind CSS** | 4.x | Planned |

### BaaS Developer Portal (`baas-portal/`) — NOT YET BUILT

| Component | Version | Notes |
|-----------|---------|-------|
| **React** | 19.x | Planned — Sub-plan 1D |
| **Vite** | 6.x | Planned |

### BaaS Docs (`baas-docs/`) — NOT YET BUILT

| Component | Version | Notes |
|-----------|---------|-------|
| **Docusaurus** | 3.10.0 | Planned — Sub-plan 1E |

---

## Product Overview

**NubBank BaaS** is a Banking as a Service platform. It provides programmable banking rails via REST APIs. Partners (fintechs, enterprises, licensed banks) consume the APIs to build their own financial products.

**This is NOT a modification of NubBank SaaS.** It is a separate product in its own repository.

### Product Models

| Model | Who | Data Isolation | Regulatory |
|-------|-----|---------------|-----------|
| **A — Fintech/Neobank** | Startup fintechs | Schema isolation | Under NubBank licence |
| **B — Embedded Finance** | Enterprises | Schema isolation | Lighter compliance |
| **C — Licensed Bank** | Licensed institutions | Database isolation | Full regulatory autonomy |

### Regulatory Reference Documents

| Document | Location | Purpose |
|----------|---------|---------|
| CBN Open Banking Guidelines (March 2023) | `docs/regulatory/CBN-Open-Banking-Operational-Guidelines-2023.md` | The authoritative CBN regulatory framework. Read before any Open Banking work. |
| CBN Compliance Gap Analysis | `docs/regulatory/CBN-Open-Banking-Compliance-Gap-Analysis.md` | Full table of what's compliant ✅, partial ⚠️, and gaps ❌ with planned phases. Updated each session. |

**Critical CBN Blockers for Nigerian Market Go-Live (Phase 2 targets):**
- OBR Registration + CAC number on PartnerOrganization
- Asymmetric JWT keys (RSA/EC — JWS RFC 7515) replacing HMAC-SHA256
- BVN/NIN live verification via Ncube rails
- Ncube consent registry sync
- Customer: add middle_name_encrypted + state_of_residence fields

### Repository Structure

```
nubbank-baas/                           ← github.com/RazorMVP/nubbank-baas
├── CLAUDE.md                           ← This file
├── baas-log.md                         ← Session change log
├── docs/
│   ├── regulatory/
│   │   ├── CBN-Open-Banking-Operational-Guidelines-2023.md  ← CBN framework
│   │   └── CBN-Open-Banking-Compliance-Gap-Analysis.md      ← Gap analysis
│   └── architecture/                   ← Architecture diagrams (future)
├── baas-engine/                        ← Spring Boot 3.5 / Java 21 (PORT 8080)
├── baas-card/                          ← Card service (PORT 8081) — NOT YET BUILT
├── baas-ncube/                         ← CBN/Ncube adapter (PORT 8082) — NOT YET BUILT
├── baas-portal/                        ← React developer portal (PORT 3000) — NOT YET BUILT
├── baas-backoffice/                    ← React operations backoffice (PORT 3001) — NOT YET BUILT
├── baas-docs/                          ← Docusaurus docs (PORT 3002) — NOT YET BUILT
└── infrastructure/
    ├── docker-compose.yml              ← NOT YET CREATED
    └── k8s/                            ← NOT YET CREATED
```

---

## Multi-Tenancy Architecture (Critical — Read This First)

Every partner gets a dedicated PostgreSQL schema (`partner_{uuid}`). Hibernate 6 SCHEMA multi-tenancy routes all JPA queries to the correct schema automatically.

### How it works

```
HTTP Request
  → Header: "Authorization: ApiKey cba_baas_..." OR "Authorization: Bearer {jwt}"
  → PartnerContextFilter (OncePerRequestFilter)
     → Resolves partner_id, schema_name, tier, environment into PartnerContext (ThreadLocal)
  → PartnerTenantResolver (CurrentTenantIdentifierResolver<String>)
     → Returns schemaName to Hibernate on every connection
  → PartnerSchemaProvider (MultiTenantConnectionProvider<String>)
     → Executes: SET search_path TO {schemaName}, public
  → All JPA queries run in partner schema automatically
  → PartnerContextFilter.finally: PartnerContext.clear() — prevents ThreadLocal leaks
```

### Schema naming convention

| Schema prefix | Used for |
|---|---|
| `partner_{32-char-uuid-no-hyphens}` | Production schema |
| `sandbox_{32-char-uuid-no-hyphens}` | Sandbox schema (always provisioned alongside) |
| `public` | Platform-level tables shared across all partners |

### Public vs Partner schemas

**public schema** (platform infrastructure, shared across all partners):
- `partner_organizations`, `partner_users`, `partner_api_keys`
- `virtual_account_pool`, `schema_provision_log`
- `billing_events`, `idempotency_keys`
- `partner_webhooks`, `webhook_deliveries`

**partner schema** (per-partner, isolated):
- `customers`, `accounts`, `transactions`, `payments`
- `exchange_rates`, `loan_products`, `deposit_products`
- `audit_log`

### Critical rule for public schema entities

All entities in the public schema MUST use `@Table(name = "...", schema = "public")`. Without this, Hibernate routes queries through the `PartnerSchemaProvider` which sets `search_path` to the current partner schema — and the table doesn't exist there.

Affected entities: `PartnerOrganization`, `PartnerUser`, `PartnerApiKey`, `VirtualAccountPool` (when built).

---

## BaaS Engine — Module Catalogue

### Built in Session 1

| Module | Package | Status |
|--------|---------|--------|
| Common (ApiResponse, BaasException, GlobalExceptionHandler) | `common/` | ✅ Built |
| Multi-tenancy (PartnerContext, TenantResolver, SchemaProvider, Config) | `tenant/` | ✅ Built |
| Partner entities + repositories | `partner/` | ✅ Built |
| Partner JWT service (HMAC-SHA256) | `auth/` | ✅ Built |
| Auth controller (register, login) | `auth/` | ✅ Built |
| TenantProvisioningService | `tenant/` | ✅ Built |
| PartnerContextFilter | `tenant/` | ✅ Built |
| SecurityConfig (permit-all + BCrypt) | `config/` | ✅ Built |
| Public schema migration (V1) | `db/migration/public/` | ✅ Built |
| Tenant schema migration (V1) | `db/migration/tenant/` | ✅ Built |

### Completed in Session 1 (Tasks 10–16)

| Module | Package | Status |
|--------|---------|--------|
| VirtualAccountService (NUBAN pool assignment) | `virtualaccount/` | ✅ Built — `PESSIMISTIC_WRITE` lock |
| Customer API | `customer/` | ✅ Built — POST/GET /baas/v1/customers |
| Account API | `account/` | ✅ Built — open, deposit, withdraw, transactions |
| Payment API (internal transfer) | `payment/` | ✅ Built — deadlock-safe UUID ordering + idempotency |
| Sandbox Controller | `sandbox/` | ✅ Built — simulate deposit, schema reset |
| Rate Limiting (Redis) | `config/` | ✅ Built — Lua INCR+EXPIRE, fail-open, X-RateLimit headers |

### Pending (Later sub-plans)

| Module | Sub-plan | Status |
|--------|---------|--------|
| baas-ncube (CBN format + Ncube) | 1B | ⬜ Not started |
| baas-backoffice (React) | 1C | ⬜ Not started |
| baas-portal (React) | 1D | ⬜ Not started |
| Infrastructure (Docker + CI) | 1E | ⬜ Not started |
| KYC delegation + Ncube live | Phase 2 | ⬜ Not started |
| Virtual account pool + loans | Phase 3 | ⬜ Not started |
| DB isolation + Model C | Phase 4 | ⬜ Not started |

---

## API Design

### Base URL
`https://api.nubbank.com/baas/v1/`

### Authentication modes

| Mode | Header | Used for |
|------|--------|---------|
| API Key | `Authorization: ApiKey cba_baas_{base64_32bytes}` | Machine-to-machine |
| Partner JWT | `Authorization: Bearer {hmac_sha256_jwt}` | Portal staff (backoffice + developer portal) |
| FAPI 2.0 | `Authorization: Bearer {keycloak_jwt}` | Open Banking consent flows |

### Standard response envelope

```json
{
  "data": { ... },
  "meta": { "requestId": "uuid", "timestamp": "2026-04-27T10:00:00Z" },
  "errors": null
}
```

### Standard error format

```json
{
  "data": null,
  "meta": { "requestId": "uuid", "timestamp": "..." },
  "errors": [{ "code": "INSUFFICIENT_BALANCE", "message": "...", "field": null, "docsUrl": "https://developers.nubbank.com/docs/error-reference#INSUFFICIENT_BALANCE" }]
}
```

### Idempotency

All POST mutation endpoints accept `Idempotency-Key` header (UUID v4). 24-hour window. Duplicates return cached response without re-processing.

---

## Coding Standards

### Java

- Java 21 features: records for DTOs, sealed interfaces, pattern matching
- No business logic in controllers — controllers call service, return `ResponseEntity<ApiResponse<T>>`
- All monetary amounts: `BigDecimal` (never `double`)
- PII stored encrypted: append `_encrypted` to column name (e.g., `first_name_encrypted`)
- `PartnerContext.get()` null check in every service method before any tenant-scoped query
- Tenant schema entities: NO `@Table(schema=...)` annotation — Hibernate routes them via SchemaProvider
- Public schema entities: MUST have `@Table(schema = "public")` annotation

### General

- `@Modifying` JPQL updates must also have `@Transactional`
- `PartnerContextFilter` must `clear()` in `finally` — never skip
- Schema name validated against `[a-zA-Z0-9_]+` before any SQL execution
- Testcontainers integration tests: use `api.version=1.41` system property (Docker Desktop 4.x+ requirement)

---

## Known Gotchas

| Issue | Fix |
|-------|-----|
| `@Modifying` without `@Transactional` | Add `@org.springframework.transaction.annotation.Transactional` to the repository method |
| `@Table(schema)` missing on public entities | Hibernate routes to partner schema — table not found at runtime |
| `PartnerContext.clear()` — use `HOLDER.remove()` not `HOLDER.set(null)` | `set(null)` leaves ThreadLocal entry alive, causing memory leaks in thread pools |
| `Instant` in JdbcTemplate — PostgreSQL JDBC cannot infer type | Use `java.sql.Timestamp.from(instant)` |
| Testcontainers Docker Desktop 4.x API version | Set `api.version=1.41` in Surefire `systemPropertyVariables` |
| `flyway-database-postgresql` missing | Spring Boot 3.3+ extracts PostgreSQL dialect — add this dep or Flyway fails at startup |
| `schema_provision_log.partner_id` FK in tests | Tests must insert a real `PartnerOrganization` row before calling `provision()`, or the FK fails |
| NUBAN check digit SQL — `CAST(expr % 10 AS TEXT)` | PostgreSQL parses `AS TEXT` as alias. Use `((expr % 10))::TEXT` |
| `ddl-auto: validate` breaks with multi-tenant schemas | Hibernate validates against `public` schema — tenant tables don't exist there. Use `ddl-auto: none`; Flyway owns the schema. |
| `@ConditionalOnBean` on a user `@Service` never fires | Spring evaluates user beans before Boot auto-config — condition always false. Use `@Autowired(required = false)` with a null-guard instead. |
| `@Testcontainers` + `@Container` stops container between test classes | Kills the shared HikariPool. Use a static initializer block instead — container starts once for the JVM; Testcontainers registers its own shutdown hook. |
| `partner_api_keys.updated_at` missing from DDL | Hibernate `validate` fails if entity field exists but column doesn't. Keep entity fields and DDL in sync. |

---

## Local Development

```bash
# Start infrastructure
docker run -d --name baas-pg \
  -e POSTGRES_DB=nubbank_baas \
  -e POSTGRES_USER=baas \
  -e POSTGRES_PASSWORD=baas \
  -p 5432:5432 postgres:16-alpine

docker run -d --name baas-redis -p 6379:6379 redis:7-alpine

# Start baas-engine
cd baas-engine
DATASOURCE_URL=jdbc:postgresql://localhost:5432/nubbank_baas \
DATASOURCE_USERNAME=baas \
DATASOURCE_PASSWORD=baas \
./mvnw spring-boot:run

# Health check
curl http://localhost:8080/actuator/health

# Swagger UI
open http://localhost:8080/swagger-ui.html
```

---

## GitHub

**Repo:** https://github.com/RazorMVP/nubbank-baas  
**Username:** RazorMVP  
**Default branch:** main  
**Active branch:** feature/phase1a-engine  

---

## Reference

**PRD:** https://akinwalenubeero.atlassian.net/wiki/spaces/NCBP/pages/349208578  
**Phase 1A Implementation Plan:** `/Users/razormvp/CoreBanking/docs/superpowers/plans/2026-04-27-nubbank-baas-phase1a-engine.md`  
**NubBank SaaS (separate product):** `/Users/razormvp/CoreBanking/` — DO NOT TOUCH
