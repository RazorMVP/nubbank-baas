# NubBank BaaS — Body of Knowledge

This file is the single source of truth for Claude when working on the NubBank BaaS platform. Read it fully at the start of every session before generating any code.

NubBank BaaS is a **completely separate product** from NubBank SaaS (`cba-platform`). Do NOT touch, reference, or modify anything in the `CoreBanking/` directory when working on this project.

---

## ⛔ SESSION COMPLETION GATE — READ BEFORE SAYING "DONE"

**You MUST NOT close a session, summarise completion, or push to GitHub until every item below is checked.**

### Mandatory End-of-Session Checklist

- [ ] **1. `baas-log.md`** — New session entry added at the top of Change History. Must include: session number, date, one-line summary, New/Updated Files table, Key Decisions, Build Verification, Confirmed Platform Versions block.
- [ ] **2. `CLAUDE.md`** — Updated: Confirmed Platform Versions table; Module Catalogue (new modules ✅); new gotchas.
- [ ] **3. API docs** — If ANY `baas-engine` endpoint was added or changed: update API docs (to be created in Session 2+). Only sessions that touched zero controller files may skip.
- [ ] **4. Deployment-agnostic check** — If a new service was added: Dockerfile committed, CI workflow committed, Docker Compose entry added.
- [ ] **5. Commit and push** — `git add CLAUDE.md baas-log.md && git commit && git push origin main`.

### Rationalisation Traps

| Thought | Why it's wrong |
|---------|---------------|
| "The frontend didn't touch the backend" | CLAUDE.md and baas-log.md still need updating |
| "I'll do docs next session" | The next session starts cold. Missing docs will be missed again. |
| "It was just a fix" | Every session gets a log entry |

---

## Confirmed Platform Versions (Session 1 — 2026-04-27)

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

### Repository Structure

```
nubbank-baas/                           ← github.com/RazorMVP/nubbank-baas
├── CLAUDE.md                           ← This file
├── baas-log.md                         ← Session change log
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
