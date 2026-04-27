# NubBank BaaS — Build Log

> Tracks all implementation work, decisions, and changes for the NubBank BaaS platform.
> Updated at the end of every session. Newest entries at the top.

---

## Build Status — Current State

| Sub-system | Status | Last Session |
|------------|--------|-------------|
| `baas-engine` — Foundation | 🔄 In Progress (Tasks 1–9 of 16 complete) | Session 1 |
| `baas-ncube` — CBN adapter | ⬜ Not started | — |
| `baas-backoffice` — React | ⬜ Not started | — |
| `baas-portal` — React | ⬜ Not started | — |
| `baas-docs` — Docusaurus | ⬜ Not started | — |
| Infrastructure (Docker + K8s + CI) | ⬜ Not started | — |

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

#### Confirmed Platform Versions

| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | `6e5b816` |
| Java | 21 | `6e5b816` |
| Hibernate | 6.x (managed) | `6e5b816` |
| Flyway | 10.x (managed) | `6e5b816` |
| Nimbus JOSE+JWT | 9.37.3 | `6e5b816` |
| Lombok | 1.18.38 | `6e5b816` |
| Testcontainers | 1.20.1 | `6e5b816` |
| Last commit | `6e5b816` | Tasks 8+9 complete |

#### What's Next (Session 2)

- Task 10: `VirtualAccountService` (NUBAN pool assignment with `PESSIMISTIC_WRITE`)
- Task 11: Customer API (`POST/GET /baas/v1/customers`)
- Task 12: Account API (open, deposit, withdraw, transactions)
- Task 13: Payment API (internal transfer + idempotency)
- Task 14: Sandbox Controller (simulate deposit, schema reset)
- Task 15: Integration smoke test
- Task 16: Rate limiting (Redis)
- Then: push feature branch + open PR → merge to main
