# NubBank BaaS — Build Log

> Tracks all implementation work, decisions, and changes for the NubBank BaaS platform.
> Updated at the end of every session. Newest entries at the top.

---

## Build Status — Current State

| Sub-system | Status | Last Session |
|------------|--------|-------------|
| `baas-engine` — Phase 1A | ✅ Complete (all 16 tasks, 23 tests passing, smoke test live) | Session 1 |
| `baas-ncube` — Phase 1B | ✅ Complete (9 tasks, 21 tests, smoke test live) | Session 2 |
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

#### Figma boards affected

The Service Architecture board needs regeneration to reflect the new components introduced in this session:

- **[Service Architecture](https://www.figma.com/board/PRACgc6BXsGVEL7ZhB866A)** — Add `AuthEnforcementFilter`, `RateLimitFilter`, `AuditAspect`, `FieldEncryptor`, `TenantJdbcTemplate`, `CobJobExecutor`, `TwoFactorTokenWriter`, `TestOtpStore` to the engine internals diagram. Show the new filter chain order: PartnerContextFilter → AuthEnforcementFilter → RateLimitFilter.
- **[Multi-Tenancy Flow](https://www.figma.com/board/TR1AYhx9Pcmd5y5grxMv8v)** — Add the `TenantJdbcTemplate` path alongside Hibernate's `MultiTenantConnectionProvider`; show that raw JDBC requires explicit `SET search_path`.
- **[Partner Provisioning Flow](https://www.figma.com/board/qHD6cSCRTQHPbkmavHtoxw)** — No change.
- **[CBN Compliance Roadmap](https://www.figma.com/board/5KpYYAtiukv7G6o3LyjsVr)** — Move "PII encryption at rest" from ⚠️ to ✅; flag NDPR §9.2 compliance scorecard delta (1 ✅, 2 ⚠️, 1 ❌).

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
