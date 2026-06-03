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

## Confirmed Platform Versions (Session 8 — 2026-05-30)

### BaaS Engine (`baas-engine/`)

| Component | Version | Notes |
|-----------|---------|-------|
| **Spring Boot** | 3.5.0 | Parent BOM |
| **Spring Security** | 6.5.0 (managed) | Multi-chain `SecurityFilterChain`; `InternalServiceClient` provides outbound HMAC signing for engine→ncube calls |
| **Spring AOP** | 3.5.0 (managed) | Added Session 4 — used by `AuditAspect` for cross-cutting audit interception |
| **Logback** | 1.5.x (managed) | `logback-spring.xml` with `PiiMaskingConverter` (`%piimsg`) — masks BVN/NIN/NUBAN/PAN in log message bodies (Session 5) |
| **Java** | 21 | LTS; records, sealed classes, pattern matching |
| **Hibernate** | 6.x (managed) | SCHEMA multi-tenancy via `MultiTenantConnectionProvider` |
| **Flyway** | 10.x (managed) | `flyway-database-postgresql` required for Spring Boot 3.3+ |
| **Nimbus JOSE+JWT** | 9.37.3 | HMAC-SHA256 Partner JWT; Keycloak operator JWTs validated via Spring Security `NimbusJwtDecoder` (oauth2-resource-server starter, Session 8) |
| **Spring Security OAuth2 Resource Server** | 6.5.x (managed) | `spring-boot-starter-oauth2-resource-server` — multi-issuer Keycloak operator JWT validation (Session 8) |
| **Jasypt** | 3.0.5 | (legacy dep — replaced by hand-rolled `FieldEncryptor` AES-GCM-256, Session 4) |
| **Lombok** | 1.18.38 | Annotation processor explicitly declared in `maven-compiler-plugin` |
| **springdoc-openapi** | 2.8.6 | OpenAPI 3.1 |
| **Testcontainers** | 1.20.1 | PostgreSQL 16 in integration tests; static initializer pattern (not `@Container`) for suite-wide reuse |
| **Last git commit** | `1010ca9` | Session 8 — Phase 1C Foundation (operator identity + Hybrid RBAC); 111 tests passing |

### BaaS Ncube (`baas-ncube/`)

| Component | Version | Notes |
|-----------|---------|-------|
| **Spring Boot** | 3.5.0 | No DB, no Redis, no Flyway — pure adapter |
| **Spring Security** | 6.5.0 (managed) | `InternalServiceAuthFilter` (HMAC validate inbound) → `AuthEnforcementFilter` (single 401 gate); auto-registration suppressed via `FilterRegistrationBean.setEnabled(false)` (Session 5) |
| **Logback** | 1.5.x (managed) | `logback-spring.xml` with `PiiMaskingConverter` (`%piimsg`) — same converter as engine, different package (Session 5) |
| **Java** | 21 | Records, sealed classes, pattern matching |
| **NPS spec** | v1.2 | pacs.008.001.12, acmt.023.001.04 |
| **CBN vendor media type** | `application/vnd.cbn.openbanking.v1+json` | Required on all controllers; `consumes` method-level on POST/PUT only; `produces` class-level (Session 5) |
| **Last git commit** | `f102ae0` | Session 6 — Phase 1F-E infrastructure hardening; 49 tests passing |

### BaaS FEP (`baas-fep/`)

| Component | Version | Notes |
|-----------|---------|-------|
| **Spring Boot** | 3.5.3 | Hosts config, actuator health (HTTP 8082), HMAC Card client, Netty lifecycle |
| **Java** | 21 | Records, sealed classes, pattern matching |
| **jPOS** | 2.1.10 | `GenericPackager` + `ISOMsg` for ISO 8583-1987 pack/unpack; resolved from the `jpos` Maven repo (`https://jpos.org/maven`) — not on Central |
| **Netty** | 4.1.115.Final | Raw TCP server on port 8583; `LengthFieldBasedFrameDecoder(65535,0,2,0,2)` + `LengthFieldPrepender(2)` (2-byte big-endian length framing) |
| **Caffeine** | 3.1.8 | BIN→partner route cache, `expireAfterWrite` 5 min |
| **Nimbus JOSE+JWT** | 9.37.3 | (transitive via ported HMAC `SigningInterceptor`) |
| **Lombok** | 1.18.38 | Annotation processor explicitly declared in `maven-compiler-plugin` |
| **Architecture** | STATELESS | No DB / JPA / Flyway / Postgres / Redis / datasource — FEP routes and forwards only |
| **Last git commit** | `70b8932` | Session 9 — Phase 1C Track-FEP (D7); 39 tests passing (Card client mocked) |

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
├── baas-fep/                           ← ISO 8583 front-end processor (HTTP 8082 / TCP 8583) — stateless ✅ Session 9
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

### Completed in Session 3 — Phase 1A-ext (Tasks 1–29)

All missing baas-engine modules are now implemented. 74 tests, BUILD SUCCESS, branch `feature/phase1a-ext-engine` pushed.

| Module | Package | Status |
|--------|---------|--------|
| Loan Products + Deposit Products | `product/` | ✅ Built |
| Fixed + Recurring Deposits | `deposit/` | ✅ Built |
| Share Products + Accounts | `share/` | ✅ Built |
| Charges | `charge/` | ✅ Built |
| Loans (full lifecycle + extensions) | `loan/` | ✅ Built |
| GL / Accounting + Rules + Provisioning | `accounting/` | ✅ Built |
| Teller / Cash Management | `teller/` | ✅ Built |
| Office + Staff | `office/` | ✅ Built |
| Groups + Centers | `group/` | ✅ Built |
| System Configuration | `system/` | ✅ Built |
| Floating Rates + Taxes | `rate/` | ✅ Built |
| Roles + Permissions | `role/` | ✅ Built |
| Client Identifiers + Addresses + Images | `clientext/` | ✅ Built |
| Notes + Documents (polymorphic) | `social/` | ✅ Built |
| Maker-Checker + DataTables | `social/` | ✅ Built |
| Open Banking Consents | `openbanking/` | ✅ Built |
| Audit Log Service + AOP aspect | `audit/` | ✅ Built |
| Notifications (Spring async events) | `notification/` | ✅ Built |
| SMS Campaigns + Report Mailing | `campaign/` | ✅ Built |
| Standing Instructions + Beneficiaries | `standing/` | ✅ Built |
| Two-Factor Authentication (HMAC-SHA256) | `twofa/` | ✅ Built |
| Credit Bureau (stub) + PPI Surveys | `bureau/` + `survey/` | ✅ Built |
| Compliance (sanctions screening) | `compliance/` | ✅ Built |
| CoB Scheduler (nightly @Scheduled) | `cob/` | ✅ Built |
| Reports Module (SQL engine) | `report/` | ✅ Built |
| Global Search + Batch API | `search/` + `batch/` | ✅ Built |
| `TenantJdbcTemplate` (multi-tenant raw JDBC) | `common/` | ✅ Built |
| `PartnerContext.userId` (from JWT sub) | `tenant/` | ✅ Built |

### Completed in Session 8 — Phase 1C Foundation

**Operator Identity & RBAC (Phase 1C Foundation, Session 8)** — Keycloak multi-issuer operator JWT validation (`auth/keycloak/*`), Hybrid RBAC wired to `@PreAuthorize` via `AuthorityResolver` + `MethodSecurityConfig`, 30-role tenant catalogue (`tenant/V3`), operator deprovisioning + reconciliation seam. ✅

| Module | Package | Status |
|--------|---------|--------|
| Keycloak multi-issuer JWT decoder (per-issuer JWKS cache) | `auth/keycloak/` | ✅ Built |
| Operator JWT resolver (allowlist + active-status gate + fail-closed) | `auth/keycloak/` | ✅ Built |
| `PartnerContextFilter` multi-branch (`iss` routing: admin/operator/HMAC) | `tenant/` | ✅ Built |
| `AuthorityResolver` (operator→RBAC-scoped; first-party→full tenant) | `auth/` | ✅ Built |
| `MethodSecurityConfig` (`@EnableMethodSecurity`) | `config/` | ✅ Built |
| 30-role tenant catalogue + core-role grants + maker-checker flag | `db/migration/tenant/V3` | ✅ Built |
| `OperatorProvisioningService` (`revokeAllGrants`) | `auth/` | ✅ Built |
| Nightly reconciliation seam (`OperatorGrantReconciliationJob` + stub) | `auth/` | ✅ Built (stub; live impl DEF-1C-17) |
| `keycloak_issuer` column + partial unique index | `db/migration/public/V3` | ✅ Built |
| `AccessDeniedException` → 403 `ACCESS_DENIED` envelope | `common/` | ✅ Built |
| `SecurityConfig` scoped `@Order(2)` + `securityMatcher` | `config/` | ✅ Built |

### Completed in Session 9 — Phase 1C Track-FEP (`baas-fep`, D7)

**ISO 8583 Front-End Processor (stateless spine)** — Netty TCP server (port 8583, 2-byte length framing),
jPOS `GenericPackager`, MTI router, BIN→partner tenant routing via Card's `GET /internal/v1/bins/{bin}`
(Caffeine 5-min cache), and an authorization flow that forwards to Card's `POST /internal/v1/authorize` and
maps the decision to DE39. Built against a **mocked `CardClient`** — live Card wiring is Stage 5. ✅ 39 tests.

| Module | Package | Status |
|--------|---------|--------|
| Netty TCP server + 2-byte length framing | `server/` | ✅ Built |
| `FepMessageHandler` (`@ChannelHandler.Sharable`; decode→route→encode; RC 96 on error) | `server/` | ✅ Built |
| jPOS `GenericPackager` + ISO 8583-1987 field model | `iso/` | ✅ Built |
| MTI router (switch on MTI → handler; unknown MTI → RC 30) | `router/` | ✅ Built |
| `BinResolver` (DE2 PAN → 8-char normalized BIN; Caffeine 5-min) | `routing/` | ✅ Built |
| `HttpCardClient` over ported HMAC `SigningInterceptor` (reads `.data`; fail-closed) | `client/` | ✅ Built |
| Authorization flow (`0100→0110`, `0200→0210`) → Card decision → DE39 | `router/` | ✅ Built |
| Unrouteable BIN → RC `91`, **DE2 omitted** (no PAN echo) | `router/` | ✅ Built |
| Network management (`0800→0810` sign-on / echo, DE70) | `router/` | ✅ Built |
| Reversal (`0400→0410`) — stub approve | `router/` | ✅ Built (real logic DEF-1C-25) |

**MTI inventory (Phase 1C):**

| MTI | Direction | Handler | Notes |
|-----|-----------|---------|-------|
| `0100` → `0110` | Terminal → FEP | `AuthorizationHandler` | Purchase auth: BIN-route → Card authorize → DE39 |
| `0200` → `0210` | Terminal → FEP | `FinancialHandler` | Withdrawal (proc code `01xxxx`); same flow as 0100 |
| `0400` → `0410` | Terminal → FEP | `ReversalHandler` | Stub: echo STAN/DE90, approve `00` (DEF-1C-25) |
| `0800` → `0810` | Terminal ↔ FEP | `NetworkHandler` | Sign-on / echo; DE70 network code, DE39 `00` |
| routed, bad DE4 | — | `AuthorizationHandler` | Missing/non-numeric amount on a routed 0100/0200 → DE39 `30` (no Card call) |
| unknown MTI | — | `MessageRouter` | Format error → DE39 `30` |
| processing exception | — | `MessageRouter.systemError()` | `0810` DE39 `96` (never logs PAN) |

> EMV/HSM/scheme-packagers/settlement/tokenization are correctly **absent** — deferred (DEF-1C-01..07).

### Pending (Later sub-plans)

| Module | Sub-plan | Status |
|--------|---------|--------|
| baas-ncube (CBN format + Ncube) | 1B | ✅ Complete (Session 2) |
| baas-backoffice (React operations portal) | 1C | ⬜ Next — start now |
| baas-portal (React developer portal) | 1D | ⬜ Not started |
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

## Phase 1F-E Gotchas (Infrastructure Hardening — Session 6)

| Issue | Fix |
|-------|-----|
| **NetworkPolicy default-deny pattern uses wrong namespace selector** | Must use `kubernetes.io/metadata.name` (auto-injected by K8s ≥1.21 on every Namespace object) in `namespaceSelector` — NOT a manually applied `name:` label. Manually applied labels can be stripped, renamed, or forgotten. The auto-injected label is immutable by non-admin users. |
| **Base manifests reference a sentinel image tag that will fail to pull** | `infrastructure/k8s/base/` uses `:base-do-not-deploy` as a sentinel. CI must substitute real SHAs via `kustomize edit set image ghcr.io/…/baas-engine=ghcr.io/…/baas-engine:sha-${SHA}` before `kubectl apply`. Forgetting this step causes ImagePullBackOff in every overlay. |
| **GHCR imagePullSecrets — two setup paths** | (1) Create a `docker-registry` Secret named `ghcr-pull-secret` in the cluster namespace and reference it in `serviceAccountName`'s `imagePullSecrets`, OR (2) patch the `default` ServiceAccount's `imagePullSecrets` list. Path (1) is preferred (explicit per-workload). Required PAT scopes: `read:packages`. Full setup documented in `infrastructure/k8s/README.md`. |
| **`/actuator/health` exact match blocks `/readiness` and `/liveness` sub-paths** | `requestMatchers("/actuator/health").permitAll()` is an exact Spring Security path match. With `management.endpoint.health.probes.enabled: true`, Spring Boot exposes `/actuator/health/readiness` and `/actuator/health/liveness` as distinct paths — both return 404 because the exact matcher does not cover sub-paths. Fix: `requestMatchers("/actuator/health", "/actuator/health/**").permitAll()`. Applied to both `baas-engine` and `baas-ncube` `SecurityConfig.java`. |

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
| `@Transactional` on `private` method silently does nothing | Spring AOP proxies don't intercept private methods or `this::method` self-references. Extract to a separate `@Service` bean and inject (e.g. `CobJobExecutor` for CoB jobs). |
| Counter increment doesn't persist when caller throws | Caller's `@Transactional` rolls back the increment too. Move the write to a separate bean with `@Transactional(REQUIRES_NEW)` (e.g. `TwoFactorTokenWriter` for OTP attempts, `AuditLogService` for failure rows). |
| `JdbcTemplate` doesn't see tenant data | Hibernate's `MultiTenantConnectionProvider` only routes Hibernate sessions; raw JDBC bypasses it. Use `TenantJdbcTemplate` (in `common/`) which sets `SET search_path` per query. |
| Schema name in raw SQL is an injection vector | Identifiers can't be parameter-bound. Validate against a strict regex `^(?:partner\|sandbox)_[0-9a-f]{32}$` before interpolation. |
| PostgreSQL JSONB column rejects bound `varchar` | Driver binds Strings as `character varying`. Use `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6 native) — no third-party library. |
| Spring `@EventListener` fires before commit | Default phase is "as soon as published". For side-effects that must skip on rollback (notifications, external API calls), use `@TransactionalEventListener(phase = AFTER_COMMIT)`. |
| `permitAll()` on Spring Security + `requireContext()` per service | Brittle — every new service must remember to check. Replaced by `AuthEnforcementFilter` which rejects `/baas/v1/**` (minus public paths) when `PartnerContext.get() == null`. New endpoints protected by default. |
| Race on read-modify-write counter | `findById → increment → save` from two threads can lose an update. Use a native UPDATE that computes both fields in the SET clause: `failed_attempts = failed_attempts + 1, locked = (failed_attempts + 1 >= :max)`. PostgreSQL evaluates SET against pre-update row → atomic at row level. |
| OTP brute-force with no lockout | Add `failed_attempts` + `locked` columns; combine atomic UPDATE (above) + REQUIRES_NEW writer (above) + constant-time hash compare so timing leaks don't help the attacker either. |
| Lombok `@Builder` initializes collection fields to null | Use `@Builder.Default` on every initialized collection (`= new ArrayList<>()` etc.) — without it the builder ignores the initializer. |
| Customer PII fields named `*_encrypted` but stored plaintext | The `_encrypted` suffix is naming aspiration only unless `@Convert(converter = FieldEncryptor.class)` is on the field. Apply `FieldEncryptor` (AES-GCM-256) to ALL regulated PII: name, email, phone, BVN, NIN, document keys, residential address. |
| `ContentCachingRequestWrapper.getInputStream()` returns empty stream after a filter reads the body | Spring's wrapper caches bytes for `getContentAsByteArray()` but does NOT replay them through `getInputStream()`. Implement an `HttpServletRequestWrapper` that overrides `getInputStream()` to return a fresh `ByteArrayInputStream` each call (`CachedBodyHttpServletRequest` pattern in `baas-ncube/.../config/`). Cap body size at read time (`MAX_BODY_BYTES = 1 MB`) to prevent OOM. |
| Naked `\b\d{13,19}\b` PII regex masks Unix-millisecond timestamps | `\b` matches at every word/non-word boundary; 13-digit ms timestamps and 19-digit Sleuth trace IDs get mangled, breaking observability. Require a context anchor via bounded lookbehind: `(?<=(?:card / pan / primary)[^\\d]{0,16})(\\d{4})...`. Java 9+ supports bounded variable-length lookbehind. BVN/NIN (11 digits) is fine without context — rarely conflicts with timestamps. |
| Stub mode silently active in prod when profile name is `PROD` not `prod` | `String.contains("prod")` is case-sensitive and won't match `prod-eu`/`production` either. Use `Arrays.stream(profiles).anyMatch(p -> p != null && p.toLowerCase(Locale.ROOT).startsWith("prod"))` — case-insensitive prefix match catches every common variant (`PROD`, `Prod`, `prod-eu`, `production`). |
| Filter is in security chain AND auto-registered as a servlet filter | `@Component` filters are auto-registered by Spring Boot servlet auto-config, so they fire in BOTH the servlet pipeline and the security chain — ordering becomes unpredictable. Define `@Bean FilterRegistrationBean<X> disableX(X filter)` returning `setEnabled(false)` to keep the filter out of the servlet pipeline; security chain alone routes it. Add a `SecurityConfigTest` using `FilterChainProxy.getFilterChains()` to assert ordering doesn't regress. |
| Class-level `@RequestMapping(consumes = ...)` rejects partner GETs with 415 | Spring inherits class-level `consumes` to all methods including GET. A partner sending `Accept` only on a GET hits 415. Move `consumes` to method-level on POST/PUT only; keep `produces` at class level for response content negotiation. Pattern used by ncube CBN vendor media type. |
| `HttpMediaTypeNotSupportedException` propagates as 500 | No matching `@ExceptionHandler` in `GlobalExceptionHandler` falls through to the framework's default 500 handler. Add `@ExceptionHandler(HttpMediaTypeNotSupportedException.class)` → 415 and `@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)` → 406. |
| Inter-service call has no auth between trusted services | `permitAll()` for internal endpoints leaves them open to anyone who can reach the service network. Use body-signed HMAC-SHA256: `Authorization: Internal <hex-hmac>` + `X-Internal-Timestamp`; HMAC content `METHOD then PATH then TIMESTAMP then sha256Hex(body)` (pipe-separated); 60s replay window; constant-time hex compare; ≥32-char shared secret enforced at filter construction. See `InternalServiceClient` (engine, signer) + `InternalServiceAuthFilter` (ncube, validator). |
| **Operator vs first-party authority boundary** — `PartnerContextFilter.populateAuthorities()` switches on `PartnerContext.authMode()` | `OPERATOR_JWT` → RBAC-scoped codes from `user_roles`; default (`API_KEY`, `JWT`) → full tenant authority. A new authMode added later falls through to FULL authority by default — evaluate explicitly (DEF-1C-15). |
| **`iss`-branch routing in `PartnerContextFilter`** — admin-issuer → no context (401 on partner API); known partner issuer → `OperatorJwtResolver.resolve` | The early `return` in the operator branch is load-bearing: a known-issuer token that fails crypto must NOT fall through to the HMAC verifier. `iss=null` → legacy HMAC `PartnerJwtService.validate`. |
| **`AccessDeniedException` → 403 envelope via `@ControllerAdvice`** — because the chain uses `anyRequest().permitAll()`, `ExceptionTranslationFilter` never fires | The `@PreAuthorize` denial is resolved by `GlobalExceptionHandler` into the standard `ACCESS_DENIED` envelope. Add an `AccessDeniedHandler` only if the chain ever stops using `permitAll()`. |
| **`app.keycloak.admin-issuer` dormant until set** — unset in dev/prod-without-env → binds to "" → admin-rejection branch never matches | Still secure: admin tokens hit `UNKNOWN_ISSUER`→401. MUST be set before the Custodian admin chain ships. |
| **`live-keycloak` profile requires a `KeycloakUserDirectory` bean** — the stub is `@Profile("!live-keycloak")` | Activating `live-keycloak` without a `@Profile("live-keycloak")` impl fails context startup (`OperatorGrantReconciliationJob` requires the bean). See DEF-1C-17. |
| **`PARTNER_ADMIN` role grant is bounded to V1–V2 permissions** — tenant `V3` CROSS JOINs all permissions at seed time | Any later migration adding permission codes must also grant them to `PARTNER_ADMIN` in that migration (DEF-1C-16). |
| **(FEP) BIN normalization MUST match Card byte-for-byte** — `BinResolver.bin(...)` vs Card `BinService.normalize(...)` | Both take ≤8 leading PAN digits, left-align, zero-pad to 8 (`String.format("%-8s", head).replace(' ', '0')`). If either side diverges, every range-match misses and all transactions route to RC 91. Frozen shared invariant (contract §2). |
| **(FEP) `FepMessageHandler` must be `@ChannelHandler.Sharable`** | Netty enforces this at runtime when one handler instance is added to multiple pipelines (the bean is a singleton). Missing annotation → `IllegalStateException` on the second connection. |
| **(FEP) Never log or echo the PAN** | PAN is masked to `****<last4>` in `Request.toString` and logged only at DEBUG by partnerId/amount/currency. The unrouteable (`91`) response MUST omit DE2 — assert `!response.hasField(2)`. |
| **(FEP) is STATELESS — never set `PartnerContext`** | FEP holds no tenant ThreadLocal and no DB. It passes `schemaName` to Card in the authorize request body; Card sets its own tenant context. Adding any JPA/Flyway/Postgres/Redis dep breaks the architecture. |
| **(FEP) jPOS 2.1.10 is not on Maven Central** | Add the `jpos` repo (`https://jpos.org/maven`) in `pom.xml` `<repositories>`, or `dependency:resolve` fails. Verified resolving in this worktree. |
| **(FEP) Card calls must fail-closed, never throw into the Netty thread** | `HttpCardClient.lookupBin` → `Optional.empty()` on 404/`RestClientException` (treated as unrouteable RC 91); `authorize` → `DECLINE`/`96` on any transport error. The handler catches everything → RC 96 system error as a last resort. |

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
