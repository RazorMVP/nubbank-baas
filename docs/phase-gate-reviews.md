# Phase-Gate Reviews — NubBank BaaS

> Log of Expert Reviews produced at phase boundaries. Source rule: `.claude/skills/baas/SKILL.md` § Phase-Gate Review.
>
> **History.** This file was previously `docs/expert-review-summary.md` under a per-session enforcement model. That model was unwound on 2026-05-17 (see `baas-log.md` for the rationale). The 13 historical rows in the **Historical Seed** block below document real critiques from sessions 1–6 and have already been promoted to `CLAUDE.md` § Known Gotchas. New entries are now written **per phase**, not per session.

---

## How This File Works

1. At a phase boundary (see § Phase-Gate Review in `SKILL.md`), invoke the Expert Review against the **whole phase as a unit**.
2. Capture the review here with **one row per phase**, with an explicit closure state:
   - `↑ Promoted <sha>` — lifted into `CLAUDE.md` § Known Gotchas
   - `[resolved] <sha>` — fixed inside this phase or the next
   - `[deferred-to-phase-N]` — scoped to a later phase, not in-flight
   - `[accepted-risk] <one-line reason>` — known limitation accepted for this phase
   - `[wontfix] <one-line reason>` — closed; not pursuing
3. New entries land **above** the Historical Seed block, ordered newest-first.

There is no hook, no CI check, and no required artifact. This is governance, not enforcement.

---

## Phase-Gate Reviews

| Phase | Reviewed on | Topic | What landed solid | What oversimplified | Closure |
| ----- | ----------- | ----- | ----------------- | ------------------- | ------- |
| _next phase_ | _TBD_ | — | _First phase-gate review lands when the next sub-plan completes._ | — | — |

---

## Historical Seed (sessions 1–6, pre-2026-05-17)

These rows were captured under the prior per-session enforcement model. They are all `↑ Promoted [backfill]` — already in `CLAUDE.md` § Known Gotchas. **Do not re-promote**, and do not treat them as in-flight critiques. Newer phase-gate reviews land above this block.

| Session | Date | Topic | Oversimplification / risk called out | Recommendation | Promotion status |
| ------- | ---- | ----- | ------------------------------------ | -------------- | ---------------- |
| 6 | 2026-05-09 | `observability` `security` | `requestMatchers("/actuator/health").permitAll()` is an exact match — `/actuator/health/readiness` and `/actuator/health/liveness` sub-paths return 404; k8s probes never report Ready; pods loop in CrashLoop. | Use glob: `requestMatchers("/actuator/health/**").permitAll()`. Catch in the compose-stack smoke test, **not** in k8s rollout (faster signal, no cluster needed). | `↑ Promoted [backfill]` — Session 6 BONUS commit |
| 5 | 2026-05-07 | `regulator-cbn` `api-contract` | Class-level `@RequestMapping(consumes = CBN_OB)` rejects GET requests with 415 — Spring inherits class-level `consumes` to every method including GET, which only sends `Accept`. Every partner GET would return cryptic 415. | Keep `produces` at class level (response negotiation); move `consumes` to method-level on POST/PUT only. Add `@ExceptionHandler(HttpMediaTypeNotSupportedException.class)` → 415 and `(HttpMediaTypeNotAcceptableException.class)` → 406 so the failure shape is correct. | `↑ Promoted [backfill]` — CLAUDE.md Known Gotchas (Session 5) |
| 5 | 2026-05-07 | `audit-trail` `observability` | First-cut PII regex `\b\d{13,19}\b` masks 13-digit Unix-millisecond timestamps and 10-digit Unix-second timestamps — every Sleuth/Micrometer trace ID, JWT `iat`/`exp`/`nbf`, and `currentTimeMillis()` log line gets mangled. False-positive rate ≈ 100% in any production log. | Context-anchored bounded lookbehind: `(?<=(?:card\|pan\|primary)[^\\d]{0,16})(\\d{4})...` for PAN; `(account\|nuban\|from\|to\|debit\|credit)` for NUBAN. BVN/NIN keep simple 11-digit match — uniqueness rare enough. Document explicit scope-out for MDC values, structured args, exception messages. | `↑ Promoted [backfill]` — CLAUDE.md Known Gotchas (Session 5) |
| 5 | 2026-05-07 | `auth` `security` | Spring's `ContentCachingRequestWrapper` does NOT replay body bytes via `getInputStream()` — HMAC filter reads body, controller's `@RequestBody` deserialiser sees an empty stream. Signed POSTs silently 400 with "Required request body is missing". | Custom `CachedBodyHttpServletRequest` overriding `getInputStream()` to return a fresh `ByteArrayInputStream` on every call; enforce `MAX_BODY_BYTES = 1 MB` at read time to bound memory. Test must be full-stack `@SpringBootTest(RANDOM_PORT)` — slice tests miss this because they bypass the filter chain. | `↑ Promoted [backfill]` — CLAUDE.md Known Gotchas (Session 5) |
| 5 | 2026-05-07 | `security` `regulator-cbn` | Stub mode could run in production silently — naked `String.contains("prod")` is case-sensitive, so `PROD`, `Prod`, `prod-eu`, `production` all evade the guard. Operators slip on casing. | `@PostConstruct` boot guard: `Arrays.stream(profiles).anyMatch(p -> p.toLowerCase(Locale.ROOT).startsWith("prod"))`. Refuse boot when stub + prod combo detected. Add `X-NubBank-Stubbed: true` response header on every stubbed call as defence-in-depth. Stub data must be constant (e.g. `00000000000`), never echo caller input — echo is indistinguishable from real verification. | `↑ Promoted [backfill]` — Session 5 `StubModeGuard` |
| 4 | 2026-05-03 | `security` `multi-tenancy` | Raw SQL with interpolated schema name is an injection vector — `SET search_path TO ${tenant}` accepts arbitrary input from the JWT claim, including `public; DROP SCHEMA partner_xxx CASCADE; --`. | Validate every schema name against a strict regex (`^(?:partner\|sandbox)_[0-9a-f]{32}$`) **before** any string interpolation. Centralise in `PartnerSchemaProvider` + `TenantJdbcTemplate`; never accept the schema name from a controller param. | `↑ Promoted [backfill]` — Session 4 `TenantJdbcTemplate` |
| 4 | 2026-05-03 | `state-machine` `payments` | Spring `@EventListener` fires immediately on `publishEvent()` — **before** the transaction commits. Webhooks, notifications, and downstream payments fired even when the originating transaction rolled back. Bank customers received "loan approved" emails for loans that never persisted. | `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` for any side-effect that must skip on rollback. Covers `AccountOpenedEvent`, `LoanApprovedEvent`, `LoanDisbursedEvent`, `PaymentCompletedEvent`. Document the phase choice in the listener's Javadoc — `BEFORE_COMMIT` and `AFTER_ROLLBACK` are also valid for other use cases (e.g. cleanup). | `↑ Promoted [backfill]` — CLAUDE.md Known Gotchas (Session 4) |
| 4 | 2026-05-03 | `schema` `audit-trail` | PostgreSQL JSONB column rejects bound `varchar` — JDBC driver binds Strings as `character varying`; audit log writes fail with `column "old_values" is of type jsonb but expression is of type character varying`. | `@JdbcTypeCode(SqlTypes.JSON)` on the entity field (Hibernate 6 native — no third-party lib). For raw inserts, pre-serialise via `ObjectMapper` and bind as `Object` not `String`. Never write naked strings to JSONB — even valid JSON literals fail unless wrapped in proper quoting. | `↑ Promoted [backfill]` — CLAUDE.md Known Gotchas (Session 4) |
| 4 | 2026-05-03 | `auth` `security` | 2FA failed-attempt counter doesn't survive `BaasException` rollback — caller's `@Transactional` rolls the increment back along with the OTP verification; brute-force lockout never engages because the counter never persists. | Move counter writes to a separate bean with `@Transactional(propagation = REQUIRES_NEW)` (`TwoFactorTokenWriter` pattern). Use atomic `UPDATE ... SET failed_attempts = failed_attempts + 1, locked = (failed_attempts + 1 >= :max)` so PostgreSQL evaluates the boolean against the pre-update row state. No `@Version` needed when the SET clause itself is atomic. | `↑ Promoted [backfill]` — Session 4 |
| 4 | 2026-05-03 | `concurrency` `state-machine` | `@Transactional` on a private method silently does nothing — Spring AOP proxies don't intercept private methods or self-references (`this::method`). CoB job's `@Transactional` was bypassed; jobs ran outside any transaction. | Extract to a separate `@Service` bean (`CobJobExecutor` pattern) and inject. AOP only works at the proxy boundary, which means inter-bean calls. Add a `static-analysis` rule (SpotBugs `SE_TRANSIENT_FIELD_NOT_TRANSIENT` won't catch this — write a custom Error Prone check) for `@Transactional` on `private` methods. | `↑ Promoted [backfill]` — CLAUDE.md Known Gotchas (Session 4) |
| 3 | 2026-05-02 | `multi-tenancy` `schema` | Raw `JdbcTemplate` queries bypass Hibernate's `MultiTenantConnectionProvider` — reports, global search, and any non-JPA query silently read the wrong tenant or `public` schema. No compile-time signal; the failure shape is "wrong data, no error". | `TenantJdbcTemplate` wraps `JdbcTemplate`, validates schema against `^(?:partner\|sandbox)_[0-9a-f]{32}$`, runs `SET search_path TO <schema>, public` before each query. Mandate use for **every** raw-SQL path; ban direct `JdbcTemplate` injection via SpotBugs custom detector or grep CI check. | `↑ Promoted [backfill]` — CLAUDE.md Known Gotchas (Session 4 retrofit) |
| 2 | 2026-04-27 | `auth` `architecture` | Engine→ncube inter-service calls had no authentication; ncube accepted any media type. Either service compromise lets an attacker pivot freely. | HMAC-SHA256 body-signed scheme: `Authorization: Internal <hmac>` + `X-Internal-Timestamp`; HMAC content `METHOD\|PATH\|TS\|sha256Hex(body)`; 60s replay window; ≥32-char secret enforced at construction. Body inclusion is critical — header-only HMAC leaves the body fully tamperable. Pair with CBN vendor media type `application/vnd.cbn.openbanking.v1+json` on POST/PUT. | `↑ Promoted [backfill]` — Session 5 `InternalServiceAuthFilter` |
| 1 | 2026-04-27 | `architecture` `auth` | `SecurityConfig` shipped as `permit-all` + stateless on `/baas/v1/**` — relied on every service method calling `requireContext()`. New endpoints were silently public; the discipline was per-developer, not enforced. | Single config gate (`AuthEnforcementFilter`) **before** controllers — `OncePerRequestFilter` rejects unauthenticated `/baas/v1/**` with 401. New endpoints are protected by default; permit-all is opt-in per path, not opt-out. This is the same pattern as Spring Security's `authenticated()` but with explicit response shape control. | `↑ Promoted [backfill]` — Session 4 `5adeb10` |

---

## Topic Tags (use in the Topic column for new phase-gate entries)

Use one or more of these tags to make grep-based audits possible later:

- `architecture` — service boundaries, integration patterns
- `schema` — DDL, Flyway, JSONB usage, indexes, partitioning
- `multi-tenancy` — `PartnerContext`, schema routing, ThreadLocal lifecycle
- `auth` — Partner JWT, API key, FAPI 2.0, mTLS, OAuth flows
- `consent` — consent lifecycle, Ncube sync, revocation, expiry
- `payments` — NIP, SWIFT, internal transfer, reversal, settlement
- `reconciliation` — end-of-day batch, GL posting, balance verification
- `regulator-cbn` — CBN OBR, Operational Guidelines, KPI metrics
- `regulator-fapi` — FAPI 2.0 §x clauses (PAR, DPoP, PKCE)
- `regulator-iso20022` — pacs.008, pain.001, message-type compliance
- `kyc` — BVN/NIN, identity providers, tiered KYC
- `idempotency` — `Idempotency-Key`, retry semantics, request hashing
- `audit-trail` — append-only `audit_log`, PII masking, retention
- `security` — encryption at rest, secrets, PII handling, CVE response
- `observability` — metrics, traces, logs, alerts
- `state-machine` — entity status transitions, valid-from/valid-to checks
- `concurrency` — `SELECT FOR UPDATE`, optimistic locking, race conditions
- `api-contract` — versioning, breaking changes, response envelope shape
- `testing` — integration tests, Testcontainers, contract tests

---

## Audit Helpers

Column indices in the Aggregation tables: `$2=Session/Phase`, `$3=Date`, `$4=Topic`, `$5=What landed solid`, `$6=Oversimplification`, `$7=Closure`.

```bash
# Count promotions to date
grep -c "↑ Promoted" docs/phase-gate-reviews.md

# All rows tagged with a topic (Topic column only — no false positives from prose)
awk -F'|' '$4 ~ /payments/' docs/phase-gate-reviews.md

# Substitute any topic from § Topic Tags — multi-tenancy, consent, auth, payments, regulator-cbn, etc.
awk -F'|' '$4 ~ /multi-tenancy/' docs/phase-gate-reviews.md
awk -F'|' '$4 ~ /regulator-cbn/' docs/phase-gate-reviews.md

# Find rows with a specific closure state
awk -F'|' '$7 ~ /deferred-to-phase/' docs/phase-gate-reviews.md
awk -F'|' '$7 ~ /accepted-risk/' docs/phase-gate-reviews.md

# All rows from a specific phase
awk -F'|' '$2 ~ /^[[:space:]]*1F-0[[:space:]]*$/' docs/phase-gate-reviews.md
```

---

## References

- [`.claude/skills/baas/SKILL.md` § Expert Review — On Request](../.claude/skills/baas/SKILL.md) — the persona and review format
- [`.claude/skills/baas/SKILL.md` § Phase-Gate Review](../.claude/skills/baas/SKILL.md) — when to invoke
- [`.githooks/pre-push`](../.githooks/pre-push) — Gate 1 (`Confirmed Platform Versions` presence)
- `baas-log.md` § Confirmed Platform Versions
- `CLAUDE.md` § Confirmed Platform Versions
