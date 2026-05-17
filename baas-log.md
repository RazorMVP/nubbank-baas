# NubBank BaaS — Build Log

> Tracks all implementation work, decisions, and changes for the NubBank BaaS platform.
> Updated at the end of every session. Newest entries at the top.

---

## Build Status — Current State

| Sub-system | Status | Last Session |
|------------|--------|-------------|
| `baas-engine` — Phase 1A + 1A-ext | ✅ Complete (Phase 1A: 16 tasks; Phase 1A-ext: 29 banking modules + 12 critical security fixes; **84 tests passing**) | Session 4 (`5adeb10`) |
| `baas-ncube` — Phase 1B | ✅ Complete (9 tasks, 21 tests, smoke test live) | Session 2 |
| `baas-backoffice` — React | ⬜ Not started — Phase 1C next | — |
| `baas-portal` — React | ⬜ Not started — Phase 1D | — |
| `baas-docs` — Docusaurus | ⬜ Not started | — |
| Infrastructure (Docker + K8s + CI) | ✅ Complete — Phase 1E (Dockerfiles for engine + ncube, `infrastructure/docker-compose.yml`, vanilla k8s manifests in `infrastructure/k8s/`, GHCR CI workflows) | Session 4 |

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
