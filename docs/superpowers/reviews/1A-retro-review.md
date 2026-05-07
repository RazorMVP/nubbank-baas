# Phase 1A Retrospective Code Review

**Reviewed**: 2026-05-03
**Scope**: PR #2, merge commit `2d96c0a` (frozen snapshot — `git show 2d96c0a:<path>`)
**Files reviewed**: ~40 Java sources + 2 Flyway migrations + `application.yml` + `pom.xml` + `SecurityConfig`
**Reviewer**: superpowers:code-reviewer (retrospective, post-merge, 6 days late)
**Plan**: `docs/superpowers/plans/2026-04-27-nubbank-baas-phase1a-engine.md`

---

## Summary

Phase 1A delivered a structurally solid scaffold of the schema-per-partner Hibernate multi-tenancy stack and the core BaaS REST surface, but it was self-merged with **zero recorded review activity**. The frozen snapshot contains **4 critical, 6 important, and 5 minor findings**, dominated by tenant-isolation gaps, an effectively open authorization model (`anyRequest().permitAll()`), and a JWT/encryption secret default that ships an exploitable static key in the binary. None of these are conceptual dead-ends — most are one-line fixes — but every one of them should have blocked merge of a *banking* engine. The good news: the layering, the Hibernate `MULTI_TENANT_*` wiring, the deadlock-safe transfer ordering, and the pessimistic-locked NUBAN pool are all correct, so the surface area to remediate is small.

Headline counts: **4 critical · 6 important · 5 minor · 5 strengths**.

---

## Critical findings

### C1. `anyRequest().permitAll()` — the engine has effectively no authorization
**File**: `baas-engine/src/main/java/com/nubbank/baas/engine/config/SecurityConfig.java:27`
**What's wrong**: `SecurityConfig` ends with `.anyRequest().permitAll() // enforcement added in Phase 2`. Combined with the fact that `PartnerContextFilter` only *resolves* a context and never *requires* one, every endpoint — `/baas/v1/customers`, `/baas/v1/accounts/*/withdraw`, `/baas/v1/payments/transfer`, `/baas/v1/sandbox/reset` — is reachable with **no Authorization header at all**.
**Exploitation**: A request with no header reaches `CustomerService.requireContext()` which throws `MISSING_AUTH` — but `requireContext()` is only called on the controllers that bothered to add it. `PaymentController.transfer` does check, but a missing or *invalid* JWT/API key silently falls through `PartnerContextFilter.resolveJwt()`'s `catch (Exception)` block (`PartnerContextFilter.java:74-78`) and the request proceeds with `PartnerContext == null`. The defense reduces to "every service method remembered to call `requireContext()`". Several read endpoints in the partner package do not.
**Fix**: Replace `anyRequest().permitAll()` with `.anyRequest().authenticated()` and stand up a real `AuthenticationProvider` that fails the request when `PartnerContext.get() == null` after the filter runs. The "Phase 2" deferral note is not acceptable for a banking API merged to main.

### C2. JWT signing secret has a hardcoded fallback that ships in the binary
**File**: `baas-engine/src/main/resources/application.yml:31`
**What's wrong**: `app.jwt.secret: ${JWT_SECRET:nubbank-baas-dev-secret-key-must-be-at-least-32-characters-long}`. If `JWT_SECRET` is unset in any environment (CI, staging, "I forgot the env var in prod"), the engine boots happily and signs production JWTs with a string that is **literally in this repo and in every Docker image built from it**. The same pattern exists for `app.encryption.key` (line 33).
**Exploitation**: Anyone who pulls the public repo or the image can mint a valid `Bearer` token for any partner by calling `PartnerJwtService.issue(...)` against that key, set `partner_id` and `schema_name` to a target partner, and walk straight into another tenant's accounts and customers. There is no defence-in-depth check that the partner_id in the JWT matches a real `PartnerOrganization` row before tenant routing fires.
**Fix**: Remove the default. Fail-fast on startup if `JWT_SECRET` is unset, and validate length ≥32 bytes. Treat `ENCRYPTION_KEY` the same way. Add a startup warning if the key is detectably the dev default.

### C3. Tenant isolation does not survive a misuse / leak of `PartnerContext`
**Files**: `tenant/PartnerSchemaProvider.java:24` (`getAnyConnection()`) and `tenant/PartnerTenantResolver.java:14`
**What's wrong**: Hibernate calls `getAnyConnection()` (no schema) for some bootstrap tasks — connections returned by this path are `dataSource.getConnection()` with **whatever search_path the previous user of that pooled connection left behind**. `releaseConnection(schemaName, connection)` does reset to `public`, but `releaseAnyConnection()` does not. With HikariCP recycling connections, a previous tenant's `search_path` can leak onto the next request when an `@Async` job, a non-tenant-aware `JdbcTemplate` call (e.g. the public-schema one in `SandboxService`), or any code path that bypasses `PartnerSchemaProvider.getConnection(name)` borrows from the pool. Compounding this, `PartnerTenantResolver.resolveCurrentTenantIdentifier()` returns `"public"` when `PartnerContext.get() == null` — which is the right safe-default, *except* that `PartnerContext` is a ThreadLocal and Phase 1A has zero tests for thread-pool reuse (only the trivial set/get test at `PartnerContextTest.java`).
**Exploitation**: An exception thrown inside a controller method *before* `PartnerContextFilter`'s `finally` runs is correctly cleared, but any `@Async` task spawned from a request thread (e.g. `provisionAsync`) inherits no context, will hit the `null` branch, and quietly resolve to `public`. If a future controller spawns `@Async` work that does anything other than schema admin, it executes against `public`, not the partner's schema. There is also no `TaskDecorator` to copy context into async threads.
**Fix**: (a) Make `releaseAnyConnection()` reset `search_path` to `public` like `releaseConnection()` does. (b) Configure HikariCP `connection-init-sql: SET search_path TO public`. (c) Add a `TaskDecorator` that snapshots and restores `PartnerContext` for every `@Async` task. (d) Make `PartnerTenantResolver` throw, not return `"public"`, when called from non-admin code paths — silent fallback to `public` when the developer forgot to set context is a footgun.

### C4. `SandboxService.reset()` truncates *any* schema name the JWT claims
**File**: `baas-engine/src/main/java/com/nubbank/baas/engine/sandbox/SandboxService.java:55-69`
**What's wrong**: The schema name is read from `PartnerContext.get().schemaName()` and concatenated directly into `TRUNCATE TABLE ` + sandboxSchema + `.` + table + ` CASCADE` via `JdbcTemplate.execute()`. There is **no allowlist or regex validation** at this point. The only validation in 1A lives inside `PartnerSchemaProvider.getConnection()` and `TenantProvisioningService.provision()`. `SandboxService` runs through neither.
**Exploitation**: Combine with C2: an attacker mints a JWT with `schema_name = "public"` (no `partner_` prefix needed because the code's `replace("partner_", "sandbox_")` is a no-op when the prefix is absent — it returns the input unchanged). Calling `POST /baas/v1/sandbox/reset` then runs `TRUNCATE TABLE public.audit_log CASCADE` etc. — wiping platform-level tables including `partner_organizations` (via CASCADE on FKs). Even without C2, a partner whose JWT is forged or mishandled by 1B (no JWT issuer validation, no `kid` rotation) gets the same blast radius.
**Fix**: Validate `sandboxSchema` against `^sandbox_[a-zA-Z0-9_]+$` before any concatenation. Better, look up the partner row by `partner_id` claim, read its real `schema_name` from the DB, and refuse if it doesn't begin with `sandbox_` or `partner_`.

---

## Important findings

### I1. API key SHA-256 lookup is timing-attack visible (but not catastrophic)
**File**: `tenant/PartnerContextFilter.java:60` — `apiKeyRepo.findByKeyHashAndActiveTrue(keyHash)`
**What's wrong**: Lookup is a B-tree index probe on `partner_api_keys.key_hash` (V1 migration line 53: `idx_api_keys_hash`). Postgres returns "miss" measurably faster than "hit + load row + LAZY init org + write last_used_at". In high-volume probing, an attacker can distinguish *whether* a candidate hash is in the table — though SHA-256 over a 32-byte random key still makes a brute-force search infeasible.
**Fix**: Acceptable risk for now, but document it. A constant-time comparison after lookup would be theatre — the real timing leak is on the index probe itself. Mitigation in I3 (rate limiting on auth) handles the practical concern.

### I2. Rate-limiter fail-open is exploitable by triggering Redis failure
**File**: `config/RateLimitFilter.java:40-58` and `config/RateLimitService.java:32-56`
**What's wrong**: When Redis is unavailable, `RateLimitService.check()` returns `RateLimitResult(true, 0, limit, 60)`. Filter writes `X-RateLimit-Limit: -1` (RateLimitFilter.java:55) and lets the request through. There is no circuit breaker, no bulkhead, no logging beyond `log.debug` (debug, not warn) on failure.
**Exploitation**: An attacker who can saturate Redis (large `INCR` storm against unrelated keys, slow-consumer pattern, or DNS poisoning of `REDIS_HOST` in shared infra) effectively unlocks unlimited requests across all partners. There is also no hard ceiling — the platform has no global RPS cap independent of the per-partner Redis check.
**Fix**: Switch to fail-*closed* with a small token-bucket fallback in JVM memory (e.g. Bucket4j local), or at minimum log at `WARN`, increment a metric, and apply a stricter local fallback (e.g. 5 RPS per partner) instead of unlimited. Distinguish "Redis returned null" (treat as suspicious) from "Redis threw" (log loudly).

### I3. Auth endpoints are not rate-limited
**File**: `config/RateLimitFilter.java:65` — `shouldNotFilter` excludes `/baas/v1/auth/`
**What's wrong**: Login and register are explicitly skipped because the partner context is unknown pre-auth. There is no IP-based rate limit, no slow-down on repeated failed `BCrypt.matches()`, and `BCryptPasswordEncoder(12)` makes login expensive enough to be a DoS vector.
**Exploitation**: An attacker can both (a) credential-stuff partner admin emails at full speed and (b) DoS the engine by hammering `/login` because each call burns ~250 ms of CPU on BCrypt rounds=12.
**Fix**: Add an IP-based rate limiter for `/baas/v1/auth/**` (e.g. 10 attempts / 5 min per IP). Consider lowering BCrypt rounds to 10 and combining with login lockout / progressive delay on failed attempts.

### I4. `audit_log` is enforced by convention, not by GRANT or trigger
**File**: `db/migration/tenant/V1__tenant_schema.sql:103-115` and `db/migration/public/V1__public_schema.sql` (no audit table)
**What's wrong**: The tenant `audit_log` table has the right shape (entity_type, entity_id, action, changed_by, old_values, new_values, created_at) but no `REVOKE UPDATE, DELETE` on the application role, no `BEFORE UPDATE OR DELETE` trigger that raises an exception, and crucially **no `AuditLogService` exists in 1A** — the package is empty in this snapshot. The CLAUDE.md/baas-log notes mention `AuditLogService` as if delivered, but the source tree at `2d96c0a` does not contain it.
**Fix**: (a) Add a Flyway migration that does `REVOKE UPDATE, DELETE ON audit_log FROM PUBLIC` and creates a `BEFORE UPDATE OR DELETE` trigger that raises. (b) Build the actual `AuditLogService` (deferred to 1A-ext / Session 4 by the look of it — verify it landed before 1C). The PRD's "10-year retention, append-only" promise is currently undeliverable.

### I5. Async tenant provisioning has no failure-recovery, blocks under partition
**File**: `tenant/TenantProvisioningService.java:39-65` and `auth/AuthController.java:55`
**What's wrong**: `register` saves the partner row, returns the JWT, then `provisioningService.provisionAsync(...)` runs. If schema creation or Flyway migration fails, the partner has a JWT, has an org row, but **has no schema** — and the code at `AuthController.java` does not rollback the org insert on async failure. Subsequent calls from this JWT will hit `PartnerSchemaProvider.getConnection(schemaName)` which executes `SET search_path TO partner_xxx, public` — succeeds with no error, then every JPA query fails with "relation does not exist". The partner sees opaque 500s. There is also no retry on the async failure path.
**Fix**: Either (a) make registration synchronous and only return the JWT after schema migration succeeds (better UX for a sub-second op anyway), or (b) gate API access behind a `provisioning_status` field on `partner_organizations` that defaults to `PROVISIONING` and is flipped to `READY` only by the async job — the filter rejects requests until READY. Add a retry / dead-letter mechanism.

### I6. No Hibernate L2 cache config — and no `multi_tenant_strict_jpql_check`
**File**: `application.yml:14-19` (jpa properties) and `tenant/MultiTenantConfig.java`
**What's wrong**: L2 cache is implicitly off (good — leaks across tenants if the cache key omits tenant_id), but there is no explicit `hibernate.cache.use_second_level_cache: false` and no `hibernate.multi_tenant_strict_jpql_check: true` to assert that JPQL queries reference tenant-aware entities only. Future code adding `@Cacheable` on a JPA entity will silently leak across tenants. There is also no `spring.jpa.properties.hibernate.multiTenancy: SCHEMA` in YAML — it relies on the customizer setting `MULTI_TENANT_IDENTIFIER_RESOLVER` and `MULTI_TENANT_CONNECTION_PROVIDER` programmatically, but never sets the `multiTenancy` *strategy* property on the customizer. (Hibernate 6 infers SCHEMA from the connection provider type, but explicit > implicit for a banking config.)
**Fix**: Add `hibernate.cache.use_second_level_cache: false`, `hibernate.cache.use_query_cache: false`, and explicit `hibernate.multiTenancy: SCHEMA` to the customizer. Add a regression test that `@Cacheable` on a tenant entity raises a startup error.

---

## Minor findings

### M1. `PartnerContextFilter.sha256Hex` reimplements crypto
`tenant/PartnerContextFilter.java:81-87` — uses `String.format("%02x", b)` per byte in a loop, then `MessageDigest.getInstance("SHA-256")` per call. Switch to `HexFormat.of().formatHex(...)` (Java 21 std lib) and consider caching the `MessageDigest` in a `ThreadLocal` or using `java.security.MessageDigest.isEqual` semantics elsewhere. Functional, just antiquated style.

### M2. `BaasException.getCode()` and `getStatus()` are public, no final fields
`common/BaasException.java` — `code` and `status` are correctly final but field access is package-private — the getters work, but the class would benefit from `@Getter` or simply being a `record` extending `RuntimeException` (Java 21 supports this idiomatically since 17).

### M3. `RateLimitService` hardcoded `EXPIRE 60` — no per-tier window
`config/RateLimitService.java:23-27` Lua script always sets a 60-second window. The "RPM" naming makes this look right, but bursting partners benefit from sliding windows or token buckets. Acceptable for 1A; document as a known limitation.

### M4. `Customer.firstNameEncrypted` etc. are not actually encrypted in 1A
`customer/CustomerService.java:30-36` — comment says `// Phase 2: encrypt with Jasypt` but the field is named `*_encrypted` which is misleading. Names tell you the data is encrypted; reality is plaintext. Either rename to `firstName` until encryption lands, or accept the discoverability risk (someone reads a row in psql, sees an unencrypted email under `email_encrypted`, draws the wrong conclusion). The Jasypt 3.0.5 dep is on the classpath but unused.

### M5. `idx_api_keys_hash WHERE active = true` — partial index but `findByKeyHashAndActiveTrue` doesn't always hit it
`db/migration/public/V1__public_schema.sql:124` — partial index on `key_hash WHERE active = true`. JPA-generated SQL must include both predicates for the planner to use this index. The repository method does (`findByKeyHashAndActiveTrue`) but a future ad-hoc `findByKeyHash` would silently full-scan. Document the expected query shape on the index.

---

## Strengths

- **Deadlock-safe transfer ordering** — `PaymentService.transfer()` locks accounts in UUID order before pessimistic-write fetch (lines 31-44). Comment is clear and correct. This is a frequently-botched concurrency primitive and 1A nailed it.
- **Pessimistic-write NUBAN pool** — `VirtualAccountRepository.findFirstUnassignedForUpdate()` uses `@Lock(PESSIMISTIC_WRITE)` and `LIMIT 1` to prevent concurrent duplicate assignments. Pre-seed of 10,000 NUBANs with check-digit math in pure SQL is elegant.
- **Schema-name regex validation in two of three concatenation sites** — `PartnerSchemaProvider.getConnection()` (line 28) and `TenantProvisioningService.provision()` (line 30) both check `[a-zA-Z0-9_]+`. The miss in `SandboxService` (C4) is the gap, not the rule.
- **`ThreadLocal.remove()` in `finally`** — `PartnerContextFilter.doFilterInternal()` line 27 always clears, preventing the textbook ThreadLocal-in-thread-pool leak.
- **Optional `StringRedisTemplate`** — `RateLimitService` uses `@Autowired(required = false)` so the engine boots without Redis. Pragmatic for dev; the fail-open behaviour (I2) is the only weakness.

---

## Recommended next actions

**Block before Phase 1C lands** (these are merge-blocking-grade for a banking API):

1. **Fix C1** — flip `anyRequest().permitAll()` to `.authenticated()` and require `PartnerContext != null` for all `/baas/v1/{customers,accounts,payments,sandbox}/**` paths. One-day fix.
2. **Fix C2** — remove dev-default for `JWT_SECRET` and `ENCRYPTION_KEY`; fail-fast on startup; add a length check. Half-day fix.
3. **Fix C4** — regex-validate `sandboxSchema` in `SandboxService.reset()` before SQL concatenation. One-line fix.
4. **Fix I5** — gate partner API calls behind a `provisioning_status` flag on `partner_organizations` so a half-provisioned partner can't get 500s on every call. Half-day.

**Address before Phase 1D** (important but not platform-breaking):

5. **C3** — `releaseAnyConnection` reset, HikariCP `connection-init-sql`, `TaskDecorator` for `@Async`. One day.
6. **I2** — fail-closed rate limiter with local fallback. Half-day.
7. **I3** — IP-based rate limit on `/baas/v1/auth/**`. Half-day.
8. **I4** — DB-level `audit_log` immutability + ship `AuditLogService`. (Verify whether 1A-ext / Session 4 already did this — if so, mark closed; if not, must ship.)

**Backlog** (nice-to-have hardening):

9. I6 — explicit Hibernate L2-off + strict JPQL check.
10. M4 — actually wire Jasypt for PII fields (the column names already lie about it).
11. M3 — consider sliding-window or token-bucket rate limit before adding the "PRO" tier in production.

**Process**: this PR was self-merged with no recorded review. The prevention here is not technical — it is a branch protection rule on `main` requiring at least one reviewer approval, and a review skill invocation hook on PRs touching `baas-engine/src/main/java/**`. Without that, the next 1B/1C/1D PRs will repeat this pattern.
