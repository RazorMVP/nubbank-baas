# NubBank BaaS — Build Log

> Tracks all implementation work, decisions, and changes for the NubBank BaaS platform.
> Updated at the end of every session. Newest entries at the top.

---

## Build Status — Current State

| Sub-system | Status | Last Session |
|------------|--------|-------------|
| `baas-engine` — Phase 1A | ✅ Complete (all 16 tasks, 23 tests passing, smoke test live) | Session 1 |
| `baas-ncube` — CBN adapter | ⬜ Not started | — |
| `baas-backoffice` — React | ⬜ Not started | — |
| `baas-portal` — React | ⬜ Not started | — |
| `baas-docs` — Docusaurus | ⬜ Not started | — |
| Infrastructure (Docker + K8s + CI) | ⬜ Not started | — |

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
| Jasypt PII encryption (active) | ⚠️ Wired, not active | Phase 2 |
| Annual consent re-validation | ❌ Gap | Phase 3 |

---

## Change History

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
