# NubBank BaaS — Build Log

> Tracks all implementation work, decisions, and changes for the NubBank BaaS platform.
> Updated at the end of every session. Newest entries at the top.

---

## Build Status — Current State

| Sub-system | Status | Last Session |
|------------|--------|-------------|
| `baas-engine` — Phase 1A + 1A-ext + 1F-0 baseline + Granular Partner RBAC | ✅ Complete (Phase 1A: 16 tasks; Phase 1A-ext: 29 banking modules + 12 critical security fixes; security baseline added Session 5; **Phase 1C Foundation — operator identity + Hybrid RBAC — Session 8; 111 tests passing**; **Granular Partner RBAC (Spec A, DEF-1C-15) — Session 19; 218 tests, deny-by-default, PARTNER_ADMIN dynamic superuser, scoped API keys**) | Session 19 (`9d51e96`) |
| `baas-ncube` — Phase 1B + 1F-0 baseline | ✅ Complete (9 tasks, **49 tests**, smoke test live; security baseline added Session 5) | Session 2; security baseline Session 5 |
| `baas-card` — Phase 1C Track-Card (D6) + seam hardening | ✅ Complete (card spine: products, issuance + lifecycle, per-card limits, public BIN lookup, internal authorize + reversal; currency scaling, currency-aware limits, idempotency, DE90 reversal; **76 tests**) | Session 11 (`c8c5f28`) |
| `baas-fep` — Phase 1C Track-FEP (D7) + seam hardening | ✅ Complete (stateless ISO 8583 FEP — Netty TCP + jPOS + MTI router + BIN routing + auth flow + DE90 reversal; **51 tests**, live Card wiring Stage 5) | Session 11 (`5a463cf`) |
| `baas-backoffice` — React | 🟡 In progress — **Foundation ✅ (Session 14, `57ffbdd`):** React 19 + Vite 6 app skeleton (api client + envelope seam, hybrid dev/PKCE auth, RBAC gating, app shell, route guards, CI + Docker + k8s). **Session 15 (`281739a`, 75 tests):** dashboard tiles wired to live aggregate + PKCE authorities from `/operators/me` (DEF-1C-28/29). **Customers domain track ✅ (Session 16, `373ebcd`, 101 unit + 1 Playwright e2e):** first per-domain track, on its own PR (`feat/baas-backoffice-customers`) — list/detail/create/edit, masked-PII profile, KYC state-machine actions + history; the engine side ships separately in PR #28. **Accounts domain track ✅ (Session 17, PR #34 `513ff73`, 139 unit + 1 Playwright e2e):** second per-domain track, on its own PR (`feat/baas-backoffice-accounts`) — list/detail/open, lifecycle (freeze/unfreeze/close), money modal (deposit/withdraw), status-history timeline, transaction ledger; the engine Accounts lifecycle + money gating ships separately in PR #33 (`feat/baas-engine-accounts-lifecycle`, 199 tests). Remaining per-domain tracks (Loans, Payments, Teller, Charges, Accounting, Reports, Compliance, Offices/Staff, Roles, Audit) pending. **Session 18:** `UPDATE_ACCOUNT` added to `.env.example` dev authorities (PR #35, merged) + committed Vite dev proxy & `docs/backoffice-local-dev.md` for running against a local engine (PR #36, open); Accounts UI verified end-to-end against a live local engine. | Session 18 |
| `baas-portal` — React | ⬜ Not started — Phase 1D | — |
| `baas-docs` — Docusaurus | ⬜ Not started | — |
| Infrastructure (Docker + K8s + CI) | ✅ Complete — Phase 1E (Dockerfiles + GHCR CI for all four services; `infrastructure/docker-compose.yml`; vanilla k8s manifests in `infrastructure/k8s/`). **Session 13: `baas-card` + `baas-fep` added to k8s base (Deployments, Services, NetworkPolicy mesh, PDBs, fep TCP LoadBalancer, partner→card Ingress routing) + FEP datastore env wired in compose.** | Session 13 |

---

## System Architecture

### Complete Service Map

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                           NubBank BaaS Platform                                   │
│                      github.com/RazorMVP/nubbank-baas                            │
│                                                                                   │
│  ┌──────────────────┐  ┌──────────────────────┐  ┌────────────────────────────┐ │
│  │  baas-portal/    │  │  baas-backoffice/     │  │  baas-backoffice/          │ │
│  │  React 19 + Vite │  │  React 19 + Vite      │  │  /platform-admin/*         │ │
│  │  Developer Portal│  │  Operations Backoffice│  │  NubBank Platform Admin    │ │
│  │  portal.nubbank  │  │  app.nubbank.com       │  │  Role: NUBBANK_PLATFORM_   │ │
│  │  API keys, sandbox│  │  Customers, accounts, │  │  ADMIN only (role-gated)   │ │
│  │  webhooks, billing│  │  loans, payments,     │  │  Partners, schemas,        │ │
│  │  usage analytics  │  │  compliance, reports  │  │  billing oversight,        │ │
│  └────────┬─────────┘  └──────────┬────────────┘  └──────────────┬─────────────┘ │
│           └────────────────────────┴─────────────────────────────┘              │
│                                     │ HTTPS + Partner JWT / API Key / FAPI 2.0  │
│  ┌──────────────────────────────────▼─────────────────────────────────────────┐ │
│  │                        Security & Gateway Layer                              │ │
│  │                                                                              │ │
│  │  PartnerContextFilter (OncePerRequestFilter)                                 │ │
│  │    ApiKey header → SHA-256(key) → lookup public.partner_api_keys             │ │
│  │    Bearer JWT   → HMAC-SHA256 verify → extract {partnerId, schemaName, tier} │ │
│  │    FAPI 2.0     → Keycloak JWT → extract {azp=partnerId}                    │ │
│  │    → sets PartnerContext (ThreadLocal) → clears in finally block             │ │
│  │                                                                              │ │
│  │  RateLimitFilter (@Order 1)                                                  │ │
│  │    Redis Lua INCR+EXPIRE → SANDBOX:30rpm / BASIC:100rpm / PRO:500rpm        │ │
│  │    → X-RateLimit-Limit / X-RateLimit-Remaining / X-RateLimit-Reset headers  │ │
│  │    → fail-open when Redis unavailable (headers show -1)                     │ │
│  └──────────┬──────────────────────┬──────────────────────┬─────────────────────┘ │
│             │                      │                      │                      │
│  ┌──────────▼──────┐  ┌────────────▼───────┐  ┌──────────▼─────────────────┐   │
│  │  baas-engine    │  │  baas-card          │  │  baas-ncube                │   │
│  │  Port 8080      │  │  Port 8081          │  │  Port 8082                 │   │
│  │  Spring Boot 3.5│  │  Spring Boot 3.5    │  │  Spring Boot 3.5           │   │
│  │  Java 21        │  │  Java 21            │  │  Java 21                   │   │
│  │  ─────────────  │  │  ──────────────     │  │  ─────────────────         │   │
│  │  Partner mgmt   │  │  Card issuance      │  │  CBN format adapter        │   │
│  │  Customers      │  │  Authorisation      │  │  Accept: application/vnd.  │   │
│  │  Accounts       │  │  Fraud engine       │  │  cbn.openbanking.v1+json   │   │
│  │  Loans          │  │  Settlement         │  │  Ncube consent registry    │   │
│  │  Payments       │  │  Disputes           │  │  BVN/NIN verification      │   │
│  │  Open Banking   │  │  Per-tenant config  │  │  NIP payment routing       │   │
│  │  Virtual account│  │  ISO 8583 (via FEP) │  │  CBN OBR registration      │   │
│  │  KYC delegation │  │                     │  │  ISO 20022 mapping         │   │
│  │  Metering/billing│  │                    │  │  CBN regulatory reports    │   │
│  │  Sandbox engine │  │                     │  │                            │   │
│  │  Rate limiting  │  │                     │  │                            │   │
│  └──────────┬──────┘  └────────────┬────────┘  └──────────┬─────────────────┘   │
│             └──────────────────────┴──────────────────────┘                     │
│                                      │                                           │
│  ┌───────────────────────────────────▼────────────────────────────────────────┐ │
│  │                              Data Layer                                     │ │
│  │                                                                             │ │
│  │  PostgreSQL 16               Redis 7             Keycloak 26                │ │
│  │  ─────────────               ───────             ───────────────────        │ │
│  │  public schema               Rate limiting       BaaS realm                 │ │
│  │  ├─ partner_organizations    rl:baas:{partnerId} ├─ Per-partner clients     │ │
│  │  ├─ partner_api_keys         Session cache       ├─ FAPI 2.0 flows          │ │
│  │  ├─ virtual_account_pool     BIN cache           └─ Model C: own realm      │ │
│  │  ├─ billing_events                                                          │ │
│  │  └─ schema_provision_log                                                    │ │
│  │                                                                             │ │
│  │  partner_abc123 schema       sandbox_abc123 schema                          │ │
│  │  ├─ customers                ├─ customers (test data)                       │ │
│  │  ├─ accounts                 ├─ accounts                                    │ │
│  │  ├─ transactions             ├─ transactions                                │ │
│  │  ├─ payments                 └─ payments                                    │ │
│  │  ├─ loan_products (own)                                                     │ │
│  │  ├─ deposit_products (own)                                                  │ │
│  │  ├─ exchange_rates (own)                                                    │ │
│  │  └─ audit_log                                                               │ │
│  └────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                   │
│  External Integrations (Phase 2+):                                               │
│    NIBSS Ncube ←→ baas-ncube  (consent registry, BVN/NIN, NIP payments)         │
│    CBN OBR    ←→ baas-ncube  (Open Banking Registry participant management)      │
│    Card Schemes ←→ baas-card (Visa/Mastercard/Verve/Afrigo via ISO 8583 FEP)    │
│    MailHog    ←→ baas-engine (dev email; SMTP in production)                    │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Multi-Tenancy Request Flow

```
Request: POST /baas/v1/accounts  Authorization: ApiKey cba_baas_xxxx

    PartnerContextFilter
         │ SHA-256(rawKey) → lookup public.partner_api_keys
         │ → PartnerContext{partnerId="abc", schema="partner_abc", tier="PRO"}
         ▼
    RateLimitFilter
         │ Redis: INCR rl:baas:abc → 47/500 → allowed
         │ Response headers: X-RateLimit-Limit:500, Remaining:453
         ▼
    AccountController.open()
         │ AccountService.requireContext() → PartnerContext.get() ≠ null ✓
         │ VirtualAccountService.assignNext("partner_abc") ← PESSIMISTIC_WRITE
         │    → UPDATE virtual_account_pool SET assigned=true WHERE id=... [public schema]
         │    → returns "0581000042"
         │ PartnerTenantResolver → returns "partner_abc"
         │ PartnerSchemaProvider → SET search_path TO partner_abc, public
         │ INSERT INTO accounts ... [runs in partner_abc schema automatically]
         │ INSERT INTO public.billing_events ...
         ▼
    201 Created { data: { accountNumber: "0581000042", balance: 0 } }

    finally { PartnerContext.clear() }  ← ThreadLocal cleanup
```

### Partner Onboarding & Provisioning Flow

```
1. SANDBOX REGISTRATION (immediate)
   POST /baas/v1/auth/register
   { orgName, adminEmail, password }
        │
        ├─ INSERT public.partner_organizations
        │    (status=SANDBOX, tier=SANDBOX, schemaName=partner_32hex)
        ├─ INSERT public.partner_users (role=PARTNER_ADMIN, BCrypt password)
        ├─ Issue Partner JWT (HMAC-SHA256, 24h)
        ├─ [Async] CREATE SCHEMA partner_32hex
        ├─ [Async] CREATE SCHEMA sandbox_32hex
        ├─ [Async] Flyway.migrate(tenant V1) on both schemas
        └─ [Async] INSERT public.schema_provision_log (SUCCESS)
        │
        ▼
   201 { token, partnerId, schemaName, tier: "SANDBOX" }
   Partner can call sandbox APIs immediately

2. PRODUCTION UPGRADE (requires NubBank approval)
   POST /baas/v1/org/applications
   { businessType, useCase, estimatedMonthlyCalls }
        │
        ├─ NubBank Platform Admin reviews
        ├─ POST /baas/v1/admin/partners/{id}/approve
        │    → status: SANDBOX → BASIC
        │    → Issue production API key
        │    → Trigger Ncube OBR registration (Phase 2)
        └─ Partner notified via webhook (APPLICATION.APPROVED)

3. MODEL C ENTERPRISE (dedicated isolation)
   On ENTERPRISE tier approval:
   ├─ Provision dedicated PostgreSQL database (not just schema)
   ├─ Provision dedicated Keycloak realm
   ├─ Configure dedicated HikariCP connection pool
   └─ AbstractRoutingDataSource routes by partner to dedicated DB
```

### CBN Open Banking Compliance Status

**Reference:** `docs/regulatory/CBN-Open-Banking-Compliance-Gap-Analysis.md`
**Framework:** CBN Operational Guidelines for Open Banking in Nigeria (March 2023)

| Category | Phase 1A Status | Phase 2 Target |
|----------|----------------|---------------|
| REST/JSON interface | ✅ Complete | — |
| OAuth 2.0 / FAPI 2.0 | ✅ Complete | — |
| Consent lifecycle (basic) | ✅ Complete | Ncube sync |
| BVN/NIN fields | ✅ Fields present | Live verification |
| Rate limiting | ✅ Complete | — |
| CBN OBR Registration | ❌ Gap | Phase 2 blocker |
| CAC number on partner model | ❌ Gap | Phase 2 blocker |
| Asymmetric JWT (JWS RSA/EC) | ❌ Gap | Phase 2 blocker |
| Ncube consent registry sync | ❌ Gap | Phase 2 blocker |
| ISO 20022 data format | ⚠️ Partial | Phase 2 (NIP) |
| 12 CBN KPI metrics | ❌ Gap | Phase 2 |
| mTLS machine auth | ❌ Gap | Phase 3 |
| PII encryption at rest (FieldEncryptor) | ✅ Active | — |
| Annual consent re-validation | ❌ Gap | Phase 3 |

---

## Change History

### Session 20 — 2026-06-25
**Command-first maker-checker / four-eyes framework on `feat/maker-checker` (closes DEF-1C-13). Engine feature commits up to `fd3ddde`; this session's docs commit is the final commit. Engine: 246 tests, 0 failures.**

Added a command-first maker-checker (four-eyes) framework built on the just-merged Granular Partner RBAC (Spec A). New REST surface at `/baas/v1/maker-checker` (approvals inbox, task detail with live dry-run validity, approve/reject/withdraw, config get/put) plus a changed `POST /baas/v1/accounts` contract: when the command is **guarded** (per-partner config enabled AND environment is PRODUCTION) the endpoint returns **202 ACCEPTED** with a `PENDING` task instead of creating the account; otherwise unchanged **201 CREATED** + account. The first (and currently only) guarded command is `ACCOUNT_OPEN`. New permissions seeded: `APPROVE_ACCOUNT`, `MANAGE_MAKER_CHECKER`; `PARTNER_APPROVER` (Spec A) now carries `APPROVE_ACCOUNT`. Tenant migration `V8__maker_checker_tasks.sql`. **CBN surface unchanged.** This task (Task 7 of 7) is documentation + build verification only — no production-code changes.

#### New/Updated Files
| File | Change |
|------|--------|
| `baas-engine/.../makerchecker/MakerCheckerTask.java` | Task entity — `command_type`, `payload` (JSONB), `made_by`, `status`, `checked_by/at`, `reject_reason`, `result_id`, `@Version` |
| `baas-engine/.../makerchecker/MakerCheckerTaskRepository.java` | `findByIdForUpdate` (PESSIMISTIC_WRITE) + status/command-type list queries; **distinct simple name** to avoid bean-name collision with legacy `social.*` |
| `baas-engine/.../makerchecker/MakerCheckerConfig.java` | Per-command opt-in switch entity (`command_type` PK, `enabled`) |
| `baas-engine/.../makerchecker/MakerCheckerConfigRepository.java` | Config repository |
| `baas-engine/.../makerchecker/MakerCheckerTaskService.java` | submit-if-guarded / approve (four-eyes + maker re-check + replay) / reject / withdraw / config (viability guard); execute-exactly-once via row-lock + `@Version` + status precondition |
| `baas-engine/.../makerchecker/MakerCheckerTaskController.java` | NEW — `/baas/v1/maker-checker` (7 endpoints); **distinct simple name** |
| `baas-engine/.../makerchecker/MakerCheckerCommandHandler.java` | Handler interface — `requiredAuthorityToSubmit/Approve`, `validate`, `execute`, `payloadType` |
| `baas-engine/.../makerchecker/MakerCheckerCommandRegistry.java` | Registry of handlers keyed by command type; `require()` → 400 `UNKNOWN_COMMAND_TYPE` |
| `baas-engine/.../makerchecker/MakerCheckerCommandType.java` | `ACCOUNT_OPEN` constant |
| `baas-engine/.../makerchecker/TaskStatus.java` | enum `PENDING/APPROVED/REJECTED/WITHDRAWN` |
| `baas-engine/.../makerchecker/dto/{TaskResponse,TaskDetailResponse,ConfigResponse,ConfigUpdateRequest,RejectRequest}.java` | DTOs; `TaskDetailResponse` carries `valid` / `wouldFailBecause` (dry-run) |
| `baas-engine/.../account/AccountOpenCommandHandler.java` | NEW — replays the IDENTICAL `AccountService.open` on approve (never a stripped re-implementation) |
| `baas-engine/.../account/AccountController.java` | `open()` now defers to `MakerCheckerTaskService.submitIfGuarded` → 202 task when guarded, else 201 account |
| `baas-engine/.../role/UserRoleRepository.java` | `countDistinctUsersWithPermission` + `countDistinctSuperusers` (viability guard) |
| `baas-engine/db/migration/tenant/V8__maker_checker_tasks.sql` | `maker_checker_tasks` + `maker_checker_config` tables; seeds `ACCOUNT_OPEN` config (OFF); `APPROVE_ACCOUNT` + `MANAGE_MAKER_CHECKER` permissions; grants `APPROVE_ACCOUNT` to `PARTNER_APPROVER` |
| `docs/api-reference.html` | NEW "Maker-Checker (Four-Eyes)" section (7 endpoints) + 202/201 note cross-referenced from the Accounts section |
| `CLAUDE.md` | Confirmed Platform Versions → Session 20 (`fd3ddde`); maker-checker module in Module Catalogue (closes DEF-1C-13); 1 new Known Gotcha (bean-name collision) |
| `baas-log.md` | This entry |

#### Key Decisions
- **Command-first replay.** The deferred/approve path invokes the IDENTICAL `AccountService.open` the synchronous path uses (`AccountOpenCommandHandler.execute`) — never a stripped re-implementation, so a guarded command behaves exactly like an unguarded one once approved.
- **Per-partner, per-command, opt-in, PRODUCTION-only enforcement.** A command is guarded only when the partner enabled it in `maker-checker/config` AND the caller's `PartnerContext.environment() == "PRODUCTION"`. Back-compatible: `ACCOUNT_OPEN` is seeded present but OFF.
- **Four-eyes + maker re-check closes the revocation backdoor.** Approve requires `checker ≠ maker` AND the command's `APPROVE_*` authority, AND re-checks the original maker is still active and still holds the submit authority at approval time — a maker whose access was revoked between submit and approve cannot have their task executed.
- **Execute-exactly-once.** `findByIdForUpdate` (PESSIMISTIC_WRITE) + `@Version` optimistic guard + a status precondition (`requirePending`) make the approve/reject/withdraw transition idempotent under concurrency; a non-`PENDING` task → `409 TASK_NOT_PENDING`.
- **Viability guard.** Enabling a command with no eligible approver (no user holds its `APPROVE_*`) → `409 NO_ELIGIBLE_APPROVER`, so a command can never be locked behind an approval nobody can grant. Counts superusers + permission-holders (over-estimate is harmless — it's a `>= 1` predicate, never an exact count).
- **Enumeration-safety via tenant-schema isolation.** Tasks live in the partner schema, so another org's task id naturally 404s — no cross-org leak, no explicit ownership check needed.
- **Distinct simple bean names (collision avoidance).** The new beans use `MakerCheckerTaskService` / `MakerCheckerTaskController` / `MakerCheckerTaskRepository`. A `MakerCheckerService` / `MakerCheckerController` in the new package would collide with the legacy passive `social.MakerCheckerService` / `MakerCheckerController` (route `/baas/v1/makercheckers`) → `ConflictingBeanDefinitionException` at startup. The command-first framework also does NOT read the legacy global `enable-maker-checker` flag — `maker-checker/config` is the single source of truth.

#### Build Verification
- `baas-engine`: **Tests run: 246, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS (`cd baas-engine && ./mvnw -o test`).
- No other service files changed this session (docs + build-verify only).

#### Confirmed Platform Versions

**BaaS Engine (`baas-engine/`):**
| Component | Version | Notes |
|-----------|---------|-------|
| Spring Boot | 3.5.0 | unchanged |
| Java | 21 | unchanged (CI); local JDK 25 for tests |
| Nimbus JOSE+JWT | 9.37.3 | unchanged |
| Last git commit | `fd3ddde` | Session 20 — command-first maker-checker / four-eyes (DEF-1C-13); 246 tests (last `baas-engine` commit; Task 7 docs touch no engine files) |

**BaaS Card (`baas-card/`):** unchanged this session — last commit `d647a4f` (Session 15).
**BaaS FEP (`baas-fep/`):** unchanged this session — last commit `a9e4cfd` (Session 12).
**BaaS Backoffice (`baas-backoffice/`):** unchanged this session — last commit `e96407e` (Session 18).
**BaaS Ncube (`baas-ncube/`):** unchanged this session — last commit `f102ae0` (Session 6).

#### Figma designs (SESSION COMPLETION GATE item 7)
No `baas-backoffice/src/**` screen changed this session (engine-only feature + docs). Item 7 is exempt.

---

### Session 19 — 2026-06-23
**Granular Partner RBAC (Spec A, DEF-1C-15) on `feat/partner-rbac` (PR #40). Final commit `9d51e96`. Engine: 218 tests, 0 failures.**

Removed the blanket-full authority fallback from `PartnerContextFilter` — partner JWTs are now deny-by-default; authorities come only from their DB role assignments. `PARTNER_ADMIN` is a dynamic superuser marker: `is_superuser=true` triggers `findAllCodes()` per request (for both partner users AND operators, preventing an operator-holding-PARTNER_ADMIN regression). Hybrid built-in + custom role support added (`ROLE_SCOPE`: `PARTNER` or `SHARED`). Scoped API key issuance (`cba_` prefix, SHA-256 hash, `scopes` JSONB column gated by `@JdbcTypeCode(SqlTypes.JSON)`). Idempotent provision-time + startup backfill via `PartnerRbacReconciler`. Partner-user / role / API-key management APIs added. Service-level privilege-escalation guards block a non-superuser delegate from assigning `is_superuser` roles, minting `["*"]` API keys, or granting a permission it doesn't itself hold. V7 Flyway migration deletes `PARTNER_ADMIN`'s static `role_permissions` rows (replaced by dynamic `findAllCodes()`). **CBN surface unchanged.**

#### New/Updated Files
| File | Change |
|------|--------|
| `baas-engine/.../auth/PartnerContextFilter.java` | Deny-by-default: removed blanket-full fallback; `OPERATOR_JWT` → RBAC scoped; `API_KEY`/`JWT` → DB role lookup |
| `baas-engine/.../auth/AuthorityResolver.java` | `PARTNER_ADMIN` dynamic superuser: `is_superuser=true` → `findAllCodes()` per request; applies to both partner users AND operators |
| `baas-engine/.../role/RoleService.java` | Built-in role protection; scope (`PARTNER`/`SHARED`) enforcement; escalation guards (superuser-role assignment, wildcard key, over-grant) |
| `baas-engine/.../role/RoleController.java` | CRUD gated by `MANAGE_ROLES` permission |
| `baas-engine/.../partner/PartnerUserService.java` | Partner-user creation/deactivation/role-assignment with escalation guards |
| `baas-engine/.../partner/PartnerUserController.java` | NEW — `/baas/v1/partner-users` (6 endpoints: create, list, get, roles, assign-role, deactivate) |
| `baas-engine/.../auth/PartnerApiKeyService.java` | Scoped key issuance; `@JdbcTypeCode(SqlTypes.JSON)` on `PartnerApiKey.scopes`; key value shown once |
| `baas-engine/.../auth/PartnerApiKeyController.java` | NEW — `/baas/v1/partner-api-keys` (1 endpoint: issue; revoke lives on `/api-keys/{id}`) |
| `baas-engine/.../auth/PartnerRbacReconciler.java` | NEW — idempotent reconciliation: provision-time backfill + `@EventListener(ApplicationReadyEvent)` startup backfill |
| `baas-engine/db/migration/public/V7__partner_rbac.sql` | `is_superuser` column; `role_scope` column; `PARTNER_ADMIN` static grants deleted; permission codes: `MANAGE_PARTNER_USERS`, `MANAGE_ROLES`, `MANAGE_API_KEYS` |
| `baas-engine/src/test/…/AbstractIntegrationTest.java` | `adminJwt(org, schema)` + `grantAdmin(schema, userId)` helpers — grant `PARTNER_ADMIN` for deny-by-default tests |
| `CLAUDE.md` | Confirmed Platform Versions → Session 19; Partner RBAC module in Module Catalogue; 7 new Known Gotchas |
| `baas-log.md` | This entry |

#### Key Decisions
- **Deny-by-default breaks `@PreAuthorize`-gated controller tests.** Removing the blanket-full fallback means a partner JWT for a userId with no DB role now 403s. Fix: `AbstractIntegrationTest.adminJwt(org, schema)` / `grantAdmin(schema, userId)` helpers grant `PARTNER_ADMIN` to the JWT subject — the test mirror of the production backfill.
- **`@JdbcTypeCode(SqlTypes.JSON)` is required to write a `String` to a `jsonb` column (Hibernate 6).** `@Column(columnDefinition="jsonb")` alone throws `column is of type jsonb but expression is of type character varying`. Applied to `PartnerApiKey.scopes`.
- **`@Transactional`-before-context pitfall (multi-tenancy).** A `@Transactional` method opens the Hibernate session (and resolves the tenant schema) at the Spring proxy boundary, BEFORE the method body sets `PartnerContext`. Fix: set context in a NON-transactional outer method, then delegate DB work to a `@Transactional` method on a SEPARATE bean (self-invocation bypasses AOP silently and is non-atomic).
- **`@ManyToOne(LAZY)` after repo tx closes → `LazyInitializationException`** (open-in-view is false). `PartnerContextFilter` reads `apiKey.getOrganization()` after lookup. Fix: use a `JOIN FETCH` query (`findByKeyHashAndActiveTrue`) so the org is initialized in-tx.
- **`PARTNER_ADMIN` is a dual-purpose dynamic superuser marker.** `is_superuser=true` → `findAllCodes()` per request; V7 deletes its static `role_permissions`. BOTH `partnerUserAuthorities` AND `operatorAuthorities` paths in `AuthorityResolver` must honor the marker — an operator holding `PARTNER_ADMIN` would otherwise resolve to empty authority.
- **Privilege-escalation guards live at the SERVICE layer, not just `@PreAuthorize`.** A delegate holding `MANAGE_*` passes the controller gate; the service additionally blocks: assigning an `is_superuser` role (non-superuser caller), minting `["*"]` API key scopes, granting a permission the caller doesn't itself hold.
- **`PartnerStatus` has NO `PRODUCTION` value** (`SANDBOX/PENDING_REVIEW/BASIC/PRO/ENTERPRISE/SUSPENDED`). `PartnerEnvironment.PRODUCTION` does exist. Spring Data path-traversal requires the underscore for `@ManyToOne` traversal (`findByOrganization_Id`).
- **Adversarial security review — ALL findings resolved in-session, no deferrals (per standing user preference).** The review caught two privilege-escalation paths + an enabler (a delegated `MANAGE_*` holder could assign `PARTNER_ADMIN`, mint a `["*"]` key, or grant unheld permissions) and minors. Fixes: service-layer escalation guards (C1/C2/I1), fail-closed UUID parsing (M1), reserved-role-name guard (M3), removed dead `fullTenantAuthorities` (N1), honest non-transactional reconciler (I2), **last-admin guard counts only ACTIVE admins (M2)**, and **`DELETE /roles/{id}` → 409 when the role is in use**. Proven by `EscalationGuardsTest` + `LastAdminAndRoleDeleteTest`.

#### Build Verification
- `baas-engine`: **Tests run: 218, Failures: 0** — BUILD SUCCESS
- No other service files changed this session.

#### Confirmed Platform Versions

**BaaS Engine (`baas-engine/`):**
| Component | Version | Notes |
|-----------|---------|-------|
| Spring Boot | 3.5.0 | unchanged |
| Java | 21 | unchanged |
| Nimbus JOSE+JWT | 9.37.3 | unchanged |
| Last git commit | `9d51e96` | Session 19 — Granular Partner RBAC; 218 tests |

**BaaS Card (`baas-card/`):** unchanged this session — last commit `d647a4f` (Session 15).
**BaaS FEP (`baas-fep/`):** unchanged this session — last commit `a9e4cfd` (Session 12).
**BaaS Backoffice (`baas-backoffice/`):** unchanged this session — last commit `e96407e` (Session 18).
**BaaS Ncube (`baas-ncube/`):** unchanged this session — last commit `f102ae0` (Session 6).

#### Figma designs (SESSION COMPLETION GATE item 7)
No `baas-backoffice/src/**` screen changed this session (engine-only RBAC feature). Item 7 is exempt.

---

### Session 18 — 2026-06-17
**Local-review + dev-experience session for the Accounts track. No `baas-engine` Java changed (the engine was only *run*, not modified). Two small frontend/devex PRs: `UPDATE_ACCOUNT` added to `.env.example` dev authorities (PR #35, MERGED `e96407e`) and a committed Vite dev proxy + `docs/backoffice-local-dev.md` for running the backoffice against a local engine (PR #36, OPEN). The Accounts console was stood up and verified end-to-end against a live local engine. CBN surface unchanged.**

The user asked to review the shipped Accounts UI for real rather than trust the Figma frames. Doing so surfaced two genuine local-dev gaps (neither affects production): (1) the Accounts track added `UPDATE_ACCOUNT` to `src/lib/rbac.ts` and `playwright.config.ts` but **not** `.env.example`, so a developer running the documented `cp .env.example .env` workflow got a dev-auth operator without `UPDATE_ACCOUNT` — silently hiding the freeze/unfreeze/close buttons (which gate on that permission). Fixed in PR #35. (2) Running the backoffice against a local engine fails two ways — the engine has **no CORS** config in 1C (cross-origin browser calls blocked) and **no `dev-token` bypass** (the placeholder token is not a valid JWT → 401). Fixed in PR #36 with a committed same-origin Vite dev proxy + a real-partner-JWT runbook.

To verify, a full isolated local stack was stood up alongside (not touching) the CoreBanking `cba-*` docker stack that owns `:5432`/`:8080`/`:6379`: nubbank Postgres on `:5442`, Redis on `:6390`, and `baas-engine` via `./mvnw spring-boot:run` (Homebrew **Java 21**) on `:8090`. Registered a partner → got its HMAC partner JWT → polled async tenant-schema provisioning → seeded 2 customers + 3 accounts (one funded for the freeze/withdraw demo, one zero-balance for the close demo). The backoffice was then pointed at the engine via the dev proxy (`VITE_API_BASE_URL=''` + `VITE_ENGINE_ORIGIN` + the partner JWT in `VITE_DEV_TOKEN`) and driven in a real browser (Playwright): the list rendered all 3 accounts with correct money formatting, and the detail page proved the §6 action map — **Freeze renders only because the operator now holds `UPDATE_ACCOUNT`**, and **Close shows only on the zero-balance account** (hidden on the funded one). The user then exercised close/withdraw/open live (data mutations persisted to the engine). Environment torn down afterward at the user's request.

#### New/Updated Files
| File | Change |
|------|--------|
| `baas-backoffice/.env.example` | **PR #35 (merged):** add `UPDATE_ACCOUNT` to `VITE_DEV_AUTHORITIES`. **PR #36 (open):** `VITE_API_BASE_URL` now empty by default (relative → dev proxy engages); add `VITE_ENGINE_ORIGIN`; note that a local engine needs a real partner JWT in `VITE_DEV_TOKEN` |
| `baas-backoffice/vite.config.ts` | **PR #36 (open):** function form + `loadEnv`; `server.proxy` `/baas` → `VITE_ENGINE_ORIGIN` (default `http://localhost:8080`). Dev-only; never affects `vite build` |
| `docs/backoffice-local-dev.md` | **PR #36 (open):** NEW end-to-end runbook — Postgres/Redis, engine boot env, partner register, seed, backoffice env, proxy/CORS + `dev-token` rationale |
| `CLAUDE.md` | Confirmed Platform Versions backoffice block → Session 18; 3 new Known Gotchas (no-CORS/no-dev-token-bypass local-dev proxy, dev-authority three-file sync, nubbank-vs-CoreBanking port isolation) |
| `baas-log.md` | This entry |

#### Key Decisions
- **Dev authorities live in THREE files that must stay in sync** — `src/lib/rbac.ts` (`PERMISSIONS`), `playwright.config.ts` (e2e), and `.env.example` (local `npm run dev`). A UI-gating permission must be added to all three; the Accounts track missed `.env.example`, hiding lifecycle buttons locally (PR #35).
- **Local-against-engine is a same-origin dev proxy, not an engine CORS change** — the engine has no CORS in 1C; adding a `server.proxy` in `vite.config.ts` (browser → Vite same-origin → `/baas/**` → engine) is the idiomatic, frontend-only fix. `VITE_API_BASE_URL` empty by default makes requests relative so the proxy engages; absolute (prod/Vercel) bypasses it. e2e is unaffected — specs stub via Playwright `page.route` (origin-agnostic) and set their own base URL.
- **The engine has no `dev-token` bypass** — the dev-auth provider's token must be a real partner JWT (`POST /baas/v1/auth/register`); its `schema_name` claim is what routes the tenant. Injected via `VITE_DEV_TOKEN`. First-party `PARTNER_ADMIN` JWTs get full tenant authority in 1C, so all account commands pass `@PreAuthorize`.
- **Local infra must avoid the CoreBanking docker ports** — `:5432`/`:8080`/`:6379` are owned by the `cba-*` stack; nubbank ran on `:5442`/`:8090`/`:6390`. Engine boot has no datasource/secret defaults (fails fast): `ENCRYPTION_KEY` is SHA-256-derived (any length), `JWT_SECRET` needs ≥32 chars (HS256), the rate limiter fails-open without Redis.
- **One service per PR / small PRs** — the env-sync fix (#35) and the dev-proxy + doc (#36) are separate, each frontend-only.

#### Build Verification
- **Zero `baas-engine` Java files touched** this session (engine only *run*) → engine build-verify legitimately skipped per the gate.
- `baas-backoffice`: `npm run typecheck` clean · **139/139 unit tests pass** (re-verified after the `vite.config.ts` function-form refactor) · committed proxy verified forwarding `:3001/baas` → live engine.

#### Confirmed Platform Versions

**BaaS Backoffice (`baas-backoffice/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| React | 19.x | `e96407e` (PR #35, merged) |
| Vite | 6.x | `e96407e` + PR #36 (dev proxy, open) |
| TypeScript | 5.x | `e96407e` |
| Routing / state | React Router 7 · TanStack Query 5 | `e96407e` |
| Test | Vitest + Playwright | 139 unit tests pass |
| Last git commit | `e96407e` | Session 18 — `UPDATE_ACCOUNT` in `.env.example` (PR #35, merged); dev proxy + local-engine doc (PR #36, open) |

**BaaS Engine (`baas-engine/`):** unchanged this session — last commit `f3c5122` (Session 17 Accounts backend, PR #33, now merged to `main`).
**BaaS Card (`baas-card/`):** unchanged — last commit `d647a4f` (Session 15).
**BaaS FEP (`baas-fep/`):** unchanged — last commit `a9e4cfd` (Session 12).

#### Figma designs (SESSION COMPLETION GATE item 7)
No new or changed `baas-backoffice/src/**` screen this session — the Accounts UI was *reviewed/run*, not modified (the only code changes are `.env.example`, `vite.config.ts`, and a doc). Item 7 is exempt (the Accounts As-Built frames from Session 17 remain current).

---

### Session 17 — 2026-06-16
**Accounts domain track — the second per-domain track on `baas-backoffice` (after Customers). ONE session, TWO independently-mergeable PRs (one service per PR): the `baas-engine` Accounts lifecycle + money gating in PR #33 (`feat/baas-engine-accounts-lifecycle`, 199 tests, OPEN) and the `baas-backoffice` Accounts console in this frontend PR #34 (`feat/baas-backoffice-accounts`, last feature commit `513ff73`, 139 unit + 1 Playwright e2e). CBN surface unchanged** (operator-facing console + operator-scoped account lifecycle, not Open Banking).

The backoffice gains its second real domain screen set, built on top of the existing Account/Transaction spine: an Accounts list with status filtering + account-number search, a detail page that is the §6 action map (per-status buttons gated by **both** permission and status, close-only-when-balance-0), an open-account modal with a debounced customer picker, a money modal (deposit/withdraw), an action modal (freeze/unfreeze/close), an oldest-first status-history timeline, and a colour-coded transaction ledger. The engine half (PR #33) adds the lifecycle state machine (`AccountService.transition` — FREEZE/UNFREEZE/CLOSE with reason + append-only `AccountStatusEvent` history), close guards (needs-zero-balance + close-only-from-ACTIVE), legal-hold money gating (deposit posts on ACTIVE+FROZEN; only CLOSED blocks credits; withdraw ACTIVE-only), a list endpoint (`JOIN FETCH` + explicit countQuery, status filter, account-number ILIKE search), detail + status-events endpoints, an optional atomic opening deposit (initial CREDIT `OPENING_DEPOSIT`), and `@PreAuthorize` on all 10 endpoints + the new `UPDATE_ACCOUNT` permission seeded in V6 (granted PARTNER_ADMIN + ACCOUNT_OFFICER).

> **Split into two independently-mergeable PRs** (per § Branch & PR Discipline — one service per PR): the **backend** Accounts lifecycle + money gating (`baas-engine`: `AccountService.transition`, `AccountStatusEvent` + `V6__account_status_events.sql` + `UPDATE_ACCOUNT` permission seed, list/detail/status-events endpoints, money-gating guards, `@PreAuthorize` on 10 endpoints) is PR #33 `feat/baas-engine-accounts-lifecycle` (OPEN, mergeable, 199 tests); the **frontend** Accounts console + this session ledger are on `feat/baas-backoffice-accounts` (PR #34, last feature commit `513ff73`). Zero file overlap — mergeable in any order; the frontend degrades gracefully if #33 is not yet deployed. Engine files are referenced here for the unified session snapshot; that code lives in #33.

#### New/Updated Files
| File | Change |
|------|--------|
| `baas-backoffice src/features/accounts/use-accounts.ts` | NEW read + mutation TanStack Query hooks (list/detail/open/transition/deposit/withdraw + status-events + transactions) |
| `baas-backoffice src/features/accounts/open-account-modal.tsx` | NEW open-account modal with a debounced customer picker (results rendered OUTSIDE the FormField) |
| `baas-backoffice src/features/accounts/accounts-list.tsx` + `account-status-badge.tsx` | NEW list screen + account status badge |
| `baas-backoffice src/features/accounts/money-modal.tsx` + `account-action-modal.tsx` | NEW money modal (deposit/withdraw) + lifecycle action modal (freeze/unfreeze/close) |
| `baas-backoffice src/features/accounts/account-status-history.tsx` + `transaction-ledger.tsx` | NEW oldest-first status-history timeline + colour-coded CREDIT/DEBIT ledger |
| `baas-backoffice src/features/accounts/account-detail.tsx` | NEW detail screen — §6 action map: per-status buttons gated by **both** permission and status; close-only-when-balance-0; `?? []` guard |
| `baas-backoffice src/lib/format.ts` | **Foundation change:** added shared `formatMoney(amount, currencyCode)` — single money-formatting helper (major-unit decimals, no /100); reused by list/detail/ledger |
| `baas-backoffice src/components/command-modal.tsx` | **Foundation change:** added `noValidate` to the shared `<form>` so RHF/Zod own all modal validation (native HTML5 validation was pre-empting Zod errors) |
| `baas-backoffice src/app/router.tsx` | Accounts routes wired (`/accounts` + `/accounts/:id`), `READ_ACCOUNT`-guarded via `RequireRoutePermission` |
| `baas-backoffice src/lib/rbac.ts` | `UPDATE_ACCOUNT` permission constant added |
| `baas-backoffice e2e/accounts.spec.ts` | NEW Playwright e2e: open → deposit → freeze → withdraw-blocked → unfreeze → close (against an evolving stub) |
| `docs/backoffice-operations.md` | Accounts routes + RBAC codes consumed (`UPDATE_ACCOUNT`) + endpoints-consumed table; FE follow-ups (ledger pagination UI, per-section error states, query-key namespacing review) |
| **PR #33 — `baas-engine` (referenced, lands separately):** `account/AccountStatusEvent.java` + `db/migration/tenant/V6__account_status_events.sql` | Append-only account-status history table + `UPDATE_ACCOUNT` permission seed (granted PARTNER_ADMIN + ACCOUNT_OFFICER) |
| **PR #33 — `baas-engine`:** `account/AccountService.java` (`transition`/`deposit`/`withdraw`/`open`), `AccountController.java` | Lifecycle state machine (atomic status+event write), close guards, legal-hold money gating, list/detail/status-events endpoints, optional atomic opening deposit, `@PreAuthorize` on 10 endpoints |

#### Key Decisions
- **Money mutations are pessimistic-locked + atomic.** `transition`/`deposit`/`withdraw`/`open` all load the account via `findByIdForUpdate` (PESSIMISTIC_WRITE); the status change + history-event write (or the balance update + initial `Transaction`) commit in a single `@Transactional`.
- **Legal-hold money gating, not a blanket block.** Deposit posts on ACTIVE **and** FROZEN — only CLOSED blocks credits → 409 `ACCOUNT_NOT_ACCEPTING_CREDITS`; withdraw is ACTIVE-only → 409 `ACCOUNT_NOT_ACCEPTING_DEBITS`. Close needs a zero balance (409 `ACCOUNT_BALANCE_NONZERO`) and is reachable only from ACTIVE (400 `INVALID_ACCOUNT_TRANSITION`); bad list status → 400 `INVALID_STATUS`.
- **`CommandModal` generic needs Zod input-type === output-type.** `.default()` / `z.coerce.number()` make Zod's input ≠ output and break `CommandModal<T>`'s `schema: ZodType<T>`. Optional/number fields are modelled as `z.string().optional().or(z.literal('')).refine(...)` and coerced at a `toBody` boundary; defaults are supplied via RHF `defaultValues`, never `.default()`.
- **`FormField` clones its single child to inject `id`.** Wrap exactly ONE labellable element (an `<Input>`) per `FormField`; sibling content (the customer-picker results list) is rendered OUTSIDE the `FormField`, or `getByLabelText` / `<label htmlFor>` targets a non-labellable wrapper.
- **Shared `formatMoney` is the single money-formatting helper** (`src/lib/format.ts`) — balances/amounts are major-unit decimals (do NOT divide by 100); never re-inline `Intl.NumberFormat`. Joins `humanizeStatus`/`formatDateTime` as the single source for display formatting.
- **`account_status_events` history is rendered oldest-first** (ascending) — deliberately the opposite of `customer_kyc_events` (newest-first, Session 16).
- **`noValidate` on `CommandModal`** — RHF/Zod own all modal validation across every modal; native HTML5 validation was pre-empting Zod errors. Verified benign, full suite green.
- **RBAC by `PERMISSIONS.*` constants only** (never string literals): list/detail actions → `UPDATE_ACCOUNT`; routes → `READ_ACCOUNT`. Detail buttons gate on **both** permission and current status.
- **One service per PR** reaffirmed: backend (#33) and frontend (#34) ship as separate PRs.
- **Deferred items added on the BACKEND branch (PR #33):** DEF-1C-32 (customer-name search in accounts list — needs an encrypted-name blind index) + DEF-1C-33 (harmonise invalid-lifecycle-transition HTTP status: Accounts 400 vs Customers 409). Not re-added to `docs/deferred-items.md` on this frontend branch.

#### Build Verification
- `baas-backoffice` (PR #34): typecheck clean · **139 unit tests + 1 Playwright e2e** · `vite build` (production) succeeds
- `baas-engine` (PR #33, referenced): **Tests run: 199, Failures: 0** — BUILD SUCCESS

#### Confirmed Platform Versions

**BaaS Engine (`baas-engine/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | PR #33 |
| Java | 21 | PR #33 |
| Nimbus JOSE+JWT | 9.37.3 | PR #33 |
| Last git commit | PR #33 (in-flight) | Session 17 — Accounts lifecycle + money gating on `feat/baas-engine-accounts-lifecycle` (#33), 199 tests; **not yet merged to `main`** (main unchanged at `b2f1709`) |

**BaaS Backoffice (`baas-backoffice/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| React | 19.x | `513ff73` |
| Vite | 6.x | `513ff73` |
| TypeScript | 5.x | `513ff73` |
| Routing / state | React Router 7 · TanStack Query 5 | `513ff73` |
| Test | Vitest + Playwright | `513ff73` |
| Last git commit | `513ff73` | Session 17 — Accounts domain track (second per-domain track); 139 unit + 1 Playwright e2e. Engine half in PR #33 (unmerged). |

**BaaS Card (`baas-card/`):** unchanged this session — last commit `d647a4f` (Session 15).
**BaaS FEP (`baas-fep/`):** unchanged this session — last commit `a9e4cfd` (Session 12).

#### Figma designs (SESSION COMPLETION GATE item 7)

Editable **Accounts — As Built** frames added to the [NubBank BaaS — Backoffice](https://www.figma.com/design/gEDnLrLD4UrChcND0yCdZ9/NubBank-BaaS-%E2%80%94-Backoffice) Figma design file (`gEDnLrLD4UrChcND0yCdZ9`), grouped in section **"Accounts — As Built (shipped UI · Session 17)"** — 5 editable frames (List, Detail, Open account, Money (Deposit/Withdraw), Action (Freeze/Close)), built on the NubBank Tokens + shell (cloned from the Customers As-Built frames and converted). Real frames, auto-layout, selectable text — not screenshots; the canonical design target frames are preserved untouched (reconciliation option B).

| As-Built frame | Mirrors |
|---|---|
| Accounts — As Built · List | Account-number / customer / status / balance columns, search + status filter, ACTIVE/FROZEN/CLOSED badges |
| Accounts — As Built · Detail | §6 action map — per-status buttons, status-history timeline, transaction ledger |
| Accounts — As Built · Open account | Debounced customer picker, product/currency, optional opening deposit, Save |
| Accounts — As Built · Money | Deposit/Withdraw amount + reason + Confirm |
| Accounts — As Built · Action | Freeze/Close reason textarea + transition note + Confirm |

---

### Session 16 — 2026-06-12
**Customers domain track — the first per-domain track on `baas-backoffice`. ONE session, TWO independently-mergeable PRs (one service per PR): the `baas-engine` KYC lifecycle in PR #28 (`feat/baas-engine-customer-lifecycle`, 166 tests, OPEN) and the `baas-backoffice` Customers console in this frontend PR (`feat/baas-backoffice-customers`, last feature commit `373ebcd`, 101 unit + 1 Playwright e2e). CBN surface unchanged** (operator-facing console + operator-scoped customer lifecycle, not Open Banking).

The backoffice gains its first real domain screen set: a Customers list with KYC filtering, a detail view that exposes masked PII (`bvnMasked`/`ninMasked` only — raw BVN/NIN never leaves the engine), the KYC state machine surfaced as operator actions (activate / suspend / reactivate / close) with an append-only history panel, plus create + edit. The engine half (PR #28) is the `CustomerKycEvent` append-only history, a 16-combination KYC state machine (`CustomerService.transition`), masked read DTOs, and an HMAC-SHA256 prefix blind-index name search (`name_search_tokens TEXT[]` GIN, mirroring `baas-card`'s `PanHasher`).

> **Split into two independently-mergeable PRs** (per § Branch & PR Discipline — one service per PR): the **backend** KYC lifecycle (`baas-engine`: `CustomerKycEvent` + `V5__customer_kyc_events.sql`, `NameTokenizer`, `CustomerService.transition`, masked `CustomerDetailResponse`, `CustomerController`) is PR #28 `feat/baas-engine-customer-lifecycle` (OPEN, mergeable, 166 tests); the **frontend** Customers console + this session ledger are on `feat/baas-backoffice-customers` (last feature commit `373ebcd`). Zero file overlap — mergeable in any order; the frontend degrades gracefully if #28 is not yet deployed. Engine files are referenced here for the unified session snapshot; that code lives in #28.

#### New/Updated Files
| File | Change |
|------|--------|
| `baas-backoffice src/features/customers/use-customers.ts` | NEW read + mutation TanStack Query hooks (list/detail/create/update/KYC-transition); openapi-fetch results unwrapped to real types behind the one accepted `as never` seam |
| `baas-backoffice src/features/customers/customer-form.ts` | NEW Zod schema for create/edit |
| `baas-backoffice src/features/customers/customer-form-modal.tsx` | NEW create/edit modal (conditionally mounted for a fresh form each open) |
| `baas-backoffice src/features/customers/customers-list.tsx` + `kyc-status-badge.tsx` | NEW list screen + KYC status badge |
| `baas-backoffice src/features/customers/kyc-action-modal.tsx` + `kyc-history.tsx` | NEW KYC state-machine action modal + append-only history panel |
| `baas-backoffice src/features/customers/customer-detail.tsx` | NEW detail screen — masked-PII profile + KYC actions + history + edit |
| `baas-backoffice src/lib/format.ts` | NEW shared `humanizeStatus` / `formatDateTime` — single source for status + date display |
| `baas-backoffice src/app/router.tsx` | Customers routes wired, `READ_CUSTOMER`-guarded via `RequireRoutePermission` |
| `baas-backoffice e2e/customers.spec.ts` | NEW Playwright e2e covering the Customers flow |
| `docs/backoffice-operations.md` | Customers routes + RBAC codes consumed + the two follow-ups (query-key namespacing review; `CommandModal` reset-on-open Foundation fix) |
| **PR #28 — `baas-engine` (referenced, lands separately):** `customer/CustomerKycEvent.java` + `db/migration/tenant/V5__customer_kyc_events.sql` | Append-only KYC history table + `name_search_tokens TEXT[]` GIN index |
| **PR #28 — `baas-engine`:** `customer/NameTokenizer.java` | HMAC-SHA256 prefix blind-index tokens (mirrors `baas-card` `PanHasher`) |
| **PR #28 — `baas-engine`:** `customer/CustomerService.java` (`transition`), `CustomerDetailResponse.java` (masked), `CustomerController.java` | 16-combo KYC state machine (atomic status+event write); masked-PII read DTO; create / list+detail / PUT / 4 transition POSTs / GET kyc-events |

#### Key Decisions
- **Blind-index name search.** Encrypted PII stays non-queryable; search runs over HMAC-SHA256 prefix tokens (length 2–12) in a `name_search_tokens TEXT[]` GIN column matched with Postgres `@>` containment — mirrors `baas-card`'s `PanHasher` so the two services share one pattern.
- **Masked PII is the only read surface.** The detail read-type exposes ONLY `bvnMasked`/`ninMasked` (`"•••••••"+last4`); raw BVN/NIN is never returned. `CustomerUpdateBody` cannot transmit raw `bvn`/`nin` — they are write-only at create.
- **KYC state machine mirrored on the frontend as an `ACTIONS` map** (PENDING_KYC→activate; ACTIVE→suspend/close; SUSPENDED→reactivate/close; CLOSED→none). `?? []` is kept as a runtime guard against an out-of-union wire status — `.map` on `undefined` would crash the screen.
- **`CommandModal` has no `form.reset()` on reopen**, so every Customers modal (create, edit, KYC action) is **conditionally mounted** (`{open && <Modal/>}`) to get a fresh form each open. Captured as a follow-up: a Foundation-level reset-on-open would remove the workaround.
- **`src/lib/format.ts` (`humanizeStatus`/`formatDateTime`) is the single source** for status + date display across the badge, history, and filter dropdown — no re-inlined `replaceAll('_',' ')` / `toLocaleString()`.
- **RBAC by `PERMISSIONS.*` constants only** (never string literals): list create → `CREATE_CUSTOMER`; detail actions + edit → `UPDATE_CUSTOMER`; routes → `READ_CUSTOMER`.
- **Production code cast-free** except the one accepted openapi-fetch `as never` seam in the hooks (results unwrapped to real types at the boundary).
- **One service per PR** reaffirmed: backend (#28) and frontend (this branch) ship as separate PRs.

#### Build Verification
- `baas-backoffice`: typecheck clean · **101 unit tests + 1 Playwright e2e** · `vite build` (production) succeeds
- `baas-engine` (PR #28, referenced): **Tests run: 166, Failures: 0** — BUILD SUCCESS

#### Confirmed Platform Versions

**BaaS Engine (`baas-engine/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | PR #28 |
| Java | 21 | PR #28 |
| Nimbus JOSE+JWT | 9.37.3 | PR #28 |
| Last git commit | PR #28 (in-flight) | Session 16 — Customers KYC lifecycle on `feat/baas-engine-customer-lifecycle` (#28), 166 tests; **not yet merged to `main`** (main unchanged at `b2f1709`) |

**BaaS Backoffice (`baas-backoffice/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| React | 19.x | `373ebcd` |
| Vite | 6.x | `373ebcd` |
| TypeScript | 5.x | `373ebcd` |
| Routing / state | React Router 7 · TanStack Query 5 | `373ebcd` |
| Test | Vitest + Playwright | `373ebcd` |
| Last git commit | `373ebcd` | Session 16 — Customers domain track (first per-domain track); 101 unit + 1 e2e |

**BaaS Card (`baas-card/`):** unchanged this session — last commit `d647a4f` (Session 15).
**BaaS FEP (`baas-fep/`):** unchanged this session — last commit `a9e4cfd` (Session 12).

#### Figma designs (SESSION COMPLETION GATE item 7)

Editable **Customers — As Built** frames added to the [NubBank BaaS — Backoffice](https://www.figma.com/design/gEDnLrLD4UrChcND0yCdZ9/NubBank-BaaS-%E2%80%94-Backoffice?node-id=0-1) Figma design file (`gEDnLrLD4UrChcND0yCdZ9`), grouped in section **"Customers — As Built (shipped UI · Session 16)"** (`138:2`). Built natively via `use_figma` — real frames, auto-layout, selectable text, reusing the existing **NubBank Tokens** variable collection + Instrument Sans (not screenshots). The original canonical frames are preserved untouched (reconciliation **option B** — design target + shipped reality side by side).

| As-Built frame | Node | Mirrors |
|---|---|---|
| Customers — As Built · List | `113:2` | Name/Email/External-ref/KYC columns, search + "All statuses" filter, ACTIVE/PENDING KYC/SUSPENDED/CLOSED badges |
| Customers — As Built · Detail | `113:287` | Masked BVN/NIN + Gender profile grid, lean KYC card, KYC history timeline (state-machine transitions) |
| Customers — As Built · New customer | `113:523` | First/Last name, Email, Phone, DOB, Gender, External ref, BVN, NIN, **Save** |
| Customers — As Built · KYC action | `113:848` | Reason textarea + ACTIVE → SUSPENDED transition note + Confirm |

> Gate item 7 was added Session 16 (PR #30); these frames are its first application. Going forward, every `baas-backoffice` module ships its editable Figma frames the same session.

---

### Session 15 — 2026-06-10
**Close DEF-1C-28 + DEF-1C-29 — the two engine-side gaps the backoffice Foundation was waiting on. Vertical slice across `baas-engine` (`b2f1709`), `baas-card` (`d647a4f`), `baas-backoffice` (`281739a`); engine 144 + card 105 + backoffice 75 tests green.**

The Foundation shipped with a dashboard whose tiles showed "—" and a PKCE auth path that resolved to zero authorities (Keycloak tokens don't carry them). This session built the two endpoints that close those gaps and wired the frontend to them — strict TDD. **CBN surface unchanged** (operator-facing identity + read-only aggregate, not Open Banking).

> **Split into two independently-mergeable PRs** (each service merges on its own track, no file overlap): the **backend** endpoints (`baas-engine` `b2f1709`/`ca7f855`, `baas-card` `d647a4f` + `docs/api-reference.html`) are in PR `feat/baas-engine-card-operations-api` (#27); the **frontend** wiring (`baas-backoffice`) + this session ledger are in PR `feat/baas-backoffice-foundation` (#26). Engine/card SHAs are recorded here for the unified session snapshot; that code lives in #27.

#### New/Updated Files
| File | Change |
|------|--------|
| `baas-engine .../operator/{OperatorMeController,OperatorIdentityService,OperatorMeResponse}.java` | NEW `GET /baas/v1/operators/me` — identity + server-resolved authorities (DEF-1C-28) |
| `baas-engine .../role/UserRoleRepository.java` | +`findRoleNamesByUserId` |
| `baas-engine .../dashboard/{DashboardController,DashboardService,DashboardSummaryResponse,CardStatsClient}.java` | NEW `GET /baas/v1/dashboard/summary` (DEF-1C-29) + best-effort engine→card stats client (graceful null) |
| `baas-engine .../{customer,account,loan}/*Repository.java` | +`countByKycStatus`, `countByStatus`/`sumBalanceByStatus`, `countByStatusIn` |
| `baas-engine src/test/resources/application-test.yml` | `card-base-url: http://127.0.0.1:1` (fast-fail → cardsIssued null in tests) |
| `baas-card .../stats/{CardStatsController,CardStatsService,CardStatsRequest,CardStatsResponse}.java` | NEW `POST /internal/v1/stats` — cards-issued count for the dashboard (DEF-1C-29) |
| `baas-backoffice src/features/dashboard/{use-dashboard.ts,dashboard.tsx}` | `useDashboardSummary` + tiles wired to real values |
| `baas-backoffice src/auth/{pkce-provider,create-provider}.ts` | `fetchOperatorAuthorities` + PKCE caches `/me` authorities (refresh on warm-up/userLoaded/redirect); `apiBaseUrl` threaded through |
| `baas-backoffice src/api/schema.d.ts` | Hand-seeded the two new GET paths |
| `docs/api-reference.html` | NEW baas-engine Operations API section + card `/internal/v1/stats` |
| `docs/backoffice-operations.md`, `docs/deferred-items.md` | `/me` authorities note + endpoints-consumed table; DEF-1C-28/29 → ✅ CLOSED |
| `assets/brand/nubeero-icon-round-border.png` | Preserved (relocated from repo root, `a8c7c58`) |

#### Key Decisions
- **`/me` is the fix for PKCE RBAC, not cosmetics.** The engine resolves authorities server-side and does NOT put them in the Keycloak token — so PKCE mode previously got `[]` (all nav hidden, all routes blocked). The provider now fetches `/me` on every session change and caches; token-claim parse is fallback-only. Dev mode (env authorities) unchanged, which is why CI/e2e stayed green throughout.
- **Cards tile crosses a service boundary, fail-soft.** Cards live in `baas-card`; the engine `CardStatsClient` calls `POST /internal/v1/stats` over the existing HMAC seam and returns `null` on any error. The dashboard renders every other tile when card-service is down (verified: engine tests have no card-service → `cardsIssued` null via a dead-loopback `card-base-url`).
- **Both new endpoints gate on `isAuthenticated()`, not a permission.** `/me` is self-identity; the dashboard is the operator's landing page and its data is already partner-schema isolated. No new permission/migration needed.
- **Auth gate honoured:** `/me` and `/dashboard/summary` reachable by any authenticated operator; unauthenticated → 401 via the existing tenant chain (tested).

#### Build Verification
- `baas-engine`: **Tests run: 144, Failures: 0** (+6) — BUILD SUCCESS
- `baas-card`: **Tests run: 105, Failures: 0** (+3) — BUILD SUCCESS
- `baas-backoffice`: typecheck clean · **75 tests** (+6) · `vite build` succeeds

#### Confirmed Platform Versions

**BaaS Engine (`baas-engine/`):** Last commit (app code) `b2f1709` — Session 15 — operations API (DEF-1C-28/29); 144 tests.
**BaaS Card (`baas-card/`):** Last commit (app code) `d647a4f` — Session 15 — `/internal/v1/stats` (DEF-1C-29); 105 tests.
**BaaS Backoffice (`baas-backoffice/`):** Last commit `281739a` — Session 15 — dashboard tiles + PKCE `/me` authorities; 75 tests.
**BaaS FEP (`baas-fep/`):** Last commit (app code) `a9e4cfd` — Session 12 (unchanged this session).

---

### Session 14 — 2026-06-09
**Phase 1C `baas-backoffice` Foundation — React 19 + Vite 6 + TS 5 operations console (deliverable D8); 22 commits `09c6807`→`57ffbdd`, 69 tests passing. Subagent-driven TDD build of the 18-task Foundation plan + 8-finding final-review fix batch. Governance: SESSION COMPLETION GATE broadened to per-service docs (all five services).**

Brainstormed → design spec (`docs/superpowers/specs/2026-06-07-baas-backoffice-design.md`, Figma `gEDnLrLD4UrChcND0yCdZ9` canonical) → 18-task TDD plan (`docs/superpowers/plans/2026-06-08-baas-backoffice-foundation.md`) → executed task-by-task with fresh implementer + two-stage review (spec then quality) per task. Foundation = the app skeleton every per-domain track builds on: Vite/Tailwind-4 setup, design tokens, shadcn/ui primitives, openapi-fetch client with auth middleware + envelope error seam, hybrid auth (dev-token vs Keycloak PKCE), RBAC permission gating, app shell + sidebar/topbar, dashboard, route guards, CI + Docker + k8s. **No Java touched** — engine/card/fep/ncube application SHAs unchanged; **CBN surface unchanged** (operator-facing UI, not Open Banking).

#### New/Updated Files
| File | Change |
|------|--------|
| `baas-backoffice/**` (81 files; 39 src + 25 test) | NEW React/Vite app: `src/api/` (client, envelope `unwrapResult` seam, query keys, schema.d.ts), `src/auth/` (types, dev-provider, pkce-provider, create-provider, context), `src/components/` (ui/* shadcn primitives, data-table, command-modal, form-field, status-badge, require-permission), `src/layout/` (app-shell, sidebar, topbar, nav-config), `src/features/` (auth/login+callback, dashboard), `src/lib/` (rbac, cn), `src/styles/` (tokens) |
| `baas-backoffice/{Dockerfile,nginx.conf,vite.config.ts,tsconfig*,playwright.config.ts,.env.example,.gitignore}` | Deployment-agnostic build: `node:22-alpine` build → `nginx:1.27-alpine` serve; Vitest + Playwright config |
| `.github/workflows/baas-backoffice-ci.yml` | NEW CI mirroring baas-engine-ci: test → e2e (Playwright) → build-and-push (GHCR + provenance + SBOM) → Trivy |
| `infrastructure/k8s/base/*` | baas-backoffice Deployment/Service/Ingress (Kustomize); documentary ConfigMap removed (runtime config injected by cluster) |
| `docs/backoffice-operations.md` | NEW living operations doc — routes, RBAC codes consumed, env vars, auth modes (gate item 4) |
| `docs/deferred-items.md` | DEF-1C-28 (operator `/me` endpoint), DEF-1C-29 (dashboard aggregate endpoint) recorded |
| `.claude/skills/baas/SKILL.md` | SESSION COMPLETION GATE broadened: item 1 (build) + item 4 (docs) now per-service matrices covering all five services; +2 rationalisation-trap rows |

#### Key Decisions
- **Hybrid auth provider, env-selected.** `VITE_DEV_AUTH=true` → fixed-token dev provider (local/CI/e2e); else Keycloak PKCE via `oidc-client-ts` v3. Both satisfy one `AuthProvider` contract so UI code is auth-agnostic. `isReady()` gate (FIX 3) holds protected routes behind a `Loading…` state until PKCE silent-signin resolves — prevents the login-flash a mid-bootstrap `isAuthenticated()===false` would otherwise cause.
- **Envelope error seam (FIX 1).** `unwrapResult(result)` inspects `result.response.status` + envelope `errors[]` and throws `ApiError`; the old `const { data } = await client.GET(...)` silently dropped openapi-fetch's parallel error channel. `use-dashboard.ts` is the first production consumer migrated, proving the seam end-to-end.
- **RBAC by permission code, not role.** Engine authorities (`READ_CUSTOMER`, `RUN_REPORT`, …) are NOT in the JWT — fetched per-request server-side; the UI mirrors them in `PERMISSIONS` (no string literals at call sites, FIX 6) and gates nav + routes via `hasPermission()`.
- **Final-review fix batch (8 findings, commit `57ffbdd`).** All fixed in-session, not deferred (per user standing preference): error seam, logout menu, async-ready guard, CI e2e job + dead-coverage-step removal, PERMISSIONS constants, reserved/duplicate primitive cleanup, documentary ConfigMap removal.
- **Gate governance change (user-directed).** SESSION COMPLETION GATE previously enforced API docs for `baas-engine` controllers only. Broadened so **every** service touched at any point in a session (`baas-backoffice`, `baas-card`, `baas-engine`, `baas-fep`, `baas-ncube`) must update its own doc surface — `api-reference.html` for REST services, `fep-iso8583-reference.md` for FEP, `backoffice-operations.md` for the console. Build-verification item likewise made per-service.

#### Build Verification
`baas-backoffice` only (no Java touched, other services exempt per gate item 1):
- `npm run typecheck` → clean (`tsc -b`)
- `npm test` → **Test Files 25 passed (25), Tests 69 passed (69)**
- `npm run build` → `vite build` succeeds (536 kB bundle; code-split deferred to per-domain tracks)
- `kubectl kustomize infrastructure/k8s/base` → renders OK after ConfigMap removal

#### Confirmed Platform Versions

No Java changed this session — engine/card/fep/ncube application SHAs unchanged from Session 13 (engine/card `1ca0d3b`, fep `a9e4cfd`). New frontend service added.

**BaaS Backoffice (`baas-backoffice/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| React | 19.x | `57ffbdd` |
| Vite | 6.x | `57ffbdd` |
| TypeScript | 5.x | `57ffbdd` |
| Tailwind CSS | 4.x | `57ffbdd` |
| Node | 22 | `57ffbdd` |
| Last git commit | `57ffbdd` | Session 14 — Foundation + final-review fix batch |

**BaaS Engine (`baas-engine/`):** Last commit (app code) `1ca0d3b` — Session 12 (unchanged this session).
**BaaS Card (`baas-card/`):** Last commit (app code) `1ca0d3b` — Session 12 (unchanged this session).
**BaaS FEP (`baas-fep/`):** Last commit (app code) `a9e4cfd` — Session 12 (unchanged this session).

---

### Session 13 — 2026-06-05
**Stage 5 deploy follow-up — wire the FEP datastore into deploy config; add `baas-card` + `baas-fep` to k8s. Infra/docs only, zero Java touched. Resolves the spec §11 "FEP deployment — datastore env required" follow-up.**

The FEP gained a datastore in Stage 5 but the deploy config never caught up: the compose `baas-fep` block had no `DATASOURCE_*` env, and the k8s base only ever covered engine + ncube — `baas-fep` (and `baas-card`) had **no** manifests at all. Built both card and fep as independent Kustomize resources so the Stage 5 money path is deployable in k8s, not just compose. **CBN surface unchanged** (deployment plumbing, not Open Banking).

#### New/Updated Files
| File | Change |
|------|--------|
| `infrastructure/docker-compose.yml` | `baas-fep`: add `DATASOURCE_{URL,USERNAME,PASSWORD}` + `depends_on` postgres(healthy)+card(started) (commit `6c39ff2`) |
| `infrastructure/.env.example` | Correct the stale "FEP — stateless, no DB" note; FEP reuses `POSTGRES_*` |
| `infrastructure/k8s/base/45,46,47-baas-card.*` | NEW: card ConfigMap, secret example, Deployment + ClusterIP(80→8081) + HPA + PDB |
| `infrastructure/k8s/base/70,71,72-baas-fep.*` | NEW: fep ConfigMap, secret example, Deployment + ClusterIP http(80→8082) + LoadBalancer TCP(8583); datastore env wired |
| `infrastructure/k8s/base/20-configmap.yaml` | engine: add `CARD_BASE_URL`/`NCUBE_BASE_URL` = `:80` (Service-port override; ncube was a pre-existing latent bug) |
| `infrastructure/k8s/base/60-ingress.yaml` | Partner→card routing: `/baas/v1/{cards,card-products,bins}` → baas-card (longest-prefix carve-out from engine's `/baas/v1`) |
| `infrastructure/k8s/base/kustomization.yaml` + 3 overlays | Register card/fep resources + image refs (`base-do-not-deploy` sentinel) |
| `infrastructure/k8s/components/network-policy/15-*.yaml` | +9 rules: ingress→card, terminals→fep:8583, fep→card, engine→card, card→engine, card/fep→postgres, card/fep egress; engine egress +card |
| `infrastructure/k8s/components/pod-disruption-budgets/15-*.yaml` | card + fep PDBs (minAvailable:1) |
| `docs/superpowers/specs/2026-06-04-...-design.md` (§11) | Marked the FEP-deployment follow-up ✅ RESOLVED with commit refs + sub-follow-ups |
| `CLAUDE.md` | +2 Known Gotchas (k8s Service port-80 inter-service URL trap; FEP fixed-replicas/no-HPA + L4 LB) |

#### Key Decisions
- **Built both card AND fep (Option 2), not fep-only.** A FEP deployed without card is liveness-green but function-dead (RC-91 on every authorization, because `CARD_BASE_URL` resolves to nothing). card's manifest is a near-clone of engine's, so the marginal cost was low. Kustomize resources are independent — building both doesn't couple them.
- **k8s Service-port trap (fixed + recorded as a gotcha).** Every `baas-*` Service fronts pods on port 80, so the app-side inter-service URL defaults (`:8081`/`:8082`/`:8080`) connection-refuse in k8s. All inter-service URLs pinned to `:80` in the ConfigMaps. Found and fixed a **pre-existing** instance: engine's `NCUBE_BASE_URL` override was missing.
- **FEP runs fixed replicas=2, NO HPA.** ISO 8583 holds long-lived sockets a naive CPU HPA can't rebalance; scale-down would sever live sessions. Safe under 2 replicas because debit idempotency is enforced at the engine (`auth_key` UNIQUE). Raw TCP exposed via L4 `LoadBalancer` (the L7 Ingress can't carry it) with `externalTrafficPolicy: Local` for source-IP preservation.
- **Flyway auto-creates the `fep` schema** (`create-schemas:true` + `default-schema:fep`) — no DB init script needed.
- **Partner→card Ingress routing added** (not deferred). card's partner API (`/baas/v1/{cards,card-products,bins}`) is carved out of engine's `/baas/v1` namespace via more-specific Ingress prefixes (longest-prefix match) + an `allow-ingress-to-card` NetworkPolicy rule. Auth stays at the service (PartnerContextFilter); the Ingress only routes.
- **Correction:** an earlier draft of this entry claimed card/fep had no GHCR CI — that was wrong (stale CLAUDE.md note). `.github/workflows/baas-card-ci.yml` and `baas-fep-ci.yml` already build + push `ghcr.io/<owner>/baas-{card,fep}:<sha>` on push to main (Trivy + SBOM + SLSA L1), so overlays resolve a real SHA today. No CI follow-up exists.

#### Build Verification
Zero Java files touched — no test run required (per skill gate). Infra validated instead:
- `docker compose config` → renders valid.
- `kubectl kustomize overlays/{dev,staging,prod}` → all build; rendered inter-service URLs all `:80`; fep-tcp = `LoadBalancer`; 6 Services / 15 NetworkPolicies / 5 PDBs / 3 HPAs (fep correctly has none).

#### Confirmed Platform Versions

No Java changed this session — engine/card/fep application code SHAs are unchanged from Session 12 (engine/card `1ca0d3b`, fep `a9e4cfd`). Infrastructure manifests advanced to `7cc5025`.

**BaaS Engine (`baas-engine/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | `1ca0d3b` |
| Java | 21 | `1ca0d3b` |
| Last git commit (app code) | `1ca0d3b` | Session 12 — Stage 5 (unchanged this session) |

**BaaS Card (`baas-card/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.3 | `1ca0d3b` |
| Java | 21 | `1ca0d3b` |
| Last git commit (app code) | `1ca0d3b` | Session 12 — Stage 5 (unchanged this session) |

**BaaS FEP (`baas-fep/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.3 | `a9e4cfd` |
| Java | 21 | `a9e4cfd` |
| Last git commit (app code) | `a9e4cfd` | Session 12 — Stage 5 (unchanged this session) |

**Infrastructure (`infrastructure/`):** Last commit `7cc5025` — Session 13 — card + fep k8s manifests + compose FEP datastore env.

---

### Session 12 — 2026-06-05
**Stage 5 — live card↔engine money wiring across `baas-engine` (`1ca0d3b`), `baas-card` (`1ca0d3b`), `baas-fep` (`a9e4cfd`); 138 + 102 + 55 tests passing. Closes DEF-1C-22, DEF-1C-23, DEF-1C-24, DEF-1C-25 (fund half); opens DEF-1C-27.**

Brainstormed → spec (`docs/superpowers/specs/2026-06-04-card-engine-stage5-money-wiring-design.md`) → 17-task TDD plan (`docs/superpowers/plans/2026-06-04-card-engine-stage5-money-wiring.md`) → implemented. An approved card authorization now performs a real, idempotent, fail-closed debit of the cardholder's engine account; a reversal credits it back; card schemas are auto-provisioned when the engine onboards a partner; and the FEP keeps a best-effort authorization audit trail. **CBN surface (OBR/consent/KYC/payment-rails) unchanged this session** — this is internal card↔engine money movement, not Open Banking; CBN compliance gap analysis unchanged.

#### New/Updated Files
| File | Change |
|------|--------|
| `baas-engine .../config/InternalServiceAuthFilter.java` + `SecurityConfig.java` | Inbound HMAC seam + `@Order(0)` internal chain for `/internal/v1/**` (Task 1) |
| `baas-engine .../account/CardAuthDebit*.java` + `tenant/V4__card_auth_debit.sql` | Dedupe + reversal-locator table/entity, UNIQUE `auth_key` (Task 2) |
| `baas-engine .../account/AccountService.java` | `cardAuthorizationDebit` (atomic idempotent), `cardAuthorizationCredit`, `lookupAccount` (Tasks 3–5) |
| `baas-engine .../account/InternalCardMoneyController.java` + dto | `/internal/v1/{card-debit,card-credit,account-lookup}`; PartnerContext-from-body (Task 5) |
| `baas-engine .../tenant/CardProvisioningClient.java` + `TenantProvisioningService.java` | Engine→card provisioning trigger; `card-provisioning-enabled` test flag (Task 12) |
| `baas-card .../config/InternalServiceClient.java` + `engine/EngineClient.java` + dto | Outbound HMAC signer + fail-closed engine client (Task 6) |
| `baas-card .../common/CurrencyMinorUnits.java` | `alphaFor` (DE49 numeric → ISO alpha) (Task 7) |
| `baas-card .../card/{Card,CardService}.java` + `dto/IssueCardRequest.java` + `card-tenant/V3__linked_account_id.sql` | `linkedAccountId` binding, engine-validated at issuance (Task 8) |
| `baas-card .../authorize/{AuthorizationDecisionService,ReversalService}.java` | authorize→engine-debit (RC 00/51/57/78/91), reversal→engine-credit (fail-closed 25) (Tasks 9–10) |
| `baas-card .../tenant/InternalProvisioningController.java` + dto | `/internal/v1/provision` (Task 11) |
| `baas-fep pom.xml` + `application.yml` + `db/migration/fep/V1__authorization_log.sql` | Datastore (Postgres prod / H2 tests) + audit migration (Task 13) |
| `baas-fep .../audit/{FepAuthorizationLog,AuthorizationAuditService}.java` | Best-effort audit writer; BIN+last4 only (Task 14) |
| `baas-fep .../router/{AuthorizationHandler,ReversalHandler}.java` | Record every authorize/reversal decision (Task 15) |
| `*ContractShapeTest` (engine+card), `SignerParityTest` (card) | Cross-service parity guards (Task 16) |
| `CLAUDE.md`, `baas-log.md`, `docs/contracts/phase1c-interfaces.md` (§2c/§2d), `docs/api-reference.html`, `docs/deferred-items.md` | Docs + gate close-out (Task 17) |

#### Key Decisions
- **Approach A — engine is the money-dedupe authority.** Debit+dedupe is one atomic engine-schema transaction keyed by `card_auth_debit.auth_key` (= `stan\|terminalId\|transmissionDateTime`). No distributed transaction; crash recovery is a plain idempotent retry. Card calls the engine first, then records its own decision row.
- **Single-message immediate debit** (no settlement layer exists). Reversal credits back, idempotent on the same key; engine-unreachable on an APPROVE credit → RC 25 without flipping `reversed` (terminal retries; credit is idempotent).
- **Card owns currency translation** — FEP→card stays ISO numeric; card translates to ISO alpha (and scales minor→major) so the engine compares alpha-to-alpha. (Self-review caught that comparing numeric to the engine's alphabetic `accounts.currency_code` would have declined every txn RC 57.)
- **RC map:** `00/51/57/78/91` for debit outcomes; missing `linkedAccountId`→78 and unknown currency→12 are local (no engine call).
- **FEP audit is best-effort** (a write failure never alters the ISO response) and stores BIN+last4 only.
- **DEF-1C-27 opened:** no automatic GL double-entry posting — engine reuses its single-`Transaction` path (a platform-wide concern, not card-seam).
- **FEP tests use H2 (PostgreSQL mode), not Testcontainers** — the FEP module's classpath trips a docker-java "Status 400" that engine/card don't hit; engine/card still verify the real-Postgres Flyway path. (See Known Gotchas.)

#### Build Verification
Tests run: baas-engine 138, baas-card 102, baas-fep 55 — Failures: 0, Errors: 0. BUILD SUCCESS (all three).

#### Confirmed Platform Versions

**BaaS Engine (`baas-engine/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | `1ca0d3b` |
| Java | 21 | `1ca0d3b` |
| Hibernate (SCHEMA multi-tenancy) | 6.x managed | `1ca0d3b` |
| Last git commit | `1ca0d3b` | Session 12 — Stage 5 card↔engine money seam; 138 tests |

**BaaS Card (`baas-card/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.3 | `1ca0d3b` |
| Java | 21 | `1ca0d3b` |
| Last git commit | `1ca0d3b` | Session 12 — Stage 5 authorize→debit / reversal→credit / linkedAccountId; 102 tests |

**BaaS FEP (`baas-fep/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.3 | `a9e4cfd` |
| Java | 21 | `a9e4cfd` |
| Persistence | spring-boot-starter-jdbc + Flyway (Postgres prod / H2 tests) | `a9e4cfd` |
| Last git commit | `a9e4cfd` | Session 12 — Stage 5 FEP authorization audit log; 55 tests |

### Session 11 — 2026-06-04
**Phase 1C seam hardening (F1–F8) — card↔FEP authorization seam correctness fixes across `baas-card` (`c8c5f28`) and `baas-fep` (`5a463cf`); 76 + 51 tests passing. Documentation/gate close-out commit is this entry.**

Eight findings (F1–F8) discovered during a systematic review of the card↔FEP authorization seam were ratified and implemented across both services. No CBN Open Banking / consent / KYC / OBR surface changed this session — CBN compliance gap analysis unchanged.

#### New/Updated Files

**`baas-card` (code commit `c8c5f28`):**

| File | Change |
|------|--------|
| `common/CurrencyMinorUnits.java` | JDK `Currency.getDefaultFractionDigits` scaling; RC 12 on unknown currency code (F1) |
| `resources/db/migration/card-tenant/V2__authorization_idempotency.sql` | New `authorization_idempotency` table; `reversed` flag on authorize log (F3, F7) |
| `limit/CardLimit.java` + `CardLimitService.java` | Currency-aware per-card limit enforcement; RC 57 (currency mismatch), RC 58 (limit exceeded in transaction currency) (F2) |
| `authorize/AuthorizationIdempotencyRepository.java` | Repository for idempotency table; nightly purge query (F3) |
| `authorize/AuthorizationIdempotencyPurgeJob.java` | `@Scheduled` nightly purge — enumerates all partner + sandbox schemas, sets `PartnerContext` per schema (F3, plan-correction: per-tenant scheduled job pattern) |
| `authorize/AuthorizationDecisionService.java` | Full rewrite — currency scaling, currency-aware limits, idempotency check + insert, schema-prefix env derivation (F4); NO outer `@Transactional` (plan-correction: context-setting service discipline) |
| `authorize/AuthorizationDecisionRequest.java` | + `stan`, `terminalId`, `transmissionDateTime` fields (F3, F6) |
| `authorize/ReversalService.java` | Locate original auth by DE90 fields; mark `reversed = true`; return `{ located }` (F5) |
| `authorize/ReversalController.java` | `POST /internal/v1/reversal` (HMAC); delegates to `ReversalService` (F5) |
| `authorize/ReversalDecisionRequest.java` + `ReversalDecisionResponse.java` | Request/response DTOs (F5) |
| `bin/BinService.java` | + `normalizeRangeEnd` method (pads short BIN end with `9`; `normalize` frozen for cross-track parity) (F6) |
| `auth/InternalServiceAuthFilter.java` | Replay window tightened to 60 seconds (F8) |
| `authorize/AuthorizationContractShapeTest.java` | Reflection shape test — asserts `AuthorizationDecisionRequest` has all required fields; per-module (F7) |

**`baas-fep` (code commit `5a463cf`):**

| File | Change |
|------|--------|
| `routing/AuthorizationDecision.java` | `Request` record extended with `stan`, `terminalId`, `transmissionDateTime` (F3, F6) |
| `router/AuthorizationHandler.java` | Populates DE11 (STAN), DE41 (terminalId), DE7 (transmissionDateTime) into the Card authorize request (F3) |
| `routing/ReversalDecision.java` | New `Request` + response records for the reversal contract §2b (F5) |
| `routing/CardClient.java` | + `reverse(ReversalDecision.Request)` → `Optional<ReversalDecision>` method (F5) |
| `client/HttpCardClient.java` | Implements `reverse()` over HMAC `RestTemplate`; fail-closed (`located: false` on any error) (F5) |
| `router/ReversalHandler.java` | Rewired — extracts DE90 original STAN + transmission date-time + DE41; calls `cardClient.reverse()`; maps `located` → RC 00/25 (F5) |
| `iso/IsoField.java` | + `DE90` constant (Original Data Elements) (F5) |
| `iso8583-1987-fields.xml` | DE90 LLVAR field definition (F5) |
| `config/CardClientConfig.java` | HMAC signer uses `request.getURI().getRawPath()` (raw, not decoded) to match card validator (F8) |
| `router/AuthorizationContractShapeTest.java` | Reflection shape test — asserts `AuthorizationDecision.Request` has all required fields; per-module (F7) |

**Documentation (this commit):**

| File | Change |
|------|--------|
| `docs/contracts/phase1c-interfaces.md` | §2a updated (3 new authorize fields, idempotency note, RC table extended, shape-test note); §2b added (reversal contract) |
| `docs/api-reference.html` | Authorize request shape updated (+3 fields); RC table extended; `POST /internal/v1/reversal` section added; MTI 0400 row updated; footer updated |
| `docs/deferred-items.md` | DEF-1C-25 status note updated — partial closure (locate + mark); fund reversal still Phase 2 |
| `CLAUDE.md` | Confirmed Platform Versions SHAs updated (c8c5f28 / 5a463cf); BaaS Card + FEP module catalogues updated with seam-hardening additions; 6 new Known Gotchas |
| `baas-log.md` | This entry |

#### Key Decisions

**F1 — Currency-correct minor-unit scaling:** JDK `Currency.getDefaultFractionDigits(currencyCode)` maps ISO 4217 numeric codes to exponents (NGN/KES/USD = 2; JPY = 0; KWD = 3). RC `12` (invalid transaction) returned for unknown/unparseable currency codes. Never hardcode exponent 2.

**F2 — Currency-aware card limits:** Per-card limits are denominated in the card product's currency. A transaction in a different currency returns RC `57` (transaction not permitted). A transaction exceeding the limit in the transaction currency returns RC `58`. Both checked only if the card has a limit row (null = unlimited).

**F3 — Authorization idempotency:** Idempotency key = `stan + "|" + terminalId + "|" + transmissionDateTime` (ISO DE11/DE41/DE7 — these fields never contain `|`). Persisted in a per-tenant `authorization_idempotency` table. Duplicate requests return the cached decision without re-evaluating. Daily purge job processes all partner + sandbox schemas. The UNIQUE constraint and the lookup both target `idem_key` alone — they always agree.

**F4 — Schema-derived environment:** Card services derive the partner environment (PRODUCTION/SANDBOX) from the schema name prefix (`sandbox_` → SANDBOX; `partner_` → PRODUCTION), not from a DB column. No extra DB lookup needed.

**F5 — DE90 reversal matching:** The `0400` reversal handler extracts DE90 (Original Data Elements) to get the original STAN and original transmission date-time, plus DE41 for terminal ID. These three fields identify the original authorization row in the per-tenant idempotency table. Card marks it `reversed = true` and returns `{ located: true/false }`. FEP maps `located: true` → RC `00`, `located: false` → RC `25`. Fund reversal (crediting the cardholder account) is deferred to Phase 2, riding with the real balance-check wiring (DEF-1C-23).

**F6 — BIN range-end coverage:** `BinService.normalizeRangeEnd` pads a short BIN end with `9` (e.g. `506775` → `50677599`) so that a BIN registered with a short `end` value covers the full sub-range below it. The frozen `normalize` (cross-track with FEP) is untouched — it still pads with `0`.

**F7 — HMAC raw-path parity:** `CardClientConfig`'s `SigningInterceptor` now signs `request.getURI().getRawPath()` (raw, undecoded). The card validator uses `httpRequest.getRequestURI()` which returns the raw path — both sides sign identical bytes. `getPath()` decodes percent-encoded segments and diverges.

**F8 — 60-second replay window:** `InternalServiceAuthFilter` accepts timestamps within ±60 seconds of server clock. This accommodates clock skew between container hosts without weakening replay protection meaningfully (prior window was unlimited).

**Plan-correction — no `@Transactional` on context-setting internal services:** A Spring AOP proxy opens the Hibernate session (invoking the tenant resolver) before the method body executes. If the method also calls `PartnerContext.set(...)`, the session opens against `public` schema (no context yet). Fix: remove outer `@Transactional`; set context first; do DB work in `try`; clear in `finally`. Applied to `AuthorizationDecisionService` and `ReversalService`.

**Plan-correction — per-tenant `@Scheduled` purge:** A scheduled job has no `PartnerContext` — it runs against `public`. Fix: enumerate all partner organizations, iterate `partner_<hex>` and `sandbox_<hex>` schemas, set `PartnerContext` per iteration, clear in `finally`. Applied to `AuthorizationIdempotencyPurgeJob`.

#### Build Verification
`baas-card`: Tests run: 76, Failures: 0, Errors: 0 — BUILD SUCCESS
`baas-fep`: Tests run: 51, Failures: 0, Errors: 0 — BUILD SUCCESS

#### Confirmed Platform Versions

**BaaS Card (`baas-card/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.3 | `c8c5f28` |
| Java | 21 | `c8c5f28` |
| Spring Security | 6.x (oauth2-resource-server present; operator-JWT RBAC deferred DEF-1C-20) | `c8c5f28` |
| Nimbus JOSE+JWT | 9.x (HMAC partner JWT) | `c8c5f28` |
| Flyway | 10.x (history table `flyway_schema_history_card`; V2 migration adds idempotency table) | `c8c5f28` |
| Testcontainers | PostgreSQL 16 in integration tests | `c8c5f28` |
| Last git commit | `c8c5f28` | Session 11 — seam hardening (F1–F8); 76 tests passing |

**BaaS FEP (`baas-fep/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.3 | `5a463cf` |
| Java | 21 | `5a463cf` |
| jPOS | 2.1.10 (from `jpos` repo) | `5a463cf` |
| Netty | 4.1.115.Final | `5a463cf` |
| Caffeine | 3.1.8 | `5a463cf` |
| Lombok | 1.18.38 | `5a463cf` |
| Architecture | STATELESS (no DB/JPA/Flyway/Postgres/Redis) | `5a463cf` |
| Last git commit | `5a463cf` | Session 11 — seam hardening (F1–F8); 51 tests passing |

### Session 10 — 2026-06-03
**Phase 1C Track-Card — `baas-card` card spine (products, issuance + lifecycle, per-card limits, public BIN lookup, internal authorize stub) — 56 tests (`cb06896`).**

New standalone microservice `baas-card` (port 8081), built on the same shared PostgreSQL as `baas-engine` with Hibernate SCHEMA multi-tenancy. Card-owned tables migrate under a dedicated Flyway history table (`flyway_schema_history_card`) so card and engine never collide on the shared DB. Built in parallel with Track-FEP (Session 9, merged into `main` first); Tasks 1–7 (the entire service) implemented and committed across 78 files / 5171 insertions from base `b40da63`; this session is the documentation/gate close-out. Merged onto `main` after Track-FEP, resolving the shared-doc registries (deferred-items, build log, API reference, docker-compose) as a union of both tracks.

#### New/Updated Files
| File | Change |
|------|--------|
| `baas-card/pom.xml`, `CardApplication.java` | Spring Boot 3.5.3 scaffold; port 8081; oauth2-resource-server present |
| `tenant/*` | Ported multi-tenancy (`PartnerContext`, `SchemaProvider`, `MultiTenantConnectionProvider`); card's own `TenantProvisioningService` for self-provisioning tests |
| `auth/*` | `PartnerJwtService` (HMAC partner JWT) + `ApiKeyResolver`; `InternalServiceAuthFilter` (inbound HMAC validate for `/internal/v1/**`) |
| `partner/*` | Read-views over engine-owned `public.partner_organizations` + `public.partner_api_keys` (decoupling deferred DEF-1C-21) |
| `config/*` | `SecurityConfig` (first-party partner JWT + API key chain; operator-JWT RBAC deferred DEF-1C-20); `FieldEncryptor` (ported AES-GCM-256) |
| `common/*` | Ported `ApiResponse` envelope `{ data, meta, errors }`, `BaasException`, `GlobalExceptionHandler` |
| `bin/*` | `CardBinRange` (`@Table(schema="public")`), `BinService` (8-char normalized range match), partner CRUD + internal `GET /internal/v1/bins/{bin}` lookup |
| `product/*` | `CardProduct` (tenant), product CRUD; race-safe creation constraint |
| `card/*` | `Card` (tenant; PAN AES-GCM encrypted, `pan_hash` HMAC-SHA256, masked responses), issuance + lifecycle state machine (`activate`/`block`/`unblock`/`cancel`) |
| `limit/*` | `CardLimit` (tenant); per-card limit upsert + view (all-null = unlimited) |
| `authorize/*` | Internal `POST /internal/v1/authorize` decision stub; ISO-8583 RC mapping; `PartnerContext` set-from-`schemaName` then cleared in `finally` |
| `resources/db/migration/card-public/`, `card-tenant/` | Public BIN-range migration + tenant card-table migrations (`flyway_schema_history_card`) |
| `resources/application.yml` | Port 8081; shared datasource; `spring.flyway.table: flyway_schema_history_card` |
| 9 test classes | `PanHasherTest`, `CardLifecycleTest`, `CardLimitTest`, `BinRegistrationTest`, `BinLookupTest`, `CardProductTest`, `CardProductConstraintRaceTest`, `CardApplicationContextTest`, `AuthorizationDecisionTest` |
| `Dockerfile`, `infrastructure/docker-compose.yml`, GHCR CI workflow | Container build + compose block + CI |
| `docs/deferred-items.md` | DEF-1C-20..23 registry rows (Track-Card) |
| `docs/api-reference.html` | New partner-facing card API reference (this session) |

#### Key Decisions
1. **Public-schema BIN table** — `CardBinRange` is `@Table(schema="public", name="card_bin_ranges")` because the FEP BIN lookup is cross-tenant: it runs *before* a tenant is known (null `PartnerContext` → public fallback reaches the table). All other card entities (`Card`, `CardLimit`, `CardProduct`) have **no** `@Table(schema=...)` so Hibernate routes them to the partner schema.
2. **Card-specific Flyway history** — card-owned tables migrate under `flyway_schema_history_card` (config `spring.flyway.table`), so card and engine never collide on the default `flyway_schema_history`. Public migrations in `db/migration/card-public/`, tenant migrations in `db/migration/card-tenant/`.
3. **First-party-only auth in 1C** — `/baas/v1/**` accepts partner JWT (HMAC) + API key only → full tenant authority (contract §1). Operator-JWT/Keycloak RBAC on card endpoints is DEFERRED (DEF-1C-20). Card reads engine-owned `public.partner_organizations` + `public.partner_api_keys` for auth; decoupling deferred (DEF-1C-21).
4. **Stateless internal decision-stub context discipline** — the FEP is a tenant-less caller; `AuthorizationDecisionService.decide()` does `PartnerContext.set(...)` from the request's `schemaName` and ALWAYS clears it in `finally` — a leaked ThreadLocal would route the next pooled-thread request to the wrong tenant schema.
5. **PAN safety** — PAN stored AES-GCM encrypted via ported `FieldEncryptor`; responses expose `maskedPan` only; the PAN is never logged anywhere (the decision stub logs only the decision + responseCode).
6. **Card tests self-provision** — card's integration tests provision their own tenant schema via card's `TenantProvisioningService` (engine→card provisioning trigger deferred DEF-1C-22).

#### Build Verification
Tests run: 56, Failures: 0, Errors: 0 — BUILD SUCCESS

#### Confirmed Platform Versions

**BaaS Card (`baas-card/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.3 | `cb06896` |
| Java | 21 | `cb06896` |
| Spring Security | 6.x (oauth2-resource-server present; operator-JWT RBAC deferred DEF-1C-20) | `cb06896` |
| Nimbus JOSE+JWT | 9.x (HMAC partner JWT) | `cb06896` |
| Flyway | 10.x (history table `flyway_schema_history_card`) | `cb06896` |
| Testcontainers | PostgreSQL 16 in integration tests | `cb06896` |
| Last git commit | `cb06896` | Session 10 — Phase 1C Track-Card; 56 tests passing |

### Session 9 — 2026-06-02
**Phase 1C Track-FEP (D7) — `baas-fep`, a stateless ISO 8583-1987 front-end processor (`29400fc`).**

Built in parallel with Track-Card against a **mocked `CardClient`** (no `baas-card` source read or imported; live wiring is Stage 5). Executed via subagent-driven development: 8 tasks, fresh implementer + spec-compliance review + code-quality review per task, every reviewer finding (incl. Minor) resolved in-task. A Netty TCP server (port 8583, 2-byte length framing) frames ISO 8583 messages; a jPOS `GenericPackager` packs/unpacks; an MTI router dispatches `0100/0200/0400/0800`; BIN→partner tenant routing resolves the owning partner via Card's `GET /internal/v1/bins/{bin}` (Caffeine 5-min cache); the authorization flow forwards to Card's `POST /internal/v1/authorize` and maps the decision to DE39 in the response.

#### New/Updated Files
| File | Change |
|------|--------|
| `baas-fep/pom.xml`, `mvnw`, `.mvn/` | New module — Spring Boot 3.5.3 parent; deps: web, security, validation, actuator, netty-all 4.1.115, jpos 2.1.10 (via `jpos` repo), caffeine 3.1.8, nimbus, lombok. NO data-jpa/flyway/postgres/redis/jasypt/testcontainers |
| `baas-fep/Dockerfile` | Multi-stage; EXPOSE 8082 + 8583; health on 8082; pinned base-image digests; non-root |
| `fep/FepApplication.java` | `@SpringBootApplication` |
| `fep/common/{ApiResponse,BaasException}.java` | PORTED verbatim from `baas-engine`, repackaged `engine`→`fep` |
| `fep/config/{FepProperties,SecurityConfig,CardClientConfig}.java` | `@ConfigurationProperties(prefix=fep)`; actuator-only chain (deny rest); `RestTemplate` + ported HMAC `SigningInterceptor` |
| `fep/iso/{IsoField,IsoMessageFactory}.java` + `iso8583-1987-fields.xml` | DE constants; jPOS `GenericPackager` pack/unpack helpers |
| `fep/server/{FepTcpServer,FepServerInitializer,FepMessageHandler}.java` | Netty lifecycle (`@PostConstruct`/`@PreDestroy`); `LengthFieldBasedFrameDecoder(65535,0,2,0,2)`+`LengthFieldPrepender(2)`; `@ChannelHandler.Sharable` decode→route→encode, RC 96 on error |
| `fep/router/{MessageRouter,AuthorizationHandler,FinancialHandler,ReversalHandler,NetworkHandler}.java` | MTI switch; `0100→0110`/`0200→0210` auth flow; `0400→0410` stub-approve; `0800→0810` network; unknown MTI → RC 30 |
| `fep/routing/{BinResolver,PartnerRoute,CardClient,AuthorizationDecision}.java` | DE2→8-char BIN normalization + Caffeine cache; `CardClient` interface; decision DTOs |
| `fep/client/HttpCardClient.java` | `CardClient` impl over HMAC `RestTemplate`; reads `.data`; fail-closed (`Optional.empty()` / `DECLINE`/`96`) |
| `application.yml` + `application-test.yml` | server 8082; `fep.tcp-port` 8583 (`0` in tests); `fep.card.base-url`; `fep.hmac-secret` |
| 11 test files (`FepContextTest`, `IsoMessageFactoryTest`, `FepTcpServerLoopbackTest`, `AuthorizationHandlerTest`, `NetworkHandlerTest`, `BinResolverTest`, `AuthorizationDecisionTest`, `client/HttpCardClientTest` (MockRestServiceServer — HTTP layer + Instant deser + HMAC + fail-closed), `support/{Iso8583TestClient,StubCardClient}`) | 46 tests; Card client mocked throughout; assert `!response.hasField(2)` on RC 91 |
| `infrastructure/docker-compose.yml` | `baas-fep` block (host 8083→8082 HTTP, 8583 TCP; readiness healthcheck; `depends_on: baas-card` commented until Stage 5; `FEP_TCP_PORT` in lockstep with host port) |
| `infrastructure/.env.example` | BaaS fep section (`BAAS_FEP_HTTP_PORT`/`TCP_PORT`, `CARD_BASE_URL`); `INTERNAL_SERVICE_SECRET` note extended to fep→card |
| `.github/workflows/baas-fep-ci.yml` | CI: test → GHCR build/push → Trivy/SBOM; pinned SHAs; lowercased GHCR owner |
| `docs/deferred-items.md` | + DEF-1C-24..26 (FEP auth-log persistence; real 0400 reversal; BIN-change cache invalidation) |
| `docs/contracts/phase1c-interfaces.md` | §2a — non-normative Track-FEP consumption-confirmation note (frozen shapes unchanged) |
| `CLAUDE.md` | + `baas-fep` Confirmed Platform Versions block, Module/MTI catalogue, 6 FEP gotchas, repo-structure line |

#### Key Decisions
- **Stateless FEP, no DB.** FEP holds no tenant data and sets **no `PartnerContext`** — it routes and forwards, passing `schemaName` to Card in the authorize request body so Card sets its own tenant context. No JPA/Flyway/Postgres/Redis dep exists; adding one breaks the architecture.
- **2-byte big-endian length framing** (jPOS standard) via Netty `LengthFieldBasedFrameDecoder`/`LengthFieldPrepender`.
- **BIN normalization parity is a frozen cross-track invariant** (contract §2): `BinResolver.bin(...)` = take ≤8 leading PAN digits, left-align, zero-pad to 8 — must equal Card's `BinService.normalize(...)` or every lookup misses.
- **Unrouteable BIN → RC `91` with DE2 omitted** (no PAN echo); asserted via `!response.hasField(2)` in tests.
- **PAN is never logged** — masked to `****<last4>` in `Request.toString`; diagnostics log partnerId/amount/currency only.
- **Fail-closed Card calls** never throw into the Netty thread: BIN lookup 404/error → unrouteable; authorize transport error → `DECLINE`/`96`; handler catch-all → RC 96.
- **jPOS 2.1.10 from the `jpos` Maven repo** (`https://jpos.org/maven`) — not on Central; verified resolving.
- **EMV/HSM/scheme-packagers/settlement/tokenization deferred** (DEF-1C-01..07) — correctly absent in 1C.

#### Build Verification
`cd baas-fep && ./mvnw -B test` → Tests run: 46, Failures: 0, Errors: 0 — BUILD SUCCESS (Card client mocked throughout).

#### Confirmed Platform Versions

**BaaS FEP (`baas-fep/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.3 | `29400fc` |
| Java | 21 | `29400fc` |
| jPOS | 2.1.10 (from `jpos` repo) | `29400fc` |
| Netty | 4.1.115.Final | `29400fc` |
| Caffeine | 3.1.8 | `29400fc` |
| Lombok | 1.18.38 | `29400fc` |
| Architecture | STATELESS (no DB/JPA/Flyway/Postgres/Redis) | `29400fc` |
| Last git commit | `29400fc` | Session 9 — Phase 1C Track-FEP (D7); 46 tests passing |

### Session 8 — 2026-05-30
**Phase 1C Foundation track — operator identity (Keycloak multi-issuer) + Hybrid RBAC + 30-role catalogue (`1010ca9`).**

First track of Phase 1C. Adds the human-operator authentication/authorization foundation to `baas-engine`, layered **additively** over the existing HMAC partner-JWT + API-key path. Executed via subagent-driven development: 10 tasks, fresh implementer + spec-compliance review + code-quality review per task, plus a final holistic review (READY TO MERGE).

#### New/Updated Files
| File | Change |
|------|--------|
| `baas-engine/pom.xml` | + `spring-boot-starter-oauth2-resource-server` (provides `NimbusJwtDecoder`) |
| `partner/PartnerOrganization.java` + `PartnerOrganizationRepository.java` | + `keycloakIssuer` field/column + `findByKeycloakIssuer` |
| `partner/PartnerStatus.java` | + `isActiveForAuth()` (SANDBOX/BASIC/PRO/ENTERPRISE true; SUSPENDED/PENDING_REVIEW false) |
| `db/migration/public/V3__operator_identity.sql` | `keycloak_issuer` column + partial unique index |
| `auth/keycloak/OperatorJwtProperties.java` | `app.keycloak.admin-issuer` config record |
| `auth/keycloak/OperatorJwtDecoderFactory.java` + `JwksOperatorJwtDecoderFactory.java` | testable per-issuer JWKS decoder seam (cached) |
| `auth/keycloak/OperatorJwtResolver.java` | Keycloak operator JWT → `PartnerContext` (allowlisted issuer, active-status gate, crypto-verified, fail-closed) |
| `tenant/PartnerContextFilter.java` | branch on `iss` (admin→reject, operator→resolve, null→HMAC fallback); `populateAuthorities()`; clears both `PartnerContext` + `SecurityContextHolder` in `finally` |
| `auth/AuthorityResolver.java` | operator → RBAC-scoped permission codes; first-party (API_KEY/JWT) → full tenant authority |
| `role/UserRoleRepository.java` + `role/PermissionRepository.java` | `findPermissionCodesByUserId`, `findAllCodes` |
| `config/MethodSecurityConfig.java` | `@EnableMethodSecurity` |
| `customer/CustomerController.java` | `@PreAuthorize` CREATE_CUSTOMER / READ_CUSTOMER (demonstration) |
| `common/GlobalExceptionHandler.java` | `AccessDeniedException` → 403 `ACCESS_DENIED` envelope |
| `db/migration/tenant/V3__role_catalogue.sql` | 30 partner roles + core-role grants + maker-checker on `APPROVE_LOAN` |
| `auth/OperatorProvisioningService.java` | `revokeAllGrants(sub)` (orphan-grant mitigation) |
| `auth/KeycloakUserDirectory.java` + `StubKeycloakUserDirectory.java` + `auth/OperatorGrantReconciliationJob.java` | nightly reconciliation seam (no-op stub; live impl DEF-1C-17) |
| `config/SecurityConfig.java` | partner chain scoped `@Order(2)` + `securityMatcher` (admin-chain readiness) |
| `src/main/resources/application.yml` | `app.keycloak.admin-issuer` env placeholder |
| `docs/deferred-items.md` | DEF-1C-01..19 registry (new) |
| `docs/contracts/phase1c-interfaces.md` | operator-auth / BIN-lookup / admin-namespace contracts (new) |
| 9 new test files | issuer lookup, decoder factory, operator JWT resolver (+ `TestJwks`), authority resolver, customer authz, role catalogue seed, operator provisioning |

#### Key Decisions
- **Operator auth is additive, not a replacement** — multi-issuer Keycloak validation branches on the JWT `iss`; legacy HMAC tokens have `iss=null` and fall through to `PartnerJwtService` unchanged.
- **Authority boundary** — first-party partner credentials (API key, HMAC partner-login) get the FULL tenant authority set; delegated Keycloak operators get RBAC-scoped authorities from tenant-schema `user_roles`. Granular RBAC for HMAC users deferred (DEF-1C-15).
- **Fail-closed everywhere** — non-UUID operator subject, unknown issuer, suspended/pending partner, expired/forged token → empty SecurityContext → 401; an admin-issuer token presented to the partner API gets no context (→401). The early `return` in the operator branch of `resolveJwt` is load-bearing: a known-issuer token that fails crypto must NOT fall through to the HMAC verifier.
- **Method security demonstrated on `CustomerController` only**; per-module `@PreAuthorize` rollout deferred (DEF-1C-16). `AccessDeniedException`→403 envelope reaches `@ControllerAdvice` (not `ExceptionTranslationFilter`) because the chain uses `anyRequest().permitAll()` — verified by a response-body assertion.
- **30-role catalogue** seeded per tenant schema (tenant `V3`); 12 core roles granted from the 13 V2 permissions; the `PARTNER_ADMIN` CROSS JOIN is bounded to V1–V2 permissions (future permission migrations must extend it — DEF-1C-16).
- **SecurityConfig** scoped (`@Order(2)` + `securityMatcher`) so the Custodian track can add an `@Order(1)` `/baas-admin/v1/**` chain without conflict.

#### Build Verification
Tests run: 111, Failures: 0, Errors: 0 — BUILD SUCCESS

#### Confirmed Platform Versions

**BaaS Engine (`baas-engine/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | `1010ca9` |
| Java | 21 | `1010ca9` |
| Spring Security | 6.5.x (+ `spring-boot-starter-oauth2-resource-server` → `NimbusJwtDecoder` for Keycloak operator JWTs) | `1010ca9` |
| Nimbus JOSE+JWT | 9.x | `1010ca9` |
| Last git commit | `1010ca9` | Session 8 — Phase 1C Foundation; 111 tests passing |

### Session 7 — 2026-05-17
**Introduce opt-in Expert Review + Phase-Gate Review pattern; unwind per-session enforcement and CI mirror.**

#### New/Updated Files

| File | Change |
| --- | --- |
| `.claude/skills/baas/SKILL.md` | Modified — replaced "Expert Review — Required After Every Substantive Answer" with "Expert Review — On Request" (opt-in tool). Added "Phase-Gate Review" section as the primary place the review is exercised, with explicit closure-state vocabulary (`[resolved]`, `[deferred-to-phase-N]`, `[accepted-risk]`, `[wontfix]`). Removed Session Completion Gate item 9 (per-session Expert Review capture) and the "Session Start Re-grounding" section. Item 10 collapsed into item 9 (commit/push). |
| `.githooks/pre-push` | New (uncommitted previously) — 36 lines, Gate 1 only (`Confirmed Platform Versions` presence in `baas-log.md` + `CLAUDE.md`). Gate 2 (risk-path summary enforcement) and the `risk-paths.txt` loader were not introduced in the committed state. |
| `.github/pull_request_template.md` | New — Summary / Scope / Test plan / Risks / rollback / Links. Comment header notes the opt-in Expert Review trigger word. No gate-related checklist sections. |
| `docs/phase-gate-reviews.md` | New — phase-gate aggregation table + 13-row historical seed (real critiques from Sessions 1–6, all `↑ Promoted [backfill]` to `CLAUDE.md` § Known Gotchas) + Topic Tags + column-aware `awk -F'\|'` audit helpers. Renamed from the never-committed `docs/expert-review-summary.md`. |

**Files created in working tree during session but not committed** (intentionally — they belonged to the unwound per-session gate):

- `.githooks/risk-paths.txt`
- `.github/workflows/expert-review-gate.yml`
- `.github/BRANCH-PROTECTION.md`

#### Key Decisions

1. **Per-session forced Expert Review was identified as having no natural stop condition.** A 20+ year banking engineer with scars always finds something to flag; without an exit criterion the summary doc becomes a backlog you can never empty, and Re-grounding at every session start compounds the load. The fix is *not* a tougher rule — it's removing the forced cadence and moving the review to phase boundaries.
2. **Kept the Expert Review persona, format, anti-patterns, and trigger word.** The 20+ year persona is a real tool; what was wrong was treating it as a gate. It is now opt-in: invoked when expensive-to-reverse decisions are on the table, or when the user types `expert review` / `second pass` / `review your last answer`.
3. **Introduced Phase-Gate Review as the primary exercise point.** One review per phase against the whole phase's deliverables, captured in `docs/phase-gate-reviews.md` with an explicit closure state. Closure states `[deferred-to-phase-N]` and `[accepted-risk]` close the row without pretending the work is done — solves the "row sits unpromoted forever" trap that the per-session model created.
4. **Stripped Gate 2 from `.githooks/pre-push`.** Kept Gate 1 (`Confirmed Platform Versions` block presence) — cheap, load-bearing, and not part of the merry-go-round. The hook is local-only; setup is unchanged: `git config core.hooksPath .githooks` after clone.
5. **Removed the CI mirror workflow (`expert-review-gate.yml`) and the branch-protection required check** on `RazorMVP/nubbank-baas:main`. Branch protection itself is retained: force-push off, deletions off, conversation resolution on, code-owner reviews on, 0 required approvals (solo-developer setup). Verified via `gh api /repos/RazorMVP/nubbank-baas/branches/main/protection` read-back — `required_status_checks` is now `null`.
6. **Historical 13-row backfill from Sessions 1–6 preserved** in the new `docs/phase-gate-reviews.md` § Historical Seed block. All 13 rows are `↑ Promoted [backfill]` and already in `CLAUDE.md` § Known Gotchas — they are not in-flight critiques. They remain as institutional memory and as test data for the awk audit helpers.
7. **PR template rewritten** to remove the Expert Review checklist. Reviewers now see a clean Summary / Scope / Test plan / Risks / Links structure. The opt-in trigger word is documented in the comment header so contributors know it's available without being mandatory.

#### Build Verification

Skipped — this session touched zero Java files. Per Session Completion Gate item 1, "Only sessions that touched zero Java files may skip."

#### Confirmed Platform Versions

**BaaS Engine (`baas-engine/`):**

| Component | Version | Git ref |
| --- | --- | --- |
| Spring Boot | 3.5.0 | `ac5687b` |
| Java | 21 | `ac5687b` |
| Nimbus JOSE+JWT | 9.37.3 | `ac5687b` |
| Last git commit | `ac5687b` | Session 6 — Phase 1F-E merge |

No engine code changed in this session; engine SHA carries forward from Session 6's Phase 1F-E merge.

---

### Session 6 — 2026-05-09
**Phase 1F-E infrastructure hardening — closes 6 critical, 13 important, 9 minor 1E findings across 22 tasks on `feature/phase1f-e-infra`. Plus: security fix — `/actuator/health/**` blocked by SecurityConfig (engine + ncube). Branch HEAD `f102ae0` + security-fix commit.**

#### Tasks Completed

| Task | Files Changed | 1E Refs Closed |
|------|---------------|----------------|
| 1 — Dockerfile healthcheck binary (curl) | 2 Dockerfiles | C1, I13 |
| 2 — Deterministic jar copy (`finalName=app`) | 2 pom.xml | C2 |
| 3 — Maven dependency:go-offline split for layer cache | 2 Dockerfiles | I12 |
| 4 — JVM hardening flags into ENTRYPOINT | 2 Dockerfiles | C3 |
| 5 — Pin base images to manifest digests | 2 Dockerfiles | C4 |
| 6 — `.dockerignore` files for both modules | 2 `.dockerignore` | I14 |
| 7 — Kustomize tree (base + dev/staging/prod overlays) | `infrastructure/k8s/` restructure | I1 |
| 8 — `baas-ncube-config` ConfigMap (NPS_ENDPOINT rename) | 2 overlay files | I2, m9 |
| 9 — Postgres StatefulSet hardening (SecurityContext, resources, pg_isready) | `30-postgres.yaml` | I3, m10 |
| 10 — PodSecurityContext on engine + ncube (UID 100, GID 101) | `40-baas-engine.yaml`, `50-baas-ncube.yaml` | I4 |
| 11 — NetworkPolicy as Kustomize Component (`kubernetes.io/metadata.name` selectors) | `components/network-policy/` | C5 |
| 12 — Split `baas-ncube-secrets` from `baas-engine-secrets` (least privilege) | `17-baas-ncube-secrets.example.yaml`, overlays | I5 |
| 13 — startupProbe + readiness/liveness path correctness, named ports | `40-baas-engine.yaml`, `50-baas-ncube.yaml` | I6, m5 |
| 14 — PodDisruptionBudgets as Kustomize Component | `components/pod-disruption-budgets/` | I7, m8 |
| 15 — GHCR imagePullSecrets workflow documented in README | `infrastructure/k8s/README.md` | m12 |
| 16 — Trivy CVE + SBOM (dual-source) + SLSA L1 provenance in CI | `.github/workflows/` | C6, I9 |
| 17 — Pin all GitHub Actions to commit SHAs + Dependabot | `.github/workflows/`, `dependabot.yml` | I10, m4 |
| 18 — Scope `packages: write` to build-and-push job only | `.github/workflows/` | I8 |
| 19 — Compose Postgres → 127.0.0.1; `<CHANGE_ME>` placeholders; rename NIBSS_NPS_BASE_URL→NPS_ENDPOINT; add 6 missing NPS_* vars | `infrastructure/docker-compose.yml`, `.env.example` | I2, m2 |
| 20 — CODEOWNERS (21 paths: infra/security/poms/roles/compliance) | `.github/CODEOWNERS` | m7 |
| 21 — HPA target 70→60, ncube memory 512→768Mi, add ncube HPA, ingress host TODO | `60-ingress.yaml`, HPA overlays | m1 |
| 22 (this session) — Kustomize render validation + Docker build + compose smoke test + docs | Various doc files | — |
| BONUS — SecurityConfig `/actuator/health` → `/actuator/health/**` (engine + ncube) | 2 `SecurityConfig.java` | Bug caught in smoke test |

#### Key Patterns / Decisions

- **Kustomize Components for opt-in cross-cutting concerns** — NetworkPolicy and PDBs as `components/` allow staging/prod to include them while dev stays lean; base manifests remain minimal
- **`kubernetes.io/metadata.name` selectors** — K8s 1.21+ auto-injects this label on Namespace objects so NetworkPolicy egress `namespaceSelector` works without manually applied labels
- **Sentinel image tag `:base-do-not-deploy`** — base manifests use a sentinel that will fail to pull; CI substitutes real SHAs via `kustomize edit set image` per overlay, making accidental prod deploy with stale base impossible
- **All GHA actions pinned to commit SHA** — `@v4` aliases are mutable; SHA pins prevent supply-chain attacks; Dependabot set to weekly digest updates for `github-actions` ecosystem
- **`packages: write` scoped to build-and-push job only** — replaces, not merges with, workflow-level permission; minimises blast radius if the push step is compromised
- **HPA averageUtilization 60%** — JVM workloads exhibit GC-induced CPU spikes; 80% leaves no headroom; 60% triggers scale-out before latency impact
- **SecurityConfig must permit `/actuator/health/**`** — found during smoke test: Spring Boot's `management.endpoint.health.probes.enabled=true` exposes `/actuator/health/readiness` and `/actuator/health/liveness` as sub-paths; `requestMatchers("/actuator/health")` is an exact match that returns 404 for sub-paths; Dockerfile `HEALTHCHECK` + k8s probes both target `/readiness`

#### Build Verification

```
cd ~/nubbank-baas/baas-engine && ./mvnw test
[INFO] Tests run: 97, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

cd ~/nubbank-baas/baas-ncube && ./mvnw test
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

#### Step-by-Step Verification Results

| Step | Result | Notes |
|------|--------|-------|
| Step 1 — kustomize render (all overlays) | ✅ Pass | dev: 12 docs, 0 NP, 0 PDB, 2 HPA; staging/prod: 22 docs, 7 NP, 3 PDB, 2 HPA |
| Step 2 — docker build (engine + ncube) | ✅ Pass | Both exit 0; layers fully cached from prior tasks |
| Step 3 — compose smoke test | ✅ Pass | Both `/actuator/health/readiness` return `{"status":"UP"}` after security fix |

#### Confirmed Platform Versions

**baas-engine (`baas-engine/`):**

| Component | Version | Notes |
|-----------|---------|-------|
| **Spring Boot** | 3.5.0 | Parent BOM |
| **Java** | 21 | LTS |
| **Maven base image** | `maven:3.9-eclipse-temurin-21-alpine@sha256:a24c967778799ee42665a84d9f94e170ae6dc35788c8d2e218071a086b601768` | Build stage |
| **JRE base image** | `eclipse-temurin:21-jre-alpine@sha256:ad0cdd9782db550ca7dde6939a16fd850d04e683d37d3cff79d84a5848ba6a5a` | Runtime stage |
| **Last git commit** | `f102ae0` | Task 21 — HPA/memory tuning; plus security-fix commit this session |

**baas-ncube (`baas-ncube/`):**

| Component | Version | Notes |
|-----------|---------|-------|
| **Spring Boot** | 3.5.0 | No DB, no Redis, no Flyway — pure adapter |
| **Java** | 21 | LTS |
| **Maven base image** | `maven:3.9-eclipse-temurin-21-alpine@sha256:a24c967778799ee42665a84d9f94e170ae6dc35788c8d2e218071a086b601768` | Build stage |
| **JRE base image** | `eclipse-temurin:21-jre-alpine@sha256:ad0cdd9782db550ca7dde6939a16fd850d04e683d37d3cff79d84a5848ba6a5a` | Runtime stage |
| **Last git commit** | `f102ae0` | Task 21 — plus security-fix commit this session |

#### Findings Deferred (per plan, not in-task scope)

| ID | Description | Reason |
|----|-------------|--------|
| I11 | HPA on CPU only, not request-rate/memory | Defer until real load data exists under production traffic |
| m3 | cert-manager / external-dns documentation | Cluster-specific tooling; covered with TODO comments in ingress manifest |
| m6 | Maven cache + GHA cache redundancy | Documentation note only; no functional bug; acceptable trade-off |
| m11 | CI `pull_request` trigger semantics | Documentation-only concern; current behaviour is correct for the branch model |

---

### Session 5 — 2026-05-07
**Phase 1F-0: cross-cutting security baseline — 6 retroactive 1B findings closed (4 critical, 2 important) on `feature/phase1f-0-cross-cutting-security`. Branch HEAD `d8b1802`. Closes 1B C1, C2, C5, I1, I3, I7.**

#### Findings closed
| ID | Description | Resolution |
|----|-------------|-----------|
| 1B C1 | Stub mode could run in production silently | `StubModeGuard` refuses prod profile in stub mode (case-insensitive prefix match); `X-NubBank-Stubbed: true` response header on every stubbed call; stub BVN/NIN return `00000000000` not echoes |
| 1B C2 | `permitAll()` on `/baas/v1/**` left ncube wide open | New `AuthEnforcementFilter` — single config gate; rejects unauthenticated `/baas/v1/**` with 401; new endpoints protected by default |
| 1B C5 | PII could surface in logs at any level | New `PiiMaskingConverter` Logback `ClassicConverter` masks BVN/NIN/NUBAN/PAN; wired in both services via `logback-spring.xml` as `%piimsg`; context-anchored regex (`card`/`pan`/`primary` for PAN; `account`/`nuban`/`from`/`to` for NUBAN) avoids false-positives on Unix epoch timestamps and trace IDs |
| 1B I1 | `/actuator/info` exposed deployment metadata | Removed from public path list; only `/actuator/health` remains permitAll |
| 1B I3 | ncube accepted any media type | All controllers now require `application/vnd.cbn.openbanking.v1+json`; `consumes` is method-level on POST/PUT only (GET/DELETE gated by `Accept` header only); `GlobalExceptionHandler` returns 415/406 |
| 1B I7 | Engine→ncube calls had no inter-service auth | HMAC-SHA256 body-signed scheme: `Authorization: Internal <hmac>` + `X-Internal-Timestamp`; HMAC content `METHOD\|PATH\|TS\|sha256Hex(body)`; 60s replay window; engine signs via `InternalServiceClient` (`@Bean("internalServiceRestTemplate")` RestTemplate); ncube validates via `InternalServiceAuthFilter`; `CachedBodyHttpServletRequest` replays body bytes after filter inspection; 1MB body cap |

#### New Files

| File | Purpose |
|------|---------|
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/AuthEnforcementFilter.java` | Single config gate — 401 on `/baas/v1/**` without auth |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/NcubeRequestContext.java` | ThreadLocal carrying inter-service caller identity (`baas-engine`) |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/InternalServiceAuthFilter.java` | HMAC-SHA256 validator; UTF-8 charset; constant-time hex compare; ≥32-char secret enforced at construction |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/CachedBodyHttpServletRequest.java` | Body cache wrapper — `ContentCachingRequestWrapper` does NOT replay bytes; this one does. `MAX_BODY_BYTES = 1 MB` enforced at read time |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/StubModeGuard.java` | `@PostConstruct` boot guard — refuses stub mode + prod profile combination; case-insensitive prefix match (PROD/Prod/prod-eu/production all trip) |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/StubResponseHeaderInterceptor.java` | `HandlerInterceptor` adds `X-NubBank-Stubbed: true` to every response |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/WebMvcConfig.java` | Wires `StubResponseHeaderInterceptor` into MVC pipeline |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/common/CbnMediaTypes.java` | `CBN_OB = "application/vnd.cbn.openbanking.v1+json"` constant |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/common/PiiMaskingConverter.java` | Logback masker — engine-side copy of identical converter |
| `baas-ncube/src/main/resources/logback-spring.xml` | Wires masker via `%piimsg` conversion word |
| `baas-engine/src/main/java/com/nubbank/baas/engine/config/InternalServiceClient.java` | `@Bean("internalServiceRestTemplate")` — outbound HMAC signer; pre-built `SecretKeySpec`; 5s connect / 30s read timeouts; boot-time HMAC algorithm probe |
| `baas-engine/src/main/java/com/nubbank/baas/engine/common/PiiMaskingConverter.java` | Logback masker — same code as ncube, different package |
| `baas-engine/src/main/resources/logback-spring.xml` | Wires masker via `%piimsg` conversion word |
| `baas-{engine,ncube}/src/test/resources/application-test.yml` | Sets `app.internal-service.shared-secret` for slice/integration tests |
| 9 test classes | `AuthEnforcementFilterTest`, `InternalServiceAuthFilterTest`, `SecurityConfigTest`, `StubModeGuardTest`, `PiiMaskingConverterTest` (×2), `InternalServiceClientTest`, expanded `NcubeIdentityControllerTest` (full-stack `@SpringBootTest(RANDOM_PORT)` with HMAC signing), expanded controller slice tests |

#### Updated Files

| File | Change |
|------|--------|
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/SecurityConfig.java` | Wires `InternalServiceAuthFilter` before `UsernamePasswordAuthenticationFilter`, then `AuthEnforcementFilter`; `permitAll()` removed for `/baas/v1/**`; `/actuator/info` dropped; `FilterRegistrationBean.setEnabled(false)` × 2 to suppress dual auto-registration |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/{consent,identity,payment,account}/*Controller.java` | `@RequestMapping(produces = CBN_OB)` at class level; `consumes = CBN_OB` only on POST/PUT methods (not class-level — would break GET/DELETE under `Accept`-only gating) |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/identity/NcubeIdentityController.java` | Stub BVN/NIN return `00000000000` instead of echoing caller input |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/common/GlobalExceptionHandler.java` | New `@ExceptionHandler(HttpMediaTypeNotSupportedException.class)` → 415; `(HttpMediaTypeNotAcceptableException.class)` → 406 |
| `baas-ncube/src/main/resources/application.yml` | `app.internal-service.shared-secret: ${INTERNAL_SERVICE_SECRET}` (no default — fails fast in prod) |
| `baas-engine/src/main/resources/application.yml` | Same `INTERNAL_SERVICE_SECRET` env var |

#### Key Decisions

- **Body-signed HMAC, not header-only.** Signature includes `sha256Hex(body)` so a tampered body fails validation even if the auth header is intact. Header-only HMAC (signing just URL+method+timestamp) leaves the body fully tamperable.
- **`CachedBodyHttpServletRequest` over Spring's `ContentCachingRequestWrapper`.** Spring's wrapper buffers bytes for `getContentAsByteArray()` but `getInputStream()` still consumes the underlying single-use stream. Two production bugs were caught by the integration test before any signed POST could land in production: (1) controller's `@RequestBody` reader saw an empty stream after the filter read it, (2) `HttpMediaTypeNotSupportedException` propagated as 500 not 415. Both fixed.
- **Stub data → `00000000000`, not echo.** Echoing the caller's input lets a partner's malformed input flow back unmodified — easy to mistake stub for real verification. Constant zero string makes "this is fake" obvious in any log line and removes the trivial echo-PII leak.
- **Context-anchored PII regex, not naked digit-runs.** First-cut `\b\d{13,19}\b` was a Critical defect: it masks every 13-digit Unix-millisecond timestamp (Sleuth/Micrometer trace IDs, `currentTimeMillis()` log lines) and every 10-digit Unix-second timestamp (JWT iat/exp/nbf). Fix requires a context word (`card`/`pan`/`primary` for PAN; `account`/`nuban`/`from`/`to`/`debit`/`credit` for NUBAN) within 16 non-digit chars before. BVN/NIN regex stays simple — 11-digit sequences rarely conflict with timestamps. MDC values, structured args, and exception messages are explicitly scoped out (documented in JavaDoc) — Phase 1F-0 is defence-in-depth on log message bodies only.
- **Case-insensitive prefix-match for prod profile detection.** First-cut `contains("prod")` would miss `PROD`, `Prod`, `prod-eu`, `production`. Operators slip on casing. Use `Arrays.stream().anyMatch(p -> p.toLowerCase(Locale.ROOT).startsWith("prod"))`.
- **`@Bean` name disambiguation for filter chains.** Both `AuthEnforcementFilter` and `InternalServiceAuthFilter` are `@Component`s, so Spring Boot auto-registers them as servlet filters AND we want them only as Spring Security chain filters. Use `FilterRegistrationBean.setEnabled(false)` for each to suppress the auto-registration.
- **`getFilterChains()` for ordering tests, not `getFilters(HttpServletRequest)`.** The ordering regression test uses `FilterChainProxy.getFilterChains()` (public API) rather than `getFilters(HttpServletRequest)` (package-private). Asserting `indexOf` ordering with `isNotNegative()` covers the index-0 case (`isPositive()` would falsely fail for the first filter).
- **`%piimsg` over rebuilding `console-appender.xml`.** Plan code included Spring Boot's `console-appender.xml` then defined a duplicate `CONSOLE_MASKED` appender — produced a harmless but noisy "Appender [CONSOLE] not referenced" boot warning. Drop the include; `defaults.xml` (which we keep) supplies `CONSOLE_LOG_PATTERN`. Also: `class="..."` not deprecated `converterClass="..."` (Logback 1.5+).

#### Known Gotchas (added to CLAUDE.md)

| Symptom | Root cause | Fix |
|---------|-----------|-----|
| `ContentCachingRequestWrapper.getInputStream()` returns empty stream after the filter reads it | Spring's wrapper caches bytes for `getContentAsByteArray()` but does NOT replay them through `getInputStream()` | Implement a `HttpServletRequestWrapper` that overrides `getInputStream()` to return a fresh `ByteArrayInputStream` each call (`CachedBodyHttpServletRequest` pattern) |
| `Pattern.compile("\\b\\d{13,19}\\b")` masks Unix-millisecond timestamps | `\b` matches at any word/non-word boundary; 13-digit ms timestamps are everywhere in JVM logs | Require a context anchor: `(?<=(?:card\|pan\|primary)[^\\d]{0,16})(\\d{4})...` — bounded lookbehind supported on Java 9+ |
| Stub mode silently active in prod when profile name is `PROD` not `prod` | `String.contains("prod")` is case-sensitive | `Arrays.stream(profiles).anyMatch(p -> p.toLowerCase(Locale.ROOT).startsWith("prod"))` — catches `PROD`, `Prod`, `prod-eu`, `production` |
| Filter is in the security chain AND auto-registered as a servlet filter | `@Component` filters are auto-registered by Spring Boot servlet auto-config | `@Bean FilterRegistrationBean<X> disableX(...)` returning `setEnabled(false)` — keeps the filter out of the servlet pipeline; security chain alone routes it |
| Class-level `@RequestMapping(consumes = ...)` rejects GET requests | Spring inherits class-level `consumes` to all methods including GET; partner GET with only `Accept` header gets 415 | Move `consumes` to method-level on POST/PUT only; keep `produces` at class level for response content negotiation |
| `HttpMediaTypeNotSupportedException` propagates as 500 | No matching `@ExceptionHandler` in `GlobalExceptionHandler` → falls through to default 500 | Add `@ExceptionHandler` for `HttpMediaTypeNotSupportedException` (415) and `HttpMediaTypeNotAcceptableException` (406) |

#### Build Verification

```
cd ~/nubbank-baas/baas-engine && ./mvnw test
[INFO] Tests run: 97, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

cd ../baas-ncube && ./mvnw test
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Engine: 84 (Session 4) + 13 new (`InternalServiceClientTest`, `PiiMaskingConverterTest` — 10 tests) = **97 tests**.
Ncube: 21 (Session 2) + 28 new (`AuthEnforcementFilterTest`, `InternalServiceAuthFilterTest`, `SecurityConfigTest`, `StubModeGuardTest`, `PiiMaskingConverterTest`, expanded controller tests) = **49 tests**.

Smoke test of the full chain (engine → ncube with HMAC, stubbed header, masked log) deferred to manual verification post-merge — requires running the full Docker Compose stack which is out of scope for this branch's CI.

#### Confirmed Platform Versions

`baas-engine/`:

| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | `d8b1802` |
| Java | 21 | `d8b1802` |
| Spring Security | 6.5.0 (managed) | `d8b1802` |
| Logback | 1.5.x (managed by Spring Boot 3.5) | `d8b1802` |
| Last git commit | `d8b1802` | Branch `feature/phase1f-0-cross-cutting-security` |

`baas-ncube/`:

| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | `d8b1802` |
| Java | 21 | `d8b1802` |
| Spring Security | 6.5.0 (managed) | `d8b1802` |
| Logback | 1.5.x (managed by Spring Boot 3.5) | `d8b1802` |
| Last git commit | `d8b1802` | Branch `feature/phase1f-0-cross-cutting-security` |

#### Process notes (Subagent-Driven Development)

Plan executed strictly per task with implementer + spec reviewer + code-quality reviewer per task. Three review-cycle fixes worth flagging:
- **Task 9.5** caught two production bugs via the new integration test before any signed POST could ship — vindicating the cost of a full-stack `@SpringBootTest(RANDOM_PORT)` over slice tests.
- **Task 10** code review found a Critical false-positive issue in the first-cut PII regex (timestamps mangled). Fixed with context-anchored lookbehind + 4 new pinning tests.
- **Task 11** code review caught two non-fatal Logback boot warnings the implementer flagged. Fixed by dropping a redundant include and switching `converterClass` → `class`.

#### What's Next (Session 6)

- Open PR for `feature/phase1f-0-cross-cutting-security` against main; squash-merge after review
- Resume Phase 1A / 1B follow-on plans (A, B in parallel; then E)
- Phase 1C — `baas-backoffice` (React/Vite operations portal)

---

### Session 4 — 2026-05-03
**PR #3 review cycle: 12 critical + 6 important security findings fixed; merged to main as squash commit `5adeb10`.**

#### Review cycle

| Round | Outcome |
|-------|---------|
| Initial review | 12 critical, 9 important, 6 minor — BLOCK MERGE |
| First fix round | C3, C5, C7–C12 + AuthEnforcementFilter (C1+C2) + 2FA lockout (C6) |
| Re-review | New criticals: C9 (events never published), C4 (PII coverage incomplete) + 4 importants |
| Second fix round | Wired ApplicationEventPublisher, expanded PII to ClientIdentifier+ClientAddress, atomic UPDATE for OTP race, ObjectMapper audit JSON, @Profile(test) for testOtpStore, removed DB credential defaults |
| Final review | **APPROVED FOR MERGE** — no regressions, no new criticals |

#### New Files

| File | Purpose |
|------|---------|
| `baas-engine/src/main/java/com/nubbank/baas/engine/config/AuthEnforcementFilter.java` | C1+C2 — single config gate for `/baas/v1/**`; replaces brittle per-service `requireContext()` discipline |
| `baas-engine/src/main/java/com/nubbank/baas/engine/common/FieldEncryptor.java` | C4 — JPA AttributeConverter, AES-GCM-256 with fresh IV per save (semantic security), SHA-256 key derivation |
| `baas-engine/src/main/java/com/nubbank/baas/engine/common/TenantJdbcTemplate.java` | C3 — wraps JdbcTemplate, validates `^(?:partner\|sandbox)_[0-9a-f]{32}$` schema before SET search_path |
| `baas-engine/src/main/java/com/nubbank/baas/engine/audit/AuditAspect.java` | C11 — Spring AOP intercepts every `@Transactional(readOnly=false)` `*Service.*` method; audits both success and failure paths; ObjectMapper for JSON encoding |
| `baas-engine/src/main/java/com/nubbank/baas/engine/cob/CobJobExecutor.java` | C7 — separate Spring bean so `@Transactional` on CoB jobs is intercepted via proxy (private/self-ref methods bypass AOP silently) |
| `baas-engine/src/main/java/com/nubbank/baas/engine/twofa/TwoFactorTokenWriter.java` | C6 — `@Transactional(REQUIRES_NEW)` writer so failed-attempt counter survives the rollback caused by INVALID_OTP exception |
| `baas-engine/src/main/java/com/nubbank/baas/engine/twofa/TestOtpStore.java` | IMPORTANT-4 — `@Profile("test")` plaintext OTP store; absent in production memory |
| `baas-engine/src/test/java/com/nubbank/baas/engine/security/SecurityBoundariesTest.java` | 6 boundary tests: missing/invalid auth → 401, OTP lockout, SQL injection block, cross-tenant isolation |
| `baas-engine/src/test/java/com/nubbank/baas/engine/security/PiiEncryptionTest.java` | Verifies row on disk is ciphertext (raw JdbcTemplate read), round-trip decrypt, IV freshness |

#### Updated Files

| File | Change |
|------|--------|
| `baas-engine/src/main/java/com/nubbank/baas/engine/customer/Customer.java` | `@Convert(FieldEncryptor.class)` on 6 PII fields |
| `baas-engine/src/main/java/com/nubbank/baas/engine/clientext/ClientIdentifier.java` | `@Convert(FieldEncryptor.class)` on `documentKey`; column 200 → 500 |
| `baas-engine/src/main/java/com/nubbank/baas/engine/clientext/ClientAddress.java` | `@Convert(FieldEncryptor.class)` on `street`/`city`/`postalCode`; columns widened proportionally |
| `baas-engine/src/main/java/com/nubbank/baas/engine/twofa/TwoFactorService.java` | Constant-time hash compare, `Optional<TestOtpStore>` injection, no plaintext map in production |
| `baas-engine/src/main/java/com/nubbank/baas/engine/twofa/TwoFactorTokenRepository.java` | Native `incrementFailedAttempts(id, max)` UPDATE (atomic at row level — closes race) |
| `baas-engine/src/main/java/com/nubbank/baas/engine/loan/LoanService.java` | `eventPublisher.publishEvent(LoanApprovedEvent)` in `approve()`, `LoanDisbursedEvent` in `disburse()` |
| `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java` | `eventPublisher.publishEvent(AccountOpenedEvent)` in `open()` |
| `baas-engine/src/main/java/com/nubbank/baas/engine/payment/PaymentService.java` | `eventPublisher.publishEvent(PaymentCompletedEvent)` in `transfer()` |
| `baas-engine/src/main/java/com/nubbank/baas/engine/notification/NotificationService.java` | `@EventListener` → `@TransactionalEventListener(AFTER_COMMIT)` on all 4 handlers |
| `baas-engine/src/main/java/com/nubbank/baas/engine/social/MakerCheckerService.java` | Checker derived from JWT `sub`, NOT request param; SoD check (maker ≠ checker) |
| `baas-engine/src/main/java/com/nubbank/baas/engine/compliance/ComplianceService.java` | `@PostConstruct` refuses `production` profile without `app.compliance.allow-stub=true` |
| `baas-engine/src/main/java/com/nubbank/baas/engine/batch/BatchApiController.java` | `catch (Exception)` → `catch (RestClientException \| IllegalArgumentException)` |
| `baas-engine/src/main/java/com/nubbank/baas/engine/cob/CobService.java` | Delegates job execution to `CobJobExecutor` (proxy boundary) |
| `baas-engine/src/main/java/com/nubbank/baas/engine/config/SecurityConfig.java` | Wires `AuthEnforcementFilter` between `PartnerContextFilter` and `RateLimitFilter` |
| `baas-engine/src/main/java/com/nubbank/baas/engine/tenant/PartnerContext.java` | Added 6th field `userId` (extracted from JWT `sub` claim by `PartnerJwtService.validate()`) |
| `baas-engine/src/main/resources/application.yml` | Removed defaults for `JWT_SECRET`, `ENCRYPTION_KEY`, `DATASOURCE_URL`/`USERNAME`/`PASSWORD` — production fails fast on missing env vars |
| `baas-engine/src/main/resources/db/migration/tenant/V2__modules_extension.sql` | `client_identifiers.document_key` 200 → 500; `client_addresses` columns widened; `two_factor_tokens` adds `failed_attempts`/`locked` |

#### Key Decisions

- **Security at the filter chain, not at the service layer.** The pre-fix pattern relied on every service method calling `requireContext()`. New endpoints would silently be public. Switched to `AuthEnforcementFilter` — single config gate; new services protected by default.
- **AOP for audit, not manual wiring.** `AuditAspect` intercepts every `@Transactional(readOnly=false)` `*Service.*` method. New services get audited automatically — no per-method discipline.
- **PII encryption with SHA-256 KDF, not PBKDF2/Argon2.** `app.encryption.key` is supposed to be a high-entropy secret from Vault/SSM, not a password. PBKDF2/Argon2 are designed to slow brute-force on low-entropy human passwords; single-pass SHA-256 to derive a 256-bit AES key is correct here.
- **`@Profile("test")` beans for test-only side channels.** The plaintext OTP store is never loaded in production; even a `@SpringBootTest` accidentally activated under a non-test profile cannot retrieve plaintext OTPs.
- **Atomic UPDATE for OTP lockout race.** Counter increment computed in the SET clause: `failed_attempts = failed_attempts + 1, locked = (failed_attempts + 1 >= :max)`. PostgreSQL evaluates SET against pre-update row, so the boolean correctly determines post-update state. No `@Version` needed.
- **REQUIRES_NEW for any counter that must survive rollback.** OTP failed-attempt counter, audit log, and notifications-via-AFTER_COMMIT all use this pattern. Without it, the JPA rollback caused by throwing `BaasException` resets the counter to 0 and the brute-force lockout never engages.

#### Known Gotchas (added to CLAUDE.md)

| Symptom | Root cause | Fix |
|---------|-----------|-----|
| `@Transactional` on a private method silently does nothing | Spring AOP proxies don't intercept private method calls or self-references (`this::method`) | Extract to a separate `@Service` bean and inject |
| Counter increment doesn't persist after exception | Caller's `@Transactional` rolls back the increment too | Move the write to a separate bean with `@Transactional(REQUIRES_NEW)` |
| `JdbcTemplate` doesn't see tenant data | Hibernate's `MultiTenantConnectionProvider` only routes Hibernate sessions; raw JDBC bypasses it | Use `TenantJdbcTemplate` which sets `SET search_path` per query |
| Schema name in raw SQL is an injection vector | Identifiers can't be parameter-bound in SQL | Validate against a strict regex before interpolation; never accept arbitrary input |
| PostgreSQL JSONB column rejects bound `varchar` | Driver binds Strings as `character varying` | `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6 native) — no third-party library |
| Spring `@EventListener` fires before commit | Default phase is "as soon as published" | `@TransactionalEventListener(phase = AFTER_COMMIT)` for side-effects that must be skipped on rollback |

#### Build Verification

```
cd ~/nubbank-baas/baas-engine && ./mvnw test
[INFO] Tests run: 84, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

74 (Phase 1A-ext modules) + 6 (security boundaries) + 3 (PII encryption) + 1 (maker-checker SoD) = **84 tests**.

#### Confirmed Platform Versions

`baas-engine/`:

| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | `5adeb10` |
| Java | 21 | `5adeb10` |
| Spring AOP (new dependency) | 3.5.0 (managed) | `5adeb10` |
| Hibernate | 6.x (Spring Boot 3.5.0 default) | `5adeb10` |
| PostgreSQL | 16 (Docker / Testcontainers) | `5adeb10` |
| Last git commit | `5adeb10` | Squash merge of PR #3 |

#### Deployment infrastructure (Phase 1E — completed in this session)

- `baas-engine/Dockerfile` — multi-stage Maven build → Eclipse Temurin JRE 21 Alpine; non-root `app` user; healthcheck on `/actuator/health`. Build verified locally — image fails fast on missing `JWT_SECRET`/`ENCRYPTION_KEY`/`DATASOURCE_*` env vars (correct production behaviour).
- `baas-ncube/Dockerfile` — same pattern; healthcheck on port 8081.
- `infrastructure/docker-compose.yml` + `.env.example` — local / on-prem stack: postgres + baas-engine + baas-ncube. Plain Compose syntax (works with Docker / Podman / nerdctl).
- `infrastructure/k8s/` — vanilla Kubernetes manifests: namespace, secret template, configmap, postgres StatefulSet, baas-engine Deployment + Service + HPA, baas-ncube Deployment + Service, generic Ingress (no provider-specific annotations). README explains the overlay pattern for cloud-specific config.
- `.github/workflows/baas-engine-ci.yml`, `.github/workflows/baas-ncube-ci.yml` — test on every PR/push, push image to GHCR on main. CI has NO deploy step — deployment is target-cluster's responsibility (kubectl/Helmfile/ArgoCD), keeping the build deployment-agnostic.

The build is now genuinely portable: same image runs on Docker Desktop, k3s, EKS, GKE, AKS, on-prem k8s, or any OCI-compatible runtime. No cloud-provider lock-in.

#### Figma boards updated

All boards refreshed via the Figma Plugin API on 2026-05-03 (after the squash merge of PR #3) to reflect the new Session 4 components. Node IDs recorded for audit trail.

- **[Service Architecture](https://www.figma.com/board/PRACgc6BXsGVEL7ZhB866A)** — Security & Gateway section widened 608 → 896 px; added `AuthEnforcementFilter` as the third filter tile (node `8:73`) and a new connector `8:77` from `RateLimitFilter` → `AuthEnforcementFilter`; the three existing connectors `1:59`, `1:63`, `1:67` (RateLimit → engine/card/ncube) were redirected to originate from `AuthEnforcementFilter` since it is the last gate before controllers. New section **`baas-engine Internals (Session 4)`** (node `9:78`) added below the security row with 6 tiles in a 3×2 grid: `AuditAspect`, `FieldEncryptor`, `TenantJdbcTemplate`, `CobJobExecutor`, `TwoFactorTokenWriter`, `TestOtpStore` (nodes `9:79`, `9:83`, `9:87`, `9:91`, `9:95`, `9:99`).
- **[Multi-Tenancy Flow](https://www.figma.com/board/TR1AYhx9Pcmd5y5grxMv8v)** — Added `TenantJdbcTemplate` node (`5:55`) below the main Hibernate rail with two labelled connectors: `5:59` from `PartnerContext` ("Raw JDBC path") and `5:63` into `partner_abc schema` ("SET search_path TO partner_abc, public"). Both rails converge on the same per-partner schema, making it explicit that raw JDBC bypasses Hibernate's `MultiTenantConnectionProvider` but still respects schema isolation via the regex-validated `SET search_path`.
- **[CBN Compliance Roadmap](https://www.figma.com/board/5KpYYAtiukv7G6o3LyjsVr)** — Phase 1A — Complete section grew 1568 → 1760 px tall and a new tile (`5:48`) was added: **PII Encryption at Rest / AES-GCM-256 / FieldEncryptor JPA Converter**. Reflects NDPR §9.2 moving from ⚠️ to ✅ (the gap-analysis doc was already updated in this session).
- **[Partner Provisioning Flow](https://www.figma.com/board/qHD6cSCRTQHPbkmavHtoxw)** — No change required; provisioning flow is unaffected by Session 4.

#### What's Next (Session 5)

- `baas-backoffice` (Phase 1C) — React/Vite operations portal
- `baas-portal` (Phase 1D) — React/Vite developer portal for partner self-service
- Phase 2 — real BVN/NIN verification via Ncube, consent registry sync, Apache Santuario XML signing

---

### Session 3 — 2026-05-02
**Phase 1A-ext: all missing baas-engine modules added (29 tasks, 74 tests, BUILD SUCCESS, branch `feature/phase1a-ext-engine` pushed).**

#### Modules added (Tasks 1–29)

| # | Module | Package | Tests |
|---|--------|---------|-------|
| 1 | V2 tenant schema migration (70 tables) | `db/migration/tenant/V2` | — |
| 2 | Loan + Deposit Products | `product/` | 3 |
| 3 | Fixed + Recurring Deposits | `deposit/` | 2 |
| 4 | Share Products + Accounts | `share/` | 1 |
| 5 | Charges | `charge/` | 2 |
| 6 | Loans core lifecycle | `loan/` | shared |
| 7 | Loan extensions (guarantors/collateral/reschedule) | `loan/` | 1 |
| 8 | GL Accounting | `accounting/` | shared |
| 9 | Accounting Rules + Provisioning | `accounting/` | 1 |
| 10 | Teller / Cash Management | `teller/` | 2 |
| 11 | Office + Staff | `office/` | 1 |
| 12 | Groups + Centers | `group/` | 2 |
| 13 | System Configuration | `system/` | 3 |
| 14 | Floating Rates + Taxes | `rate/` | 1 |
| 15 | Roles + Permissions (with `PartnerContext.userId` from JWT sub) | `role/` | 1 |
| 16 | Client Identifiers + Addresses + Images | `clientext/` | 1 |
| 17 | Notes + Documents (polymorphic) | `social/` | 1 |
| 18 | Maker-Checker + DataTables | `social/` | 1 |
| 19 | Open Banking Consents | `openbanking/` | 2 |
| 20 | Audit Log Service + AOP aspect (covers ALL services) | `audit/` | 1 |
| 21 | Notifications (Spring async events) | `notification/` | 1 |
| 22 | SMS Campaigns + Report Mailing Jobs | `campaign/` | 2 |
| 23 | Standing Instructions + Beneficiaries | `standing/` | 2 |
| 24 | Two-Factor Authentication (HMAC-SHA256 OTP) | `twofa/` | 2 |
| 25 | Credit Bureau (stub) + PPI Surveys | `bureau/` + `survey/` | 2 |
| 26 | Compliance Module (sanctions screening) | `compliance/` | 2 |
| 27 | CoB Scheduler (nightly @Scheduled jobs) | `cob/` | 2 |
| 28 | Reports Module (SQL engine) + `TenantJdbcTemplate` | `report/` + `common/` | 3 |
| 29 | Global Search + Batch API | `search/` + `batch/` | 1 |

#### Architectural decisions

- **`PartnerContext.userId`** added (6th field) — extracted from JWT `sub` claim by `PartnerJwtService.validate()`. Propagates real user identity into audit logs at live deployment, not the org ID.
- **`AuditAspect`** intercepts all `@Transactional` (non-readOnly) `*Service` methods system-wide. New services get audited automatically — no manual wiring per service.
- **`TenantJdbcTemplate`** (`common/`) — wraps `JdbcTemplate` and sets `SET search_path TO <tenant>, public` per query. Required because Hibernate's `MultiTenantConnectionProvider` only routes Hibernate sessions; raw JDBC bypasses it. Used by `ReportService` and `GlobalSearchController`.
- **`@JdbcTypeCode(SqlTypes.JSON)`** for JSONB columns (`open_banking_consents.scopes`, `notification_events.payload`) — Hibernate 6 native, no third-party library needed.
- **`cob_job_history`** moved to `public/` schema — system-wide audit, not per-tenant. Tenant search_path order (`tenant, public`) makes it findable from any context.
- **Word-boundary regex** for SQL keyword blocklist — `CREATE` no longer false-matches `created_at`.
- **Lombok `@Builder.Default`** on every initialized collection field — prevents `NullPointerException` when builder is used.
- **`@JsonIgnore`** on every lazy `@ManyToOne` back-reference — prevents Jackson serialization errors outside the Hibernate session.

#### New Files (180+ Java + 1 SQL migration + 1 plan)

See commits `e8cd292` → `bb0eb6c` on `feature/phase1a-ext-engine`.

#### Build Verification

```
cd ~/nubbank-baas/baas-engine && ./mvnw test
[INFO] Tests run: 74, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Branch pushed: `feature/phase1a-ext-engine` (32 commits ahead of `main`).

#### What's Next (Session 4)

- `baas-backoffice` (Phase 1C) — React/Vite operations portal consuming all the new endpoints
- `baas-portal` (Phase 1D) — React/Vite developer portal for partner self-service
- `baas-engine` Phase 2 — wire real BVN/NIN verification, Ncube consent registry sync, Apache Santuario XML signing

---

### Session 2 — 2026-04-27
**Phase 1B: baas-ncube service — CBN Open Banking adapter + NIBSS NPS ISO 20022 gateway (commits `1d8eb9d` → `97544ce`).**

#### New Files (30 total)

| File | Change |
|------|--------|
| `baas-ncube/pom.xml` | NEW — Spring Boot 3.5, Java 21, no DB/Redis/Flyway |
| `baas-ncube/src/main/java/.../config/SecurityConfig.java` | NEW — permit-all, stateless |
| `baas-ncube/src/main/java/.../config/BaasEngineClientConfig.java` | NEW — RestTemplate for baas-engine calls |
| `baas-ncube/src/main/java/.../common/` (5 files) | NEW — CbnApiResponse, CbnLinks, CbnMeta, CbnAmount, NcubeException, GlobalExceptionHandler |
| `baas-ncube/src/main/java/.../account/dto/` (6 files) | NEW — NubBankAccountDto, NubBankTransactionDto, CbnAccountItem, CbnAccountScheme, CbnBalanceItem, CbnTransactionItem |
| `baas-ncube/src/main/java/.../account/NcubeAccountClient.java` | NEW — calls baas-engine, transforms to CBN format |
| `baas-ncube/src/main/java/.../account/NcubeAccountController.java` | NEW — GET /baas/v1/ncube/accounts, /balances, /transactions |
| `baas-ncube/src/main/java/.../consent/dto/` (3 files) | NEW — NubBankConsentDto, CbnConsentItem, CbnConsentRequest |
| `baas-ncube/src/main/java/.../consent/NcubeConsentClient.java` | NEW — calls baas-engine consent endpoints |
| `baas-ncube/src/main/java/.../consent/NcubeConsentController.java` | NEW — GET/POST/DELETE /baas/v1/ncube/consents |
| `baas-ncube/src/main/java/.../payment/dto/` (2 files) | NEW — NipPaymentRequest, NipPaymentResponse |
| `baas-ncube/src/main/java/.../payment/nps/` (9 files) | NEW — Pacs008Message, Acmt023Message, Acmt024Response, Pacs002Response, NpsXmlBuilder, NpsXmlParser, NpsMessageSigner, NpsMessageEncryptor, NpsHttpClient + 3 stub impls |
| `baas-ncube/src/main/java/.../payment/NipPaymentOrchestrator.java` | NEW — two-step: acmt.023 Name Enquiry → pacs.008 Credit Transfer |
| `baas-ncube/src/main/java/.../payment/NcubePaymentController.java` | NEW — POST /baas/v1/ncube/payments/nip |
| `baas-ncube/src/main/java/.../identity/dto/` (3 files) | NEW — BvnVerificationRequest, NinVerificationRequest, VerificationResponse |
| `baas-ncube/src/main/java/.../identity/NcubeIdentityController.java` | NEW — POST /baas/v1/ncube/identity/verify-bvn, verify-nin |

#### Key Decisions

1. **baas-ncube has no database** — pure adapter; all data from baas-engine; no Flyway/Redis/PostgreSQL
2. **Stub interfaces with `@ConditionalOnProperty`** — `NpsMessageSigner`, `NpsMessageEncryptor`, `NpsHttpClient` all have stubs active by default; Phase 2 replaces by setting `baas.nps.signing.enabled=true`, `baas.nps.encryption.enabled=true`, `baas.nps.live=true`
3. **Two-step NIP flow mandatory** — `NipPaymentOrchestrator` always sends `acmt.023` (Name Enquiry) BEFORE `pacs.008` (Credit Transfer); unverified beneficiary throws `NcubeException` before any payment is attempted
4. **ISO 20022 XML namespace versions** — `pacs.008.001.12` and `acmt.023.001.04` (latest NIBSS NPS v1.2 spec)
5. **BVN in SplmtryData (both debtor AND creditor)** — `pacs.008` includes Nigerian-specific `<SplmtryData>` with BVN, AccountTier, AccountDesignation for both parties, and `<NameEnquiryMsgId>` from acmt.024
6. **NipPaymentOrchestrator constructor** — explicit `@Autowired` constructor (not Lombok) because Spring Boot's `@Value` field injection is incompatible with `@RequiredArgsConstructor` when there are multiple constructors
7. **CBN status mapping** — `AWAITING_AUTHORISATION` → `AwaitingAuthorisation`, `AUTHORISED` → `Authorised` (UK Open Banking v3.1 casing per CBN guidelines)

#### Build Verification

```
Tests run: 21, Failures: 0, Errors: 0 — BUILD SUCCESS
Smoke test: health=UP, BVN verify=NIBSS_NCUBE_STUB, NIP payment=COMPLETED (stub NPS)
```

#### Confirmed Platform Versions

**BaaS Ncube (`baas-ncube/`):**

| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | `97544ce` |
| Java | 21 | `97544ce` |
| Lombok | 1.18.38 | `97544ce` |
| springdoc-openapi | 2.8.6 | `97544ce` |
| NPS spec version | v1.2 (pacs.008.001.12, acmt.023.001.04) | `97544ce` |
| Last git commit | `97544ce` | Session 2 — Phase 1B complete |

#### What's Next (Session 3)

Phase 1C: `baas-backoffice` — React 19 + Vite shell (auth, dashboard, customers, accounts)
Phase 1D: `baas-portal` — React 19 + Vite developer portal shell
Phase 1E: Infrastructure — Docker Compose + CI/CD pipelines

---

### Session 1 — 2026-04-27
**Phase 1A foundation: baas-engine scaffolded with multi-tenancy, partner auth, provisioning, and request routing (commits `68b3403` → `6e5b816`).**

#### New/Updated Files

| File | Change |
|------|--------|
| `baas-engine/pom.xml` | NEW — Spring Boot 3.5.0, Java 21, all Phase 1A dependencies |
| `baas-engine/src/main/java/com/nubbank/baas/engine/BaasEngineApplication.java` | NEW — Entry point |
| `baas-engine/src/main/resources/application.yml` | NEW — Full config with env-var defaults |
| `baas-engine/src/test/resources/application-test.yml` | NEW — Test profile (no Redis, test JWT secrets) |
| `baas-engine/src/main/java/.../common/ApiResponse.java` | NEW — `{ data, meta, errors }` envelope |
| `baas-engine/src/main/java/.../common/BaasException.java` | NEW — Domain exception with HTTP status + 5 factory methods |
| `baas-engine/src/main/java/.../common/GlobalExceptionHandler.java` | NEW — `@RestControllerAdvice` (with defensive FieldError fallback) |
| `baas-engine/src/main/resources/db/migration/public/V1__public_schema.sql` | NEW — 9 platform tables, 8 indexes, 10k NUBAN seed |
| `baas-engine/src/main/resources/db/migration/tenant/V1__tenant_schema.sql` | NEW — 8 per-partner tables, 8 indexes |
| `baas-engine/src/main/java/.../tenant/PartnerContext.java` | NEW — ThreadLocal record; `set/get/clear/isSandbox` |
| `baas-engine/src/main/java/.../tenant/PartnerTenantResolver.java` | NEW — `CurrentTenantIdentifierResolver<String>` |
| `baas-engine/src/main/java/.../tenant/PartnerSchemaProvider.java` | NEW — `MultiTenantConnectionProvider<String>`; validates schema name; `SET search_path` |
| `baas-engine/src/main/java/.../tenant/MultiTenantConfig.java` | NEW — `HibernatePropertiesCustomizer` wires multi-tenancy |
| `baas-engine/src/main/java/.../tenant/TenantProvisioningService.java` | NEW — CREATE SCHEMA + Flyway per-tenant runner + sandbox schema |
| `baas-engine/src/main/java/.../tenant/PartnerContextFilter.java` | NEW — `OncePerRequestFilter`; API key + JWT resolution; `finally { clear() }` |
| `baas-engine/src/main/java/.../partner/PartnerOrganization.java` | NEW — `@Table(schema="public")` entity |
| `baas-engine/src/main/java/.../partner/PartnerUser.java` | NEW — `@Table(schema="public")` entity |
| `baas-engine/src/main/java/.../partner/PartnerApiKey.java` | NEW — `@Table(schema="public")` entity (scopes as JSON string) |
| `baas-engine/src/main/java/.../partner/Partner*.java` (enums + repos) | NEW — 3 enums + 3 repositories |
| `baas-engine/src/main/java/.../auth/PartnerJwtService.java` | NEW — HMAC-SHA256 JWT issue + validate (Nimbus JOSE+JWT) |
| `baas-engine/src/main/java/.../auth/AuthController.java` | NEW — `POST /baas/v1/auth/register` + `/login` |
| `baas-engine/src/main/java/.../auth/dto/*.java` | NEW — RegisterRequest, LoginRequest, AuthResponse |
| `baas-engine/src/main/java/.../config/SecurityConfig.java` | NEW — Permit-all, stateless, BCrypt(12), PartnerContextFilter registered |
| `baas-engine/src/test/java/.../PartnerContextTest.java` | NEW — 4 unit tests |
| `baas-engine/src/test/java/.../PartnerJwtServiceTest.java` | NEW — 4 unit tests |
| `baas-engine/src/test/java/.../AbstractIntegrationTest.java` | NEW — Testcontainers PostgreSQL 16 base class |
| `baas-engine/src/test/java/.../TenantProvisioningTest.java` | NEW — 2 integration tests (schema creation + data isolation) |

#### Key Decisions

1. **Schema isolation via Hibernate SCHEMA strategy** — `SET search_path` enforced at PostgreSQL level, not application level. A query bug cannot cross schema boundaries.

2. **Public schema entities need `@Table(schema="public")`** — Without this, Hibernate routes public table queries through `PartnerSchemaProvider` which applies the partner `search_path`. Tables like `partner_organizations` don't exist in partner schemas → runtime failure.

3. **`PartnerContext.clear()` uses `HOLDER.remove()`** — `set(null)` leaves the ThreadLocal entry alive in thread pool threads. `remove()` is the correct cleanup.

4. **`@Modifying` + `@Transactional` required together** — `updateLastUsed` in `PartnerApiKeyRepository` needs both. `@Modifying` alone throws `TransactionRequiredException` when called from a non-transactional context (e.g., a filter).

5. **Testcontainers + Docker Desktop 4.x** — API version negotiation fails without `api.version=1.41` in Surefire `systemPropertyVariables`. This is portable: Linux CI Docker Engine also accepts v1.41.

6. **`schema_provision_log` FK constraint in tests** — Tests must insert a real `PartnerOrganization` row before calling `provision()` to satisfy the FK. Random UUIDs fail the constraint.

7. **NUBAN SQL check digit** — `CAST(expr % 10 AS TEXT)` is ambiguous in PostgreSQL (`AS TEXT` parsed as column alias). Fixed to `((expr % 10))::TEXT`.

8. **`Instant` in JdbcTemplate** — PostgreSQL JDBC cannot infer SQL type for `java.time.Instant`. Use `java.sql.Timestamp.from(instant)`.

#### Build Verification

```
Tests run: 10 total
  PartnerContextTest (unit):       4/4 ✅
  PartnerJwtServiceTest (unit):    4/4 ✅
  TenantProvisioningTest (IT):     2/2 ✅
BUILD SUCCESS
```

#### Additional Files Created (Tasks 10–16 + smoke test fixes)

| File | Change |
|------|--------|
| `baas-engine/src/main/java/.../virtualaccount/VirtualAccountPool.java` | NEW — `@Table(schema="public")` entity |
| `baas-engine/src/main/java/.../virtualaccount/VirtualAccountRepository.java` | NEW — `@Lock(PESSIMISTIC_WRITE)` query |
| `baas-engine/src/main/java/.../virtualaccount/VirtualAccountService.java` | NEW — atomic NUBAN assignment |
| `baas-engine/src/main/java/.../customer/Customer.java` (+ enums, dto, repo, service, controller) | NEW — full customer module |
| `baas-engine/src/main/java/.../account/Account.java` (+ Transaction, enums, dto, repo, service, controller) | NEW — full account module |
| `baas-engine/src/main/java/.../payment/Payment.java` (+ enums, dto, repo, service, controller) | NEW — internal transfer + idempotency |
| `baas-engine/src/main/java/.../sandbox/SandboxService.java` + `SandboxController.java` | NEW — simulate deposit, schema reset |
| `baas-engine/src/main/java/.../config/RateLimitService.java` + `RateLimitFilter.java` | NEW — Redis Lua INCR+EXPIRE, fail-open |
| `baas-engine/src/main/resources/application.yml` | UPDATED — `ddl-auto: none` (validate breaks multi-tenant) |
| `baas-engine/src/main/resources/db/migration/public/V1__public_schema.sql` | UPDATED — `partner_api_keys.updated_at` column added |
| `baas-engine/src/test/java/.../AbstractIntegrationTest.java` | UPDATED — static initializer (not `@Container`) for suite-wide container reuse |

#### Key Decisions (Session 1 Complete)

1. **Schema isolation via Hibernate SCHEMA strategy** — `SET search_path` enforced at PostgreSQL level, not application level.
2. **Public schema entities need `@Table(schema="public")`** — Without this, Hibernate routes queries to partner schema where tables don't exist.
3. **`PartnerContext.clear()` uses `HOLDER.remove()`** — `set(null)` leaks ThreadLocal entries in thread pools.
4. **`@Modifying` requires `@Transactional`** — discovered on `updateLastUsed`.
5. **`ddl-auto: none` required** — `validate` breaks because tenant tables don't exist in public schema.
6. **`@ConditionalOnBean` doesn't work on user `@Service`** — use `@Autowired(required = false)` instead.
7. **Testcontainers static initializer** — `@Container` stops the container between test classes, killing HikariPool. Static block starts it once.
8. **Deadlock-safe UUID ordering** — `PaymentService` always locks the lower UUID first.
9. **Idempotency check before locks** — `findByIdempotencyKey()` checked before `PESSIMISTIC_WRITE` lock acquisition.
10. **Sandbox always resets `sandbox_` schema** — never `partner_` schema, even with a production JWT.

#### Build Verification

```
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
Live smoke test: health=UP, register=✅, customer=✅, account=✅, rate-limit-headers=✅
```

#### Confirmed Platform Versions

**BaaS Engine (`baas-engine/`):**

| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | `c6c5e47` |
| Java | 21 | `c6c5e47` |
| Hibernate | 6.x (managed) | `c6c5e47` |
| Flyway | 10.x (managed) | `c6c5e47` |
| Nimbus JOSE+JWT | 9.37.3 | `c6c5e47` |
| Lombok | 1.18.38 | `c6c5e47` |
| Testcontainers | 1.20.1 | `c6c5e47` |
| Last commit | `c6c5e47` | Session 1 — Phase 1A complete |

#### What's Next (Session 2)

Phase 1B: `baas-ncube` — CBN format adapter + BVN/NIN verification
Phase 1C: `baas-backoffice` — React shell (auth, dashboard, customers, accounts)
Phase 1D: `baas-portal` — React developer portal shell
Phase 1E: Infrastructure — Docker Compose + CI/CD pipelines
