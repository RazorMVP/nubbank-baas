# Phase 1C — Track-Card (`baas-card`, D6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
> This track runs in the `~/nb-card` worktree on branch `feature/phase1c-card`, branched from `main` at or
> after `c6cb1b8` (Foundation merged). Read the spec §6.6 and the playbook before starting.

**Goal:** Build `baas-card`, a multi-tenant Spring Boot card service (port 8081) providing the Phase-1C card
lifecycle spine — card products, issuance, lifecycle state machine, per-card limits, encrypted PAN, the
public-schema BIN→tenant lookup `baas-fep` needs, and an internal card-authorization-decision stub — mirroring
`baas-engine`'s multi-tenancy + security + envelope patterns.

**Architecture:** Same shared PostgreSQL DB as `baas-engine`. Hibernate SCHEMA multi-tenancy via
`PartnerContext` (ThreadLocal, cleared in `finally`). Tenant card data (`card_products`, `cards`,
`card_limits`) lives in the existing `partner_{uuid}` schemas under a **card-specific Flyway history table**
(`flyway_schema_history_card`). The BIN lookup is a **public-schema** table (`card_bin_ranges`) because the
lookup is inherently cross-tenant — FEP calls it *before* a tenant is known. Card reads `baas-engine`'s
existing `public.partner_organizations` + `public.partner_api_keys` for partner-JWT (HMAC) + API-key auth;
internal endpoints use body-signed HMAC (`InternalServiceAuthFilter`).

**Tech Stack:** Java 21, Spring Boot 3.5.3, Spring Security 6 (OAuth2 resource server present but operator-JWT
deferred — see below), Hibernate SCHEMA multi-tenancy, Flyway (public + tenant, card history table),
Nimbus JOSE+JWT 9.48 (HMAC partner JWT), PostgreSQL 16, Testcontainers, Lombok 1.18.38.

**Auth scope decision (locked for Phase 1C):** Card's `/baas/v1/**` endpoints accept **partner JWT (HMAC) +
API key only** → full tenant authority (contract §1: "First-party credentials … carry the full tenant
authority set"). Operator-JWT / Keycloak-realm RBAC on card endpoints is **deferred** (registry item
`DEF-1C-20`) and added in Stage 4 when Track-Backoffice wires card screens. Internal endpoints use HMAC.

**Persistence-coupling decision (locked for Phase 1C):** Card shares the `baas-engine` DB and reads the
engine-owned `public.partner_organizations` / `public.partner_api_keys` tables for auth. Decoupling card into
its own partner mirror is **deferred** (`DEF-1C-21`). Card-owned tables use a separate Flyway history table so
the two services never collide on `flyway_schema_history`.

**Cross-service provisioning note:** In Phase 1C, card's tenant tables are created by card's own
`TenantProvisioningService` (its integration tests provision their own schema, exactly like
`CustomerControllerTest`). Wiring engine-provisions-partner → card-also-provisions is a Stage-5 integration
concern (`DEF-1C-22`).

---

## File structure

```
baas-card/
├── pom.xml                          ← mirror baas-engine/pom.xml (no Redis; add nothing card-specific)
├── mvnw, mvnw.cmd, .mvn/            ← copy from baas-engine
├── Dockerfile                       ← mirror baas-engine/Dockerfile, EXPOSE 8081
├── src/main/java/com/nubbank/baas/card/
│   ├── CardApplication.java         ← @SpringBootApplication @EnableAsync @EnableScheduling
│   ├── tenant/                      ← PORTED from engine (repackaged), card TenantProvisioningService
│   │   ├── PartnerContext.java
│   │   ├── PartnerContextFilter.java         (JWT+ApiKey only; NO operator-JWT branch)
│   │   ├── PartnerTenantResolver.java
│   │   ├── PartnerSchemaProvider.java
│   │   └── TenantProvisioningService.java    (locations: db/migration/card-tenant; table: flyway_schema_history_card)
│   ├── auth/
│   │   ├── PartnerJwtService.java            ← PORTED from engine (HMAC validate only)
│   │   └── ApiKeyResolver.java               ← extracted from engine PartnerContextFilter.resolveApiKey
│   ├── partner/                     ← read-only JPA views of engine-owned public tables
│   │   ├── PartnerOrganization.java          (@Table(schema="public", name="partner_organizations"))
│   │   ├── PartnerOrganizationRepository.java
│   │   ├── PartnerApiKey.java                 (@Table(schema="public", name="partner_api_keys"))
│   │   └── PartnerApiKeyRepository.java
│   ├── config/
│   │   ├── SecurityConfig.java               ← mirror engine (@Order(2) partnerFilterChain, +/internal permit)
│   │   ├── AuthEnforcementFilter.java        ← PORTED (also guard /internal? NO — internal uses HMAC filter)
│   │   ├── InternalServiceAuthFilter.java    ← INBOUND HMAC validation for /internal/v1/**
│   │   └── FieldEncryptor.java               ← PORTED from engine (AES-GCM JPA converter)
│   ├── common/
│   │   ├── ApiResponse.java                  ← PORTED verbatim (repackaged)
│   │   ├── BaasException.java                ← PORTED verbatim (repackaged)
│   │   └── GlobalExceptionHandler.java       ← PORTED verbatim (repackaged)
│   ├── bin/                         ← BIN ranges (PUBLIC schema) + internal lookup
│   │   ├── CardBinRange.java
│   │   ├── CardBinRangeRepository.java
│   │   ├── BinService.java
│   │   ├── BinController.java                 (/baas/v1/bins — tenant CRUD)
│   │   ├── InternalBinController.java         (/internal/v1/bins/{bin} — HMAC lookup)
│   │   └── dto/{RegisterBinRangeRequest,BinRangeResponse,BinLookupResponse}.java
│   ├── product/                    ← card products (TENANT schema)
│   │   ├── CardProduct.java, CardProductRepository.java, CardProductService.java, CardProductController.java
│   │   └── dto/{CreateCardProductRequest,CardProductResponse}.java
│   ├── card/                       ← cards + lifecycle (TENANT schema)
│   │   ├── Card.java, CardStatus.java, CardType.java
│   │   ├── CardRepository.java, CardService.java, CardController.java
│   │   └── dto/{IssueCardRequest,CardResponse}.java
│   ├── limit/                      ← per-card limits (TENANT schema)
│   │   ├── CardLimit.java, CardLimitRepository.java, CardLimitService.java
│   │   └── dto/{UpdateCardLimitsRequest,CardLimitResponse}.java
│   └── authorize/                  ← internal authorization-decision stub
│       ├── AuthorizationDecisionService.java
│       ├── InternalAuthorizationController.java   (/internal/v1/authorize — HMAC)
│       └── dto/{AuthorizationDecisionRequest,AuthorizationDecisionResponse}.java
├── src/main/resources/
│   ├── application.yml             ← server.port 8081; flyway card-public history; jpa SCHEMA multitenancy
│   ├── db/migration/card-public/V1__card_bin_ranges.sql
│   └── db/migration/card-tenant/V1__card_tables.sql
└── src/test/
    ├── resources/application-test.yml          ← mirror engine test config (Redis excluded; test secrets)
    └── java/com/nubbank/baas/card/
        ├── AbstractCardIntegrationTest.java     ← PORTED AbstractIntegrationTest (card provisioning helper)
        ├── support/TestPartner.java             ← helper: create org + provision schema + issue HMAC JWT
        └── {bin,product,card,limit,authorize}/*Test.java

.github/workflows/baas-card-ci.yml   ← mirror baas-engine-ci.yml (paths: baas-card/**, image baas-card, port 8081)
infrastructure/docker-compose.yml    ← ADD baas-card service block (port 8081)
```

**Repackage rule for every PORTED file:** copy the source byte-for-byte, then change the package declaration
and imports from `com.nubbank.baas.engine` → `com.nubbank.baas.card`. Where a delta is required, the task
shows the exact delta. Source paths are absolute under `~/nubbank-baas/baas-engine/`.

---

## Task 1: Service scaffold + ported multi-tenancy/security/common infrastructure

**Files:**
- Create: `baas-card/pom.xml`, `baas-card/mvnw`, `baas-card/.mvn/**`, `baas-card/Dockerfile`
- Create: `baas-card/src/main/java/com/nubbank/baas/card/CardApplication.java`
- Create (PORT): the `tenant/`, `auth/`, `partner/`, `config/`, `common/` files listed above
- Create: `baas-card/src/main/resources/application.yml`, `db/migration/card-public/V1__card_bin_ranges.sql`,
  `db/migration/card-tenant/V1__card_tables.sql`
- Create (PORT): `src/test/.../AbstractCardIntegrationTest.java`, `support/TestPartner.java`,
  `src/test/resources/application-test.yml`

- [ ] **Step 1: Scaffold module.** Copy `baas-engine/pom.xml` → `baas-card/pom.xml`; change
  `<artifactId>baas-engine</artifactId>` → `baas-card`, `<finalName>app</finalName>` stays. Remove the
  `spring-boot-starter-data-redis` dependency (card has no rate-limiter in 1C). Copy `mvnw`, `mvnw.cmd`,
  `.mvn/` from `baas-engine`. Copy `baas-engine/Dockerfile` → `baas-card/Dockerfile` and change every `8080`
  → `8081` (EXPOSE, HEALTHCHECK URL, ENTRYPOINT comment) and the header comment `baas-engine` → `baas-card`.

- [ ] **Step 2: Application class.**

```java
package com.nubbank.baas.card;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CardApplication {
    public static void main(String[] args) {
        SpringApplication.run(CardApplication.class, args);
    }
}
```

- [ ] **Step 3: PORT common + multi-tenancy + security.** Port verbatim (repackaged
  `com.nubbank.baas.engine`→`com.nubbank.baas.card`):
  - `common/ApiResponse.java`, `common/BaasException.java`, `common/GlobalExceptionHandler.java`
  - `tenant/PartnerContext.java`, `tenant/PartnerTenantResolver.java`, `tenant/PartnerSchemaProvider.java`
  - `config/AuthEnforcementFilter.java`, `config/FieldEncryptor.java`, `config/SecurityConfig.java`
  - `auth/PartnerJwtService.java`
  Source paths: `baas-engine/src/main/java/com/nubbank/baas/engine/{common,tenant,config}/...` and
  `.../auth/PartnerJwtService.java` (read each before porting; `FieldEncryptor` is the `@Convert` AES-GCM
  converter referenced by `Customer`).

- [ ] **Step 4: Card `TenantProvisioningService`.** Port `tenant/TenantProvisioningService.java` but change the
  two card-specific knobs in `runTenantMigrations`:

```java
private void runTenantMigrations(String schemaName) {
    Flyway tenantFlyway = Flyway.configure()
        .dataSource(dataSource)
        .schemas(schemaName)
        .defaultSchema(schemaName)
        .locations("classpath:db/migration/card-tenant")   // ← card tenant migrations
        .table("flyway_schema_history_card")               // ← separate history; never collide with engine
        .baselineOnMigrate(true)
        .load();
    tenantFlyway.migrate();
}
```
  Keep `createSchema()` as `CREATE SCHEMA IF NOT EXISTS` (idempotent — engine may have created it already).
  The `schema_provision_log` INSERT: card shares the DB, so that table exists — but to avoid coupling, wrap
  the INSERT in a try/catch that logs-and-continues if the table is absent (card's own tests don't depend on
  it). Simpler: drop the `schema_provision_log` write entirely in card's copy (it's engine bookkeeping).

- [ ] **Step 5: `PartnerContextFilter` (JWT + ApiKey only).** Port engine's filter but **delete the
  operator-JWT branch** and the `populateAuthorities()` RBAC query. Card grants full tenant authority to any
  resolved first-party credential. Replace `populateAuthorities()` with:

```java
private void populateAuthorities() {
    PartnerContext ctx = PartnerContext.get();
    if (ctx == null) return;
    // First-party credential (API key / HMAC partner JWT) → full tenant authority.
    var auth = new UsernamePasswordAuthenticationToken(
        ctx.userId() != null ? ctx.userId() : ctx.partnerId(),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_PARTNER")));
    SecurityContextHolder.getContext().setAuthentication(auth);
}
```
  `resolveContext` keeps only the `ApiKey` branch (→ `ApiKeyResolver`) and the `Bearer` branch
  (→ `PartnerJwtService.validate` → authMode `"JWT"`). No issuer peek, no `OperatorJwtResolver`. Keep the
  `finally { PartnerContext.clear(); SecurityContextHolder.clearContext(); }`.

- [ ] **Step 6: `ApiKeyResolver` + `partner/` read entities.** Extract engine's `resolveApiKey` logic into
  `auth/ApiKeyResolver.java` (SHA-256 the raw key → query `partner_api_keys` → build `PartnerContext` from the
  joined `partner_organizations` row, authMode `"API_KEY"`, update `last_used_at`). Create read-mapped JPA
  entities `partner/PartnerOrganization.java` and `partner/PartnerApiKey.java` with
  `@Table(schema = "public", name = ...)` matching the engine columns (read `baas-engine`'s
  `partner/PartnerOrganization.java` + `db/migration/public/V1__public_schema.sql` for exact column names:
  `id, name, status, tier, environment, schema_name, contact_email, keycloak_issuer, …` and api-keys:
  `id, partner_id, key_hash, last_used_at, …`). Repositories: `findBySchemaName`/`findById` and
  `findByKeyHash`.

- [ ] **Step 7: `InternalServiceAuthFilter` (inbound HMAC).** Port engine's inbound HMAC validator (read
  `baas-engine` for `InternalServiceAuthFilter` — Phase 1F-0 added it). It guards `/internal/v1/**`: recompute
  `HmacSHA256(secret, "METHOD|PATH|TIMESTAMP|sha256hex(body)")`, compare to the `Authorization: Internal <hex>`
  header (constant-time), reject stale `X-Internal-Timestamp` (> 5 min skew) with 401. Secret from
  `${app.internal-service.shared-secret}`. If engine has no inbound filter yet (only the outbound
  `InternalServiceClient`), write it fresh against the same signing scheme shown in
  `InternalServiceClient.SigningInterceptor` (METHOD|PATH|ts|sha256(body)). Register it in `SecurityConfig` for
  a new `@Order(1)` chain with `securityMatcher("/internal/v1/**")` that permits all (the filter does the auth)
  and disables the partner filters on that path.

- [ ] **Step 8: `application.yml`.** Mirror engine; set `server.port: 8081`,
  `spring.application.name: baas-card`, Flyway public block with
  `locations: classpath:db/migration/card-public`, `table: flyway_schema_history_card`,
  `schemas: public`, `default-schema: public`; JPA `multiTenancy: SCHEMA`, `ddl-auto: none`; drop the Redis
  block; keep `app.jwt.secret`, `app.encryption.key`, `app.internal-service.shared-secret`. Add
  `application-test.yml` mirroring engine's test config (Redis autoconfig excluded, test secrets).

- [ ] **Step 9: Public + tenant migrations.**
  `db/migration/card-public/V1__card_bin_ranges.sql`:

```sql
CREATE TABLE IF NOT EXISTS public.card_bin_ranges (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bin_start    VARCHAR(8)  NOT NULL,
    bin_end      VARCHAR(8)  NOT NULL,
    partner_id   UUID        NOT NULL,
    schema_name  VARCHAR(63) NOT NULL,
    scheme       VARCHAR(20),
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT bin_range_order CHECK (bin_start <= bin_end)
);
CREATE INDEX IF NOT EXISTS idx_card_bin_ranges_lookup
    ON public.card_bin_ranges (bin_start, bin_end) WHERE active = TRUE;
CREATE INDEX IF NOT EXISTS idx_card_bin_ranges_partner
    ON public.card_bin_ranges (partner_id);
```
  `db/migration/card-tenant/V1__card_tables.sql`: `card_products`, `cards`, `card_limits` (defined in Tasks
  3–5; create the file now with all three tables so the tenant migration is single-versioned — see those tasks
  for exact columns; it is acceptable to author this SQL incrementally, but keep it as `V1` and re-run a fresh
  schema in tests).

- [ ] **Step 10: Test harness.** Port `AbstractIntegrationTest` → `AbstractCardIntegrationTest` (same static
  Testcontainers `postgres:16-alpine` initializer, `@DynamicPropertySource`, `@AfterEach PartnerContext.clear()`).
  Card tests must also create the engine-owned `public.partner_organizations`/`partner_api_keys` tables, since
  card does not own their migration. Two options — pick (a): **(a)** in `AbstractCardIntegrationTest`, run a
  one-time Flyway migration of `baas-engine`'s `db/migration/public` by adding a test-only resource copy, OR
  **(b)** add a minimal `src/test/resources/db/migration/test-public/V1__engine_public_min.sql` creating just
  the columns card reads. Use **(b)** — a minimal fixture keeps card's tests independent of engine's file tree.
  Create `support/TestPartner.java`: builds a `PartnerOrganization` row, calls
  `TenantProvisioningService.provision`, and issues an HMAC partner JWT via `PartnerJwtService.issue(...)`
  (read engine's `CustomerControllerTest.setup()` for the exact `issue(...)` argument order).

- [ ] **Step 11: Smoke test.**

```java
class CardApplicationContextTest extends AbstractCardIntegrationTest {
    @Test
    void contextLoads_and_healthReady() {
        var res = restTemplate.getForEntity("/actuator/health/readiness", String.class);
        org.assertj.core.api.Assertions.assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
```

- [ ] **Step 12: Run + commit.** `cd baas-card && ./mvnw -B test` → BUILD SUCCESS. Commit:
  `git add baas-card .github infrastructure && git commit -m "feat(card): scaffold baas-card — multi-tenancy + security + common infra ported"`

---

## Task 2: BIN ranges (public schema) + internal lookup endpoint

**Files:**
- Create: `bin/CardBinRange.java`, `bin/CardBinRangeRepository.java`, `bin/BinService.java`,
  `bin/BinController.java`, `bin/InternalBinController.java`, `bin/dto/*.java`
- Test: `bin/BinLookupTest.java`, `bin/BinRegistrationTest.java`

- [ ] **Step 1: Failing lookup test.** In `BinLookupTest extends AbstractCardIntegrationTest`: seed a
  `card_bin_ranges` row (`bin_start=506000`, `bin_end=506099`, a partnerId, `schema_name=partner_x`) directly
  via `JdbcTemplate`; HMAC-GET `/internal/v1/bins/50600012` and assert `200` + body
  `{ data: { partnerId, schemaName: "partner_x" } }`; HMAC-GET `/internal/v1/bins/99999999` asserts `404`;
  plain GET (no HMAC header) asserts `401`. Use a test HMAC signer helper (mirror
  `InternalServiceClient.SigningInterceptor`). Run → FAIL (no controller).

- [ ] **Step 2: Entity + repository.**

```java
@Entity
@Table(schema = "public", name = "card_bin_ranges")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CardBinRange {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "bin_start", nullable = false, length = 8)  private String binStart;
    @Column(name = "bin_end",   nullable = false, length = 8)  private String binEnd;
    @Column(name = "partner_id", nullable = false)             private UUID partnerId;
    @Column(name = "schema_name", nullable = false, length = 63) private String schemaName;
    @Column(length = 20) private String scheme;
    @Column(nullable = false) private boolean active;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate(){ createdAt=Instant.now(); updatedAt=createdAt; active=true; }
    @PreUpdate  void onUpdate(){ updatedAt=Instant.now(); }
}
```
```java
public interface CardBinRangeRepository extends JpaRepository<CardBinRange, UUID> {
    // Range scan: pad/truncate handled in service; compare 8-char zero-padded BIN.
    @Query("""
        SELECT b FROM CardBinRange b
        WHERE b.active = true AND b.binStart <= :bin AND b.binEnd >= :bin
        ORDER BY LENGTH(b.binStart) DESC""")
    List<CardBinRange> findMatching(@Param("bin") String bin);
    List<CardBinRange> findByPartnerIdOrderByBinStartAsc(UUID partnerId);
}
```
  > **Public-schema note:** because this entity is `@Table(schema="public")`, Hibernate hits `public`
  > regardless of `PartnerContext`. The internal lookup runs with **no** `PartnerContext` (HMAC caller) — that
  > is correct; `PartnerSchemaProvider` falls back to `public` when context is null.

- [ ] **Step 3: `BinService`.**

```java
@Service @RequiredArgsConstructor
public class BinService {
    private final CardBinRangeRepository repo;

    @Transactional(readOnly = true)
    public Optional<CardBinRange> lookup(String bin) {
        String norm = normalize(bin);                 // first 6–8 digits, left-aligned, zero-pad to 8
        return repo.findMatching(norm).stream().findFirst();
    }

    @Transactional
    public CardBinRange register(String binStart, String binEnd, String scheme) {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null) throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        if (normalize(binStart).compareTo(normalize(binEnd)) > 0)
            throw BaasException.badRequest("INVALID_BIN_RANGE", "bin_start must be ≤ bin_end");
        return repo.save(CardBinRange.builder()
            .binStart(normalize(binStart)).binEnd(normalize(binEnd))
            .partnerId(UUID.fromString(ctx.partnerId()))    // captured from context — never trusted from body
            .schemaName(ctx.schemaName())
            .scheme(scheme).build());
    }

    @Transactional(readOnly = true)
    public List<CardBinRange> listForCurrentPartner() {
        PartnerContext ctx = PartnerContext.get();
        return repo.findByPartnerIdOrderByBinStartAsc(UUID.fromString(ctx.partnerId()));
    }

    static String normalize(String bin) {
        String digits = bin.replaceAll("\\D", "");
        String head = digits.length() >= 8 ? digits.substring(0, 8) : digits;
        return String.format("%-8s", head).replace(' ', '0');
    }
}
```
  > **Why capture `partner_id`/`schema_name` from context, not the request body:** a partner must not be able
  > to register a BIN that resolves to another tenant's schema. The authenticated `PartnerContext` is the only
  > trusted source of the owning tenant.

- [ ] **Step 4: Controllers + DTOs.**
  - `InternalBinController` (`/internal/v1/bins/{bin}`, GET): calls `binService.lookup(bin)` → `200`
    `ApiResponse.ok(new BinLookupResponse(partnerId, schemaName))` or throws
    `BaasException.notFound("BIN_NOT_FOUND", ...)`. No `@PreAuthorize` (HMAC filter already gated it).
  - `BinController` (`/baas/v1/bins`): `POST` (register from `RegisterBinRangeRequest{binStart,binEnd,scheme}`),
    `GET` (list for current partner). `ResponseEntity<ApiResponse<...>>`.
  - DTOs: `RegisterBinRangeRequest(@NotBlank String binStart, @NotBlank String binEnd, String scheme)`,
    `BinRangeResponse(UUID id, String binStart, String binEnd, String scheme, boolean active)`,
    `BinLookupResponse(UUID partnerId, String schemaName)`.

- [ ] **Step 5: Run + commit.** `./mvnw -B test` → green. Commit
  `feat(card): public BIN-range table + internal HMAC lookup (FEP contract §2)`.

---

## Task 3: Card products (tenant schema)

**Files:** `product/CardProduct.java`, `CardProductRepository.java`, `CardProductService.java`,
`CardProductController.java`, `dto/*`; Test: `product/CardProductTest.java`

- [ ] **Step 1: Failing test.** Authenticated (TestPartner JWT) `POST /baas/v1/card-products`
  `{name:"Virtual Debit", cardType:"DEBIT", currency:"NGN"}` → `201` with `id`; `GET /baas/v1/card-products`
  lists it; a second POST with a duplicate `name` → `409 DUPLICATE_PRODUCT`. Run → FAIL.

- [ ] **Step 2: Entity (tenant — NO schema annotation).**

```java
@Entity @Table(name = "card_products")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CardProduct {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, unique = true, length = 100) private String name;
    @Enumerated(EnumType.STRING) @Column(name="card_type", nullable=false, length=20) private CardType cardType;
    @Column(nullable = false, length = 3) private String currency;          // ISO 4217 alpha
    @Column(name = "bin_start", length = 8) private String binStart;        // optional link to a registered BIN
    @Column(name = "default_daily_limit", precision = 19, scale = 4) private BigDecimal defaultDailyLimit;
    @Column(nullable = false) private boolean active;
    @Version private Long version;
    @Column(name="created_at", updatable=false) private Instant createdAt;
    @Column(name="updated_at") private Instant updatedAt;
    @PrePersist void c(){createdAt=Instant.now();updatedAt=createdAt;active=true;}
    @PreUpdate  void u(){updatedAt=Instant.now();}
}
```
  `CardType` enum: `DEBIT, PREPAID, CREDIT`.

- [ ] **Step 3: Repository + Service + Controller.** Repository: `existsByName`, `findAll`. Service mirrors
  `CustomerService` (`requireContext()`, `@Transactional`, conflict on duplicate name, DTO mapping). Controller
  `/baas/v1/card-products` POST + GET, `ResponseEntity<ApiResponse<...>>`. (No operator `@PreAuthorize` —
  deferred; the chain already enforces first-party auth via `AuthEnforcementFilter`.)

- [ ] **Step 4: Add `card_products` to `card-tenant/V1__card_tables.sql`** (columns matching the entity:
  snake_case, `numeric(19,4)`, `version bigint default 0`, timestamptz). Run → green. Commit
  `feat(card): card products (tenant)`.

---

## Task 4: Card issuance + lifecycle state machine (tenant schema)

**Files:** `card/Card.java`, `CardStatus.java`, `CardType.java` (reuse Task 3's), `CardRepository.java`,
`CardService.java`, `CardController.java`, `dto/*`; Test: `card/CardLifecycleTest.java`

- [ ] **Step 1: Failing state-machine test.** Issue a virtual card (`POST /baas/v1/cards`
  `{productId, customerRef:"ext-1", virtual:true}`) → `201`, status `ISSUED`. Then:
  `POST /baas/v1/cards/{id}?command=activate` → `ACTIVE`; `?command=block` → `BLOCKED`; `?command=unblock`
  → `ACTIVE`; `?command=cancel` → `CANCELLED`; a `?command=activate` from `CANCELLED` → `409 INVALID_TRANSITION`.
  Assert PAN is never returned in full (`maskedPan` like `"506000******1234"`, no `pan` field). Run → FAIL.

- [ ] **Step 2: Enums + entity.**

```java
public enum CardStatus { ISSUED, ACTIVE, BLOCKED, CANCELLED, EXPIRED }
```
```java
@Entity @Table(name = "cards")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Card {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name="product_id", nullable=false) private UUID productId;
    @Column(name="customer_ref", length=100) private String customerRef;
    @Convert(converter = FieldEncryptor.class)
    @Column(name="pan_encrypted", nullable=false, length=500) private String panEncrypted;  // full PAN, AES-GCM
    @Column(name="pan_hash", nullable=false, unique=true, length=64) private String panHash; // HMAC-SHA256 hex — deterministic lookup
    @Column(name="pan_last4", nullable=false, length=4) private String panLast4;
    @Column(name="bin", nullable=false, length=8) private String bin;
    @Column(name="expiry_ym", nullable=false, length=4) private String expiryYm;            // YYMM
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) private CardStatus status;
    @Column(nullable=false) private boolean virtual;
    @Version private Long version;
    @Column(name="created_at", updatable=false) private Instant createdAt;
    @Column(name="updated_at") private Instant updatedAt;
    @PrePersist void c(){createdAt=Instant.now();updatedAt=createdAt;if(status==null)status=CardStatus.ISSUED;}
    @PreUpdate  void u(){updatedAt=Instant.now();}
    public String maskedPan(){ return bin + "******" + panLast4; }
}
```
  > **PAN handling:** the full PAN is stored only in `pan_encrypted` (AES-GCM via the ported `FieldEncryptor`).
  > Responses expose `maskedPan()` only — never the cleartext PAN or the encrypted blob. This satisfies the
  > "never store/log raw PAN" rule.
  > **`pan_hash` (deterministic lookup):** AES-GCM is non-deterministic, so it cannot be queried. The
  > authorization stub (Task 6) and any "find card by PAN" path resolve via `pan_hash` =
  > `HMAC-SHA256(app.encryption.key, fullPan)` hex. Add a tiny `card/PanHasher.java` helper
  > (`@Component`; `String hash(String pan)` using `javax.crypto.Mac "HmacSHA256"`, key bytes from
  > `${app.encryption.key}`). `CardRepository` adds `Optional<Card> findByPanHash(String panHash)`. The unique
  > constraint on `pan_hash` prevents duplicate cards within a tenant.

- [ ] **Step 3: Service with transition guard.**

```java
private static final Map<CardStatus, Set<CardStatus>> ALLOWED = Map.of(
    CardStatus.ISSUED,  Set.of(CardStatus.ACTIVE, CardStatus.CANCELLED),
    CardStatus.ACTIVE,  Set.of(CardStatus.BLOCKED, CardStatus.CANCELLED, CardStatus.EXPIRED),
    CardStatus.BLOCKED, Set.of(CardStatus.ACTIVE, CardStatus.CANCELLED),
    CardStatus.CANCELLED, Set.of(),
    CardStatus.EXPIRED, Set.of());

private void transition(Card card, CardStatus target) {
    if (!ALLOWED.getOrDefault(card.getStatus(), Set.of()).contains(target))
        throw BaasException.conflict("INVALID_TRANSITION",
            "Cannot move card from " + card.getStatus() + " to " + target);
    card.setStatus(target);
}
```
  `executeCommand(id, command)` maps `activate→ACTIVE, block→BLOCKED, unblock→ACTIVE, cancel→CANCELLED` and
  calls `transition`. `issue(req)` generates a test PAN: `bin` from the product's `binStart` (or a registered
  BIN), random digits, Luhn check digit; `expiryYm` = now + 3y; `panLast4` = last 4; `panEncrypted` = full PAN
  (encrypted by `FieldEncryptor`); **`panHash` = `panHasher.hash(fullPan)`** (set before save so the unique
  constraint and the Task-6 lookup work). Never log the generated PAN.

- [ ] **Step 4: Controller + DTOs.** `/baas/v1/cards` POST (issue) + GET (list) +
  `POST /{id}?command=...`. `CardResponse(UUID id, UUID productId, String customerRef, String maskedPan,
  String expiryYm, CardStatus status, boolean virtual, Instant createdAt)` — **no PAN field**.

- [ ] **Step 5: Add `cards` table to tenant migration.** Run → green. Commit
  `feat(card): issuance + lifecycle state machine (PAN encrypted, masked responses)`.

---

## Task 5: Per-card limits (tenant schema)

**Files:** `limit/CardLimit.java`, `CardLimitRepository.java`, `CardLimitService.java`, `dto/*`;
wire into `CardController` (`/baas/v1/cards/{id}/limits` PUT + GET). Test: `limit/CardLimitTest.java`

- [ ] **Step 1: Failing test.** `PUT /baas/v1/cards/{id}/limits`
  `{dailyPurchase:100000, dailyWithdrawal:50000, perTxn:25000, monthly:1000000}` → `200`; `GET` returns them;
  a `perTxn` greater than `dailyPurchase` → `400 INVALID_LIMITS`; negative → `400`. Run → FAIL.

- [ ] **Step 2: Entity (UNIQUE card_id).**

```java
@Entity @Table(name = "card_limits", uniqueConstraints = @UniqueConstraint(columnNames = "card_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CardLimit {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name="card_id", nullable=false) private UUID cardId;
    @Column(name="daily_purchase",   precision=19, scale=4) private BigDecimal dailyPurchase;
    @Column(name="daily_withdrawal", precision=19, scale=4) private BigDecimal dailyWithdrawal;
    @Column(name="per_txn",          precision=19, scale=4) private BigDecimal perTxn;
    @Column(name="monthly",          precision=19, scale=4) private BigDecimal monthly;
    @Version private Long version;
    @Column(name="updated_at") private Instant updatedAt;
    @PreUpdate @PrePersist void t(){ updatedAt=Instant.now(); }
}
```

- [ ] **Step 3: Service.** `requireContext()`; upsert by `cardId` (`findByCardId` or new); validate all
  non-null amounts `>= 0` and `perTxn <= dailyPurchase` (when both set) else
  `BaasException.badRequest("INVALID_LIMITS", ...)`. Verify the card exists in the current tenant first
  (`cardRepository.findById` → 404 otherwise).

- [ ] **Step 4: Wire controller, add `card_limits` to tenant migration, run, commit**
  `feat(card): per-card limits`.

---

## Task 6: Internal card-authorization-decision stub

**Files:** `authorize/AuthorizationDecisionService.java`, `authorize/InternalAuthorizationController.java`,
`authorize/dto/*`; Test: `authorize/AuthorizationDecisionTest.java`

- [ ] **Step 1: Failing decision test.** HMAC `POST /internal/v1/authorize`
  `{partnerId, schemaName, pan:"<the issued card's PAN>", amountMinor:5000, currency:"566"}` against an
  `ACTIVE` card with sufficient limits → `200 { decision:"APPROVE", responseCode:"00" }`. Against a `BLOCKED`
  card → `responseCode:"62"`; amount over `perTxn` → `"61"` (decline, limit exceeded); a PAN with no matching
  card → `"56"` (no such card). No HMAC header → `401`. The request carries `pan` (the only identifier the FEP
  has) + `schemaName` so the stub resolves the card by `pan_hash` within the tenant. Run → FAIL.

- [ ] **Step 2: Service.**

```java
@Service @RequiredArgsConstructor
public class AuthorizationDecisionService {
    private final CardRepository cardRepo;
    private final CardLimitRepository limitRepo;
    private final PanHasher panHasher;

    public AuthorizationDecisionResponse decide(AuthorizationDecisionRequest req) {
        // Caller is the FEP over HMAC; it resolved the tenant via the BIN lookup and passes schemaName + PAN.
        PartnerContext.set(new PartnerContext(req.partnerId(), req.schemaName(),
            "PRO", "PRODUCTION", "INTERNAL", null));
        try {
            // FEP only has the PAN (ISO 8583 DE2) — resolve the card by deterministic pan_hash, not by id.
            Card card = cardRepo.findByPanHash(panHasher.hash(req.pan())).orElse(null);
            if (card == null)                        return decline("56");   // no such card
            if (card.getStatus() == CardStatus.BLOCKED || card.getStatus() == CardStatus.CANCELLED)
                                                     return decline("62");   // restricted
            if (card.getStatus() != CardStatus.ACTIVE) return decline("54"); // not usable (ISSUED/EXPIRED)
            BigDecimal amount = new BigDecimal(req.amountMinor()).movePointLeft(2);
            var lim = limitRepo.findByCardId(card.getId()).orElse(null);
            if (lim != null && lim.getPerTxn() != null && amount.compareTo(lim.getPerTxn()) > 0)
                                                     return decline("61");   // exceeds limit
            // Phase 1C: balance check is a stub (always sufficient). Real balance via baas-engine in Phase 2.
            return new AuthorizationDecisionResponse("APPROVE", "00", "Approved");
        } finally {
            PartnerContext.clear();
        }
    }
    private AuthorizationDecisionResponse decline(String rc){
        return new AuthorizationDecisionResponse("DECLINE", rc, "Declined");
    }
}
```
  > **`PartnerContext.set` here, cleared in `finally`:** this is the one place card sets context *itself* (the
  > FEP passed the resolved tenant). The `finally` clear is mandatory — a leaked ThreadLocal here would route
  > the next pooled-thread request to the wrong tenant. Mirror the spec §10 invariant.

- [ ] **Step 3: Controller + DTOs.** `InternalAuthorizationController` `POST /internal/v1/authorize` (HMAC,
  no `@PreAuthorize`). `AuthorizationDecisionRequest(String partnerId, String schemaName, String pan,
  long amountMinor, String currency)` — **field-for-field identical to FEP's `AuthorizationDecision.Request`**
  (cross-track contract; if one side changes, change both); `AuthorizationDecisionResponse(String decision,
  String responseCode, String message)`. **Never log `pan`.**

- [ ] **Step 4: Run + commit** `feat(card): internal authorization-decision stub (ISO-8583 RC mapping)`.

---

## Task 7: Infrastructure — Dockerfile (done T1), compose, CI

**Files:** `infrastructure/docker-compose.yml` (add block), `.github/workflows/baas-card-ci.yml`

- [ ] **Step 1: Compose block.** Add a `baas-card` service mirroring the `baas-engine` block: build context
  `../baas-card`, `image: baas-card:local`, `depends_on: postgres (service_healthy)`, env
  `DATASOURCE_URL/USERNAME/PASSWORD`, `JWT_SECRET`, `ENCRYPTION_KEY`, `INTERNAL_SERVICE_SECRET`,
  `SPRING_PROFILES_ACTIVE`, `ports: "${BAAS_CARD_PORT:-8081}:8081"`, healthcheck on
  `http://127.0.0.1:8081/actuator/health/readiness`.

- [ ] **Step 2: CI workflow.** Copy `.github/workflows/baas-engine-ci.yml` → `baas-card-ci.yml`; change
  `name`, the `paths` filters (`baas-card/**`, `.github/workflows/baas-card-ci.yml`), `working-directory:
  baas-card`, and the image tag (`ghcr.io/${lower_owner}/baas-card`), build context `baas-card`. Keep the
  pinned action SHAs identical. **Create via `cat > … << 'EOF'`/Write per the repo's tooling** (workflow files
  are fine to Write in this repo; engine/ncube workflows already exist).

- [ ] **Step 3: Commit** `chore(card): docker-compose block + GHCR CI workflow`.

---

## Task 8: Session Completion Gate — docs, deferred registry, contract note

**Files:** `baas-log.md`, `CLAUDE.md`, `docs/deferred-items.md`, `docs/api-reference.html`,
`docs/contracts/phase1c-interfaces.md`

- [ ] **Step 1: Run the full suite** `cd baas-card && ./mvnw -B test` → record `Tests run: N, Failures: 0`.
- [ ] **Step 2: Deferred registry.** Append to `docs/deferred-items.md`:
  - `DEF-1C-20 | Operator-JWT/Keycloak RBAC on baas-card endpoints | First-party auth only in 1C | Phase 1C (Stage 4 — Backoffice) | Track-Card`
  - `DEF-1C-21 | Decouple card from engine's public partner tables (own partner mirror) | Shared DB acceptable in 1C | Phase 2 | Track-Card`
  - `DEF-1C-22 | Cross-service tenant provisioning trigger (engine→card schema objects) | Card tests self-provision in 1C | Phase 1C (Stage 5) | Track-Card`
  - `DEF-1C-23 | Card authorization balance check (real, via baas-engine) | Stub always-sufficient in 1C | Phase 2 | Track-Card`
- [ ] **Step 3: `baas-log.md`** — new top entry: Session N, summary + final SHA, New/Updated Files table, Key
  Decisions (public BIN table; card Flyway history; first-party-only auth; stateless decision stub context
  discipline), Build Verification, **Confirmed Platform Versions** block for `baas-card` (Spring Boot 3.5.3,
  Java 21, last commit SHA).
- [ ] **Step 4: `CLAUDE.md`** — Confirmed Platform Versions: add `baas-card` row; Module Catalogue: mark Card
  ✅ with endpoint inventory; add any new gotchas (public-schema BIN lookup runs with null context → public
  fallback; separate Flyway history table; decision-stub `PartnerContext.set`+`finally` discipline).
- [ ] **Step 5: API docs** — add the card endpoints to `docs/api-reference.html` (`/baas/v1/card-products`,
  `/baas/v1/cards`, `/baas/v1/cards/{id}` command, `/baas/v1/cards/{id}/limits`, `/baas/v1/bins`) and note the
  two internal HMAC endpoints (`/internal/v1/bins/{bin}`, `/internal/v1/authorize`).
- [ ] **Step 6: Contract note** — in `docs/contracts/phase1c-interfaces.md` §2, mark the BIN lookup as
  **implemented by Track-Card** at `GET /internal/v1/bins/{bin}` and document the `/internal/v1/authorize`
  request/response shape (FEP consumes it in Track-FEP Task 6).
- [ ] **Step 7: Commit** `docs(baas-log+claude): Track-Card complete — baas-card card spine`. Then finish the
  branch via `superpowers:finishing-a-development-branch` → **Option 2 (push + PR into `main`)**.

---

## Self-review checklist (run before opening the PR)

- [ ] Spec §6.6 coverage: products ✓, issuance (virtual + physical-order stub — note: physical-order is the
  `virtual=false` flag + ISSUED status; no bureau in 1C) ✓, lifecycle ✓, limits ✓, PAN encrypted ✓, BIN
  ranges ✓, authorization-decision stub ✓, HMAC inter-service ✓.
- [ ] No PAN in any response DTO or log line (grep `getPanEncrypted`, `panEncrypted` in controllers/logs).
- [ ] Every tenant entity has **no** `@Table(schema=...)`; `CardBinRange` **has** `schema="public"`.
- [ ] Every `PartnerContext.set` has a matching `finally { clear() }` (filter + decision stub).
- [ ] Type names consistent: `CardStatus`, `CardType`, `BinLookupResponse`, `AuthorizationDecisionResponse`
  identical across tasks.
- [ ] Flyway: card uses `flyway_schema_history_card`; never writes engine's `flyway_schema_history`.
- [ ] `./mvnw -B test` green; new service has Dockerfile + compose + CI.
