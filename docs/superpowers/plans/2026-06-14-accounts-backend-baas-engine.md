# Accounts Track — Backend (baas-engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `baas-engine` REST surface the Accounts backoffice needs — a paginated account list (status filter + account-number search), a widened account detail response, an account lifecycle state machine (freeze / unfreeze / close) with a required reason and append-only history, money-movement status gating (freeze blocks debits but allows credits), an optional opening-deposit on account open, and `@PreAuthorize` guards on every endpoint.

**Architecture:** Extends the existing `com.nubbank.baas.engine.account` package. Tenant-scoped (Hibernate routes every query to the partner schema via `PartnerContext`). Lifecycle transitions are path-segment commands writing an audit row to a new `account_status_events` table in one transaction — identical in shape to the just-shipped `customer_kyc_events`. The list query uses a JPQL `JOIN FETCH a.customer` with an explicit `countQuery` (Spring Data cannot derive a count over a `JOIN FETCH` — known gotcha) so `customerName` resolves from the eagerly-loaded `Account.customer` with no second round-trip. A new `UPDATE_ACCOUNT` permission is seeded in V6 (the first-party full-tenant-authority set is data-driven — `AuthorityResolver.fullTenantAuthorities()` returns `permissionRepo.findAllCodes()` — so seeding the row is sufficient; there is no Java authority list to edit).

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, Flyway (tenant schema migrations), PostgreSQL 16, Testcontainers + JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-14-accounts-track-design.md`

**Branch:** `feat/baas-engine-accounts-lifecycle` (already created off `main`, currently checked out). Ships as its own PR (zero overlap with the frontend PR).

---

## Pre-flight

- [ ] **Confirm branch**

```bash
cd ~/nubbank-baas
git branch --show-current   # expect: feat/baas-engine-accounts-lifecycle
git status --short          # expect: clean working tree (only the spec already committed)
```

If the branch does not exist yet:

```bash
cd ~/nubbank-baas
git checkout main && git pull --ff-only origin main
git checkout -b feat/baas-engine-accounts-lifecycle
```

## Shared test harness (referenced by every integration task below)

All integration tests extend `AbstractIntegrationTest` (Testcontainers Postgres started once for the suite, `TestRestTemplate restTemplate`, `PartnerContext.clear()` in `@AfterEach`). Every test class reproduces this `@BeforeEach`: it provisions a fresh tenant schema, **persists a real `Customer` directly via the repository inside a `PartnerContext`** (the `accounts.customer_id` FK is `NOT NULL` — an account cannot be opened without a customer; this bit the Customers track), and issues a first-party partner JWT that carries full tenant authority. **Reproduce this block in each new test class** (adjust the partner name/email per class):

```java
@Autowired private PartnerJwtService jwtService;
@Autowired private PartnerOrganizationRepository orgRepo;
@Autowired private TenantProvisioningService provisioningService;
@Autowired private CustomerRepository customerRepo;

private String jwt;
private String schemaName;
private UUID orgId;
private UUID customerId;

@BeforeEach
void setup() {
    schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
    PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
        .name("Acct Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
        .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
        .contactEmail("at@partner.com").build());
    orgId = org.getId();
    provisioningService.provision(org.getId(), schemaName);

    // The account FK requires a customer — persist one directly in the tenant schema.
    PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
        "SANDBOX", "SANDBOX", "TEST", null));
    customerId = customerRepo.save(Customer.builder()
        .firstNameEncrypted("Ada").lastNameEncrypted("Lovelace").build()).getId();
    PartnerContext.clear();

    jwt = jwtService.issue(UUID.randomUUID().toString(), "at@partner.com", "PARTNER_ADMIN",
        org.getId().toString(), "Acct Test", schemaName, "SANDBOX", "SANDBOX");
}

private HttpHeaders auth() {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(jwt);
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
}

/** Open an account via the API and return its id. */
private UUID openAccount(Map<String, Object> body) {
    Map<String, Object> withCustomer = new java.util.HashMap<>(body);
    withCustomer.putIfAbsent("customerId", customerId.toString());
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(withCustomer, auth()), Map.class);
    return UUID.fromString((String) ((Map<?, ?>) r.getBody().get("data")).get("id"));
}
```

Required imports for the harness (per test class):

```java
import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.customer.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
```

A first-party partner JWT gets full tenant authority (every permission code, resolved from the `permissions` table via `AuthorityResolver.fullTenantAuthorities()` → `permissionRepo.findAllCodes()`), so all `@PreAuthorize` checks pass — **provided the permission row exists in the schema**. That is why `UPDATE_ACCOUNT` must be seeded (Task 1) before the lifecycle tests can issue freeze/unfreeze/close.

---

## Task 1: V6 migration — `account_status_events` table + `UPDATE_ACCOUNT` permission

**Files:**
- Create: `baas-engine/src/main/resources/db/migration/tenant/V6__account_status_events.sql`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountSchemaV6Test.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AccountSchemaV6Test extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void v6_addsStatusEventsTableAndUpdateAccountPermission() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("V6").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("v6@test.com").build());
        provisioningService.provision(org.getId(), schema);

        assertThat(jdbc.queryForObject("SELECT to_regclass(?)", String.class,
            schema + ".account_status_events")).isNotNull();

        Integer perm = jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".permissions WHERE code = 'UPDATE_ACCOUNT'",
            Integer.class);
        assertThat(perm).isEqualTo(1);

        // PARTNER_ADMIN must hold the new permission (V3 granted only the V1/V2 codes).
        Integer grant = jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".role_permissions rp "
            + "JOIN " + schema + ".roles r ON r.id = rp.role_id "
            + "JOIN " + schema + ".permissions p ON p.id = rp.permission_id "
            + "WHERE r.name = 'PARTNER_ADMIN' AND p.code = 'UPDATE_ACCOUNT'",
            Integer.class);
        assertThat(grant).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountSchemaV6Test -q`
Expected: FAIL — `to_regclass` returns null (table missing) / permission count 0.

- [ ] **Step 3: Write the migration**

`baas-engine/src/main/resources/db/migration/tenant/V6__account_status_events.sql`:

```sql
-- Accounts track: lifecycle history (freeze/unfreeze/close) + UPDATE_ACCOUNT permission.

-- Append-only audit table: rows are only ever INSERTed, never UPDATEd or DELETEd.
-- No `version` column is needed because optimistic locking does not apply here.
CREATE TABLE account_status_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID NOT NULL REFERENCES accounts(id),
    from_status VARCHAR(50) NOT NULL,
    to_status   VARCHAR(50) NOT NULL,
    reason      TEXT NOT NULL,
    changed_by  VARCHAR(255),
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_account_status_events_account
    ON account_status_events (account_id, changed_at DESC);

-- New permission: one code for all three lifecycle commands (mirrors single UPDATE_CUSTOMER).
INSERT INTO permissions (grouping, code, entity_name, action_name) VALUES
  ('accounts', 'UPDATE_ACCOUNT', 'ACCOUNT', 'UPDATE');

-- V3's PARTNER_ADMIN cross-join was bounded to the V1/V2 permission codes and does NOT
-- auto-extend (see DEF-1C-16). Grant the new code to PARTNER_ADMIN explicitly here.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'UPDATE_ACCOUNT'
   WHERE r.name = 'PARTNER_ADMIN';

-- ACCOUNT_OFFICER -> account maintenance gets the lifecycle permission too.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'UPDATE_ACCOUNT'
   WHERE r.name = 'ACCOUNT_OFFICER';
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountSchemaV6Test -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/resources/db/migration/tenant/V6__account_status_events.sql \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountSchemaV6Test.java
git commit -m "feat(engine): V6 tenant migration — account_status_events + UPDATE_ACCOUNT permission

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `AccountStatusEvent` entity + `AccountStatusEventRepository`

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountStatusEvent.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountStatusEventRepository.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountStatusEventRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AccountStatusEventRepositoryTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private AccountStatusEventRepository eventRepo;

    @Test
    void findByAccountId_returnsOldestFirst() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Ev").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("ev@test.com").build());
        provisioningService.provision(org.getId(), schema);
        UUID accountId = UUID.randomUUID();

        try {
            PartnerContext.set(new PartnerContext(org.getId().toString(), schema,
                "SANDBOX", "SANDBOX", "TEST", null));
            eventRepo.save(AccountStatusEvent.builder().accountId(accountId)
                .fromStatus("ACTIVE").toStatus("FROZEN").reason("first").changedBy("op").build());
            eventRepo.save(AccountStatusEvent.builder().accountId(accountId)
                .fromStatus("FROZEN").toStatus("ACTIVE").reason("second").changedBy("op").build());

            List<AccountStatusEvent> events = eventRepo.findByAccountIdOrderByChangedAtAsc(accountId);
            assertThat(events).hasSize(2);
            assertThat(events.get(0).getReason()).isEqualTo("first");
            assertThat(events.get(1).getReason()).isEqualTo("second");
        } finally {
            PartnerContext.clear();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountStatusEventRepositoryTest -q`
Expected: FAIL — `AccountStatusEvent` / `AccountStatusEventRepository` do not exist (compile error).

- [ ] **Step 3: Write the entity + repository**

`baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountStatusEvent.java`:

```java
package com.nubbank.baas.engine.account;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_status_events")
// NO schema annotation — this is a TENANT table; Hibernate routes via PartnerSchemaProvider.
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccountStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    // from/to status are stored as plain String, not the AccountStatus enum, on purpose:
    // this is an append-only audit record. Keeping it decoupled from the live enum means
    // a future enum change can never break deserialization of historical rows. The write
    // path only ever passes AccountStatus.name(), so invalid values cannot be persisted.
    @Column(name = "from_status", nullable = false, length = 50)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 50)
    private String toStatus;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "changed_by", length = 255)
    private String changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @PrePersist
    void onCreate() {
        if (changedAt == null) changedAt = Instant.now();
    }
}
```

`baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountStatusEventRepository.java`:

```java
package com.nubbank.baas.engine.account;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AccountStatusEventRepository extends JpaRepository<AccountStatusEvent, UUID> {
    List<AccountStatusEvent> findByAccountIdOrderByChangedAtAsc(UUID accountId);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountStatusEventRepositoryTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountStatusEvent.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountStatusEventRepository.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountStatusEventRepositoryTest.java
git commit -m "feat(engine): AccountStatusEvent entity + repository

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `AccountCommand` enum + lifecycle DTOs

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountCommand.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountStatusEventResponse.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountTransitionRequest.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountCommandTest.java`

> These are pure value types with no behaviour to integration-test; one tiny unit test fixes their shape so later tasks compile against a stable contract.

- [ ] **Step 1: Write the failing unit test**

```java
package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.account.dto.AccountStatusEventResponse;
import com.nubbank.baas.engine.account.dto.AccountTransitionRequest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AccountCommandTest {

    @Test
    void command_hasExactlyFreezeUnfreezeClose() {
        assertThat(AccountCommand.values())
            .containsExactly(AccountCommand.FREEZE, AccountCommand.UNFREEZE, AccountCommand.CLOSE);
    }

    @Test
    void transitionRequest_exposesReason() {
        assertThat(new AccountTransitionRequest("legal hold").reason()).isEqualTo("legal hold");
    }

    @Test
    void statusEventResponse_carriesAuditFields() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.now();
        AccountStatusEventResponse r =
            new AccountStatusEventResponse(id, "ACTIVE", "FROZEN", "why", "op", at);
        assertThat(r.fromStatus()).isEqualTo("ACTIVE");
        assertThat(r.toStatus()).isEqualTo("FROZEN");
        assertThat(r.changedAt()).isEqualTo(at);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountCommandTest -q`
Expected: FAIL — `AccountCommand`, `AccountTransitionRequest`, `AccountStatusEventResponse` do not exist (compile error).

- [ ] **Step 3: Write the enum + DTOs**

`baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountCommand.java`:

```java
package com.nubbank.baas.engine.account;

public enum AccountCommand { FREEZE, UNFREEZE, CLOSE }
```

`baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountStatusEventResponse.java`:

```java
package com.nubbank.baas.engine.account.dto;

import java.time.Instant;
import java.util.UUID;

public record AccountStatusEventResponse(
    UUID id,
    String fromStatus,
    String toStatus,
    String reason,
    String changedBy,
    Instant changedAt
) {}
```

`baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountTransitionRequest.java`:

```java
package com.nubbank.baas.engine.account.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountTransitionRequest(
    @NotBlank(message = "reason is required") String reason
) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountCommandTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountCommand.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountStatusEventResponse.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountTransitionRequest.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountCommandTest.java
git commit -m "feat(engine): AccountCommand enum + transition/status-event DTOs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Widened detail — `AccountDetailResponse` + `GET /{id}` upgrade

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountDetailResponse.java`
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java` (`getById` return type + `toDetail` helper)
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java` (`getById` return type + `@PreAuthorize`)
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountDetailTest.java`

> The existing `open(...)` still returns the lean `AccountResponse` and the controller's `open` keeps returning it — only `getById` widens. (Spec §4: "open response returns the new detail shape" is satisfied later in Task 8, where `open` is changed to return `AccountDetailResponse`. Until then, leave `open` on `AccountResponse` so each task compiles independently. **Task 8 flips `open` to `AccountDetailResponse`.**)

- [ ] **Step 1: Write the failing test** (reproduce the shared harness fields + `setup` + `auth` + `openAccount` from the preamble at the top of the class)

```java
@Test
void detail_returnsWidenedFields() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings", "currencyCode", "NGN"));

    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id, HttpMethod.GET,
        new HttpEntity<>(auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");

    assertThat(data.get("customerId")).isEqualTo(customerId.toString());
    assertThat(data.get("customerName")).isEqualTo("Ada Lovelace");
    assertThat(data.get("accountTypeLabel")).isEqualTo("Savings");
    assertThat(data.get("status")).isEqualTo("ACTIVE");
    assertThat(data.get("currencyCode")).isEqualTo("NGN");
    assertThat(((Number) data.get("balance")).doubleValue()).isEqualTo(0.0);
    assertThat(((Number) data.get("availableBalance")).doubleValue()).isEqualTo(0.0);
    assertThat(((Number) data.get("minimumBalance")).doubleValue()).isEqualTo(0.0);
    assertThat(data.get("allowOverdraft")).isEqualTo(false);
    assertThat(data).containsKey("openedAt");
}

@Test
void detail_unknownId_404() {
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + UUID.randomUUID(),
        HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountDetailTest -q`
Expected: FAIL — response has no `customerName`/`availableBalance`/`openedAt` keys (current `AccountResponse` is lean).

- [ ] **Step 3: Implement**

`baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountDetailResponse.java`:

```java
package com.nubbank.baas.engine.account.dto;

import com.nubbank.baas.engine.account.AccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountDetailResponse(
    UUID id,
    String accountNumber,
    UUID customerId,
    String customerName,
    String accountTypeLabel,
    AccountStatus status,
    BigDecimal balance,
    BigDecimal availableBalance,
    String currencyCode,
    BigDecimal minimumBalance,
    boolean allowOverdraft,
    BigDecimal overdraftLimit,
    Instant openedAt
) {}
```

In `AccountService.java` change `getById` to return the detail shape and add a `toDetail` helper. Replace the existing `getById`:

```java
    @Transactional(readOnly = true)
    public AccountDetailResponse getById(UUID id) {
        requireContext();
        return toDetail(findOrThrow(id));
    }
```

Add the helper (place beside `toResponse`):

```java
    private AccountDetailResponse toDetail(Account a) {
        Customer c = a.getCustomer();
        String customerName = (c.getFirstNameEncrypted() + " " + c.getLastNameEncrypted()).trim();
        return new AccountDetailResponse(a.getId(), a.getAccountNumber(),
            c.getId(), customerName, a.getAccountTypeLabel(), a.getStatus(),
            a.getBalance(), a.getAvailableBalance(), a.getCurrencyCode(),
            a.getMinimumBalance(), a.isAllowOverdraft(), a.getOverdraftLimit(),
            a.getCreatedAt());
    }
```

> `findOrThrow` already loads the `Account`; the `@ManyToOne` `customer` is `FetchType.LAZY` but the read runs inside the `@Transactional(readOnly = true)` session, so `c.getFirstNameEncrypted()` triggers the lazy load (decrypted by the entity converter) within the open session — no `LazyInitializationException`. `import com.nubbank.baas.engine.customer.Customer;` is already present in `AccountService`.

In `AccountController.java` add `import org.springframework.security.access.prepost.PreAuthorize;` and change `getById`:

```java
    @PreAuthorize("hasAuthority('READ_ACCOUNT')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountDetailResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getById(id)));
    }
```

(`AccountDetailResponse` is imported via the existing wildcard `import com.nubbank.baas.engine.account.dto.*;`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountDetailTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountDetailResponse.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountDetailTest.java
git commit -m "feat(engine): widen GET /accounts/{id} to AccountDetailResponse + READ_ACCOUNT guard

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Lifecycle state machine — `transition` + freeze/unfreeze/close endpoints

**Files:**
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java` (`transition`, `target`, `currentPrincipal`; inject `AccountStatusEventRepository`)
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java` (3 transition endpoints)
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountTransitionTest.java`

- [ ] **Step 1: Write the failing test** (reproduce the shared harness)

```java
@Test
void freeze_movesActiveToFrozen() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze",
        HttpMethod.POST, new HttpEntity<>(Map.of("reason", "legal hold"), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Map<?,?>) r.getBody().get("data")).get("status")).isEqualTo("FROZEN");
}

@Test
void unfreeze_movesFrozenToActive() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze", HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "hold"), auth()), Map.class);
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/unfreeze",
        HttpMethod.POST, new HttpEntity<>(Map.of("reason", "released"), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Map<?,?>) r.getBody().get("data")).get("status")).isEqualTo("ACTIVE");
}

@Test
void close_movesActiveToClosed_whenBalanceZero() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/close",
        HttpMethod.POST, new HttpEntity<>(Map.of("reason", "customer request"), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Map<?,?>) r.getBody().get("data")).get("status")).isEqualTo("CLOSED");
}

@Test
void unfreeze_fromActive_isIllegal_400() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/unfreeze",
        HttpMethod.POST, new HttpEntity<>(Map.of("reason", "x"), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
    assertThat(errors.get(0).get("code")).isEqualTo("INVALID_ACCOUNT_TRANSITION");
}

@Test
void close_fromFrozen_isIllegal_400() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze", HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "hold"), auth()), Map.class);
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/close",
        HttpMethod.POST, new HttpEntity<>(Map.of("reason", "x"), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
    assertThat(errors.get(0).get("code")).isEqualTo("INVALID_ACCOUNT_TRANSITION");
}

@Test
void close_withNonZeroBalance_409() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    restTemplate.exchange("/baas/v1/accounts/" + id + "/deposit", HttpMethod.POST,
        new HttpEntity<>(Map.of("amount", 100.00), auth()), Map.class);
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/close",
        HttpMethod.POST, new HttpEntity<>(Map.of("reason", "x"), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
    assertThat(errors.get(0).get("code")).isEqualTo("ACCOUNT_BALANCE_NONZERO");
}

@Test
void transition_blankReason_400() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze",
        HttpMethod.POST, new HttpEntity<>(Map.of("reason", ""), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountTransitionTest -q`
Expected: FAIL — no `/freeze` handler (404/500), transitions not implemented.

- [ ] **Step 3: Implement**

In `AccountService.java` add `AccountStatusEventRepository` to the `@RequiredArgsConstructor` field list. Insert this field with the others (after `private final CardAuthDebitRepository cardAuthDebitRepo;`):

```java
    private final AccountStatusEventRepository statusEventRepo;
```

Add these imports if not already present (`AccountStatus`, `BaasException`, `SecurityContextHolder`):

```java
import org.springframework.security.core.context.SecurityContextHolder;
```

Add the `transition` method, `target` helper, and `currentPrincipal` helper (place them after `getById`):

```java
    @Transactional
    public AccountDetailResponse transition(UUID id, AccountCommand command, String reason) {
        requireContext();
        Account account = accountRepo.findByIdForUpdate(id)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND",
                "Account " + id + " not found"));
        AccountStatus from = account.getStatus();
        AccountStatus to = target(from, command);
        if (to == null) {
            throw BaasException.badRequest("INVALID_ACCOUNT_TRANSITION",
                "Cannot " + command + " an account in status " + from);
        }
        if (command == AccountCommand.CLOSE
                && account.getBalance().compareTo(java.math.BigDecimal.ZERO) != 0) {
            throw BaasException.conflict("ACCOUNT_BALANCE_NONZERO",
                "Account balance must be zero to close (current: " + account.getBalance() + ")");
        }
        account.setStatus(to);
        accountRepo.save(account);
        statusEventRepo.save(AccountStatusEvent.builder()
            .accountId(id).fromStatus(from.name()).toStatus(to.name())
            .reason(reason).changedBy(currentPrincipal()).build());
        return toDetail(account);
    }

    /**
     * Lifecycle target state, or null if (from, command) is not a legal edge.
     * Close is reachable from ACTIVE only — a FROZEN account must be unfrozen first
     * (legal-hold realism). The zero-balance guard for CLOSE is enforced separately in
     * transition(), so a CLOSE from ACTIVE returns CLOSED here regardless of balance.
     */
    private static AccountStatus target(AccountStatus from, AccountCommand command) {
        return switch (command) {
            case FREEZE   -> from == AccountStatus.ACTIVE ? AccountStatus.FROZEN : null;
            case UNFREEZE -> from == AccountStatus.FROZEN ? AccountStatus.ACTIVE : null;
            case CLOSE    -> from == AccountStatus.ACTIVE ? AccountStatus.CLOSED : null;
        };
    }

    private String currentPrincipal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return (principal == null || "anonymousUser".equals(principal)) ? null : principal.toString();
    }
```

In `AccountController.java` add the three lifecycle endpoints:

```java
    @PreAuthorize("hasAuthority('UPDATE_ACCOUNT')")
    @PostMapping("/{id}/freeze")
    public ResponseEntity<ApiResponse<AccountDetailResponse>> freeze(
            @PathVariable UUID id, @Valid @RequestBody AccountTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            accountService.transition(id, AccountCommand.FREEZE, req.reason())));
    }

    @PreAuthorize("hasAuthority('UPDATE_ACCOUNT')")
    @PostMapping("/{id}/unfreeze")
    public ResponseEntity<ApiResponse<AccountDetailResponse>> unfreeze(
            @PathVariable UUID id, @Valid @RequestBody AccountTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            accountService.transition(id, AccountCommand.UNFREEZE, req.reason())));
    }

    @PreAuthorize("hasAuthority('UPDATE_ACCOUNT')")
    @PostMapping("/{id}/close")
    public ResponseEntity<ApiResponse<AccountDetailResponse>> close(
            @PathVariable UUID id, @Valid @RequestBody AccountTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            accountService.transition(id, AccountCommand.CLOSE, req.reason())));
    }
```

(`AccountCommand` is in the same package as the controller — no import needed; `AccountTransitionRequest` and `AccountDetailResponse` are imported via the existing wildcard `import com.nubbank.baas.engine.account.dto.*;`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountTransitionTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountTransitionTest.java
git commit -m "feat(engine): account lifecycle (freeze/unfreeze/close) state machine + history write

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Status-events endpoint

**Files:**
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java` (`statusEvents`)
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java` (`GET /{id}/status-events`)
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountStatusEventsApiTest.java`

- [ ] **Step 1: Write the failing test** (reproduce the shared harness)

```java
@Test
void statusEvents_returnsHistoryOldestFirst() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze", HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "legal hold"), auth()), Map.class);
    restTemplate.exchange("/baas/v1/accounts/" + id + "/unfreeze", HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "released"), auth()), Map.class);

    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/status-events",
        HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> events = (List<Map<String,Object>>) r.getBody().get("data");
    assertThat(events).hasSize(2);
    assertThat(events.get(0).get("fromStatus")).isEqualTo("ACTIVE");
    assertThat(events.get(0).get("toStatus")).isEqualTo("FROZEN");
    assertThat(events.get(0).get("reason")).isEqualTo("legal hold");
    assertThat(events.get(1).get("toStatus")).isEqualTo("ACTIVE");
}

@Test
void statusEvents_unknownAccount_404() {
    ResponseEntity<Map> r = restTemplate.exchange(
        "/baas/v1/accounts/" + UUID.randomUUID() + "/status-events",
        HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountStatusEventsApiTest -q`
Expected: FAIL — no `/status-events` handler.

- [ ] **Step 3: Implement**

In `AccountService.java` add the `statusEvents` method (place after `transition`). Add `import java.util.List;` if not already present (it imports `java.util.UUID` only today, so add the `List` import):

```java
    @Transactional(readOnly = true)
    public List<AccountStatusEventResponse> statusEvents(UUID id) {
        requireContext();
        if (!accountRepo.existsById(id)) {
            throw BaasException.notFound("ACCOUNT_NOT_FOUND", "Account " + id + " not found");
        }
        return statusEventRepo.findByAccountIdOrderByChangedAtAsc(id).stream()
            .map(e -> new AccountStatusEventResponse(e.getId(), e.getFromStatus(), e.getToStatus(),
                e.getReason(), e.getChangedBy(), e.getChangedAt()))
            .toList();
    }
```

In `AccountController.java` add `import java.util.List;` and the endpoint:

```java
    @PreAuthorize("hasAuthority('READ_ACCOUNT')")
    @GetMapping("/{id}/status-events")
    public ResponseEntity<ApiResponse<List<AccountStatusEventResponse>>> statusEvents(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.statusEvents(id)));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountStatusEventsApiTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountStatusEventsApiTest.java
git commit -m "feat(engine): GET /accounts/{id}/status-events history endpoint

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: List endpoint — filter by status + account-number search

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountSummaryResponse.java`
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountRepository.java` (`search` query with JOIN FETCH + countQuery)
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java` (`list` + `toSummary`)
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java` (`GET /` list)
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountListTest.java`

- [ ] **Step 1: Write the failing test** (reproduce the shared harness)

```java
/** Open an account, capture its account_number for search assertions. */
private Map<String,Object> openAccountFull(String typeLabel) {
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(Map.of("customerId", customerId.toString(),
            "accountTypeLabel", typeLabel), auth()), Map.class);
    @SuppressWarnings("unchecked")
    Map<String,Object> data = (Map<String,Object>) r.getBody().get("data");
    return data;
}

@SuppressWarnings("unchecked")
private List<Map<String,Object>> listContent(String query) {
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts" + query,
        HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String,Object> page = (Map<String,Object>) r.getBody().get("data");
    return (List<Map<String,Object>>) page.get("content");
}

@Test
void list_returnsSummaryRows_withCustomerName() {
    openAccountFull("Savings");
    List<Map<String,Object>> content = listContent("");
    assertThat(content).isNotEmpty();
    Map<String,Object> row = content.get(0);
    assertThat(row.get("customerName")).isEqualTo("Ada Lovelace");
    assertThat(row).containsKeys("id", "accountNumber", "customerId",
        "accountTypeLabel", "status", "balance", "currencyCode");
    assertThat(row).doesNotContainKey("availableBalance"); // summary is lean
}

@Test
void list_filtersByStatus() {
    Map<String,Object> a = openAccountFull("Savings");
    Map<String,Object> b = openAccountFull("Current");
    // Freeze account b
    restTemplate.exchange("/baas/v1/accounts/" + b.get("id") + "/freeze", HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "hold"), auth()), Map.class);

    assertThat(listContent("?status=ACTIVE")).hasSize(1);
    assertThat(listContent("?status=ACTIVE").get(0).get("id")).isEqualTo(a.get("id"));
    assertThat(listContent("?status=FROZEN")).hasSize(1);
    assertThat(listContent("?status=FROZEN").get(0).get("id")).isEqualTo(b.get("id"));
}

@Test
void list_searchesByAccountNumberPrefix() {
    Map<String,Object> a = openAccountFull("Savings");
    openAccountFull("Current");
    String acctNo = a.get("accountNumber").toString();   // 10-digit NUBAN

    // full account number → exactly one match
    assertThat(listContent("?search=" + acctNo)).hasSize(1);
    // a 6-char prefix of it → at least the one account (ILIKE prefix)
    assertThat(listContent("?search=" + acctNo.substring(0, 6))).isNotEmpty();
    // a non-matching string → zero
    assertThat(listContent("?search=ZZZZZZ")).isEmpty();
}

@Test
void list_paginates() {
    openAccountFull("Savings");
    openAccountFull("Current");
    openAccountFull("Savings");
    assertThat(listContent("?page=0&size=2")).hasSize(2);
    assertThat(listContent("?page=1&size=2")).hasSize(1);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountListTest -q`
Expected: FAIL — `GET /baas/v1/accounts` (no `{id}`) returns 405/404; no list handler.

- [ ] **Step 3: Implement**

`baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountSummaryResponse.java`:

```java
package com.nubbank.baas.engine.account.dto;

import com.nubbank.baas.engine.account.AccountStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record AccountSummaryResponse(
    UUID id,
    String accountNumber,
    UUID customerId,
    String customerName,
    String accountTypeLabel,
    AccountStatus status,
    BigDecimal balance,
    String currencyCode
) {}
```

In `AccountRepository.java` add the paginated search query. Spring Data **cannot** auto-derive a count query over a `JOIN FETCH`, so an explicit `countQuery` is mandatory. Add the imports and method:

```java
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
```

```java
    /**
     * Paginated account list with customer eagerly fetched (so customerName resolves with no
     * second round-trip). JOIN FETCH requires an explicit countQuery — Spring Data cannot
     * derive a count over a fetch join.
     *   status — exact AccountStatus filter, or null to match all statuses
     *   search — case-insensitive prefix pattern on account_number (e.g. "012345%"),
     *            already lower-cased by the service; or null to skip
     */
    @Query(value = """
        SELECT a FROM Account a JOIN FETCH a.customer
        WHERE (:status IS NULL OR a.status = :status)
          AND (:search IS NULL OR LOWER(a.accountNumber) LIKE :search)
        ORDER BY a.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(a) FROM Account a
        WHERE (:status IS NULL OR a.status = :status)
          AND (:search IS NULL OR LOWER(a.accountNumber) LIKE :search)
        """)
    Page<Account> search(@Param("status") AccountStatus status,
                         @Param("search") String search,
                         Pageable pageable);
```

> `LOWER(a.accountNumber) LIKE :search` is the JPA-portable equivalent of Postgres `ILIKE` (spec §3.4 "plaintext `ILIKE`"). The service lower-cases the search term and appends `%`, so the comparison is case-insensitive prefix matching (digit-only NUBANs are unaffected, but this stays correct if account-number formats ever include letters). `:status` is a typed `AccountStatus` enum parameter passed directly (JPQL handles the enum comparison) — null skips the filter.

In `AccountService.java` add the `list` method and `toSummary` helper. Add `import com.nubbank.baas.engine.account.dto.AccountSummaryResponse;` (or rely on the existing wildcard `import com.nubbank.baas.engine.account.dto.*;` — it is already present, so no new import needed):

```java
    @Transactional(readOnly = true)
    public Page<AccountSummaryResponse> list(int page, int size, String status, String search) {
        requireContext();
        AccountStatus statusFilter = (status == null || status.isBlank())
            ? null : AccountStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        String searchPattern = (search == null || search.isBlank())
            ? null : search.trim().toLowerCase(java.util.Locale.ROOT) + "%";
        return accountRepo.search(statusFilter, searchPattern, PageRequest.of(page, size))
            .map(this::toSummary);
    }

    private AccountSummaryResponse toSummary(Account a) {
        Customer c = a.getCustomer();
        String customerName = (c.getFirstNameEncrypted() + " " + c.getLastNameEncrypted()).trim();
        return new AccountSummaryResponse(a.getId(), a.getAccountNumber(),
            c.getId(), customerName, a.getAccountTypeLabel(), a.getStatus(),
            a.getBalance(), a.getCurrencyCode());
    }
```

> An unparseable `status` string (e.g. `?status=BOGUS`) makes `AccountStatus.valueOf` throw `IllegalArgumentException`. The existing `GlobalExceptionHandler` maps that to a `400` — acceptable; the frontend only ever sends the three valid enum names.

In `AccountController.java` add the list endpoint (import `org.springframework.data.domain.Page` is already present):

```java
    @PreAuthorize("hasAuthority('READ_ACCOUNT')")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<AccountSummaryResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.list(page, size, status, search)));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountListTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountSummaryResponse.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountRepository.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountListTest.java
git commit -m "feat(engine): GET /accounts list — status filter + account-number search (JOIN FETCH + countQuery)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Opening deposit on account open

**Files:**
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/OpenAccountRequest.java` (add `openingDeposit`)
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java` (`open` extension; return `AccountDetailResponse`)
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java` (`open` return type + `@PreAuthorize`)
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountOpeningDepositTest.java`

- [ ] **Step 1: Write the failing test** (reproduce the shared harness)

```java
@Test
void open_withOpeningDeposit_setsBalanceAndWritesCreditTransaction() {
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(Map.of("customerId", customerId.toString(),
            "accountTypeLabel", "Savings", "openingDeposit", 2500.00), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    @SuppressWarnings("unchecked")
    Map<String,Object> data = (Map<String,Object>) r.getBody().get("data");
    assertThat(((Number) data.get("balance")).doubleValue()).isEqualTo(2500.0);
    assertThat(((Number) data.get("availableBalance")).doubleValue()).isEqualTo(2500.0);
    // detail shape now returned by open
    assertThat(data.get("customerName")).isEqualTo("Ada Lovelace");

    String accountId = data.get("id").toString();
    ResponseEntity<Map> txns = restTemplate.exchange(
        "/baas/v1/accounts/" + accountId + "/transactions", HttpMethod.GET,
        new HttpEntity<>(auth()), Map.class);
    @SuppressWarnings("unchecked")
    Map<String,Object> page = (Map<String,Object>) txns.getBody().get("data");
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> content = (List<Map<String,Object>>) page.get("content");
    assertThat(content).hasSize(1);
    assertThat(content.get(0).get("transactionType")).isEqualTo("CREDIT");
    assertThat(content.get(0).get("reference")).isEqualTo("OPENING_DEPOSIT");
    assertThat(((Number) content.get(0).get("amount")).doubleValue()).isEqualTo(2500.0);
}

@Test
void open_withoutOpeningDeposit_isZeroBalance_noTransaction() {
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(Map.of("customerId", customerId.toString(),
            "accountTypeLabel", "Savings"), auth()), Map.class);
    @SuppressWarnings("unchecked")
    Map<String,Object> data = (Map<String,Object>) r.getBody().get("data");
    assertThat(((Number) data.get("balance")).doubleValue()).isEqualTo(0.0);

    String accountId = data.get("id").toString();
    ResponseEntity<Map> txns = restTemplate.exchange(
        "/baas/v1/accounts/" + accountId + "/transactions", HttpMethod.GET,
        new HttpEntity<>(auth()), Map.class);
    @SuppressWarnings("unchecked")
    Map<String,Object> page = (Map<String,Object>) txns.getBody().get("data");
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> content = (List<Map<String,Object>>) page.get("content");
    assertThat(content).isEmpty();
}

@Test
void open_withNegativeOpeningDeposit_400() {
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(Map.of("customerId", customerId.toString(),
            "accountTypeLabel", "Savings", "openingDeposit", -1.00), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountOpeningDepositTest -q`
Expected: FAIL — `openingDeposit` ignored; balance is 0 and no transaction is written.

- [ ] **Step 3: Implement**

`baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/OpenAccountRequest.java` — add the `openingDeposit` field with a `@PositiveOrZero` guard (rejects negatives at the validation layer → 400 without reaching the service):

```java
package com.nubbank.baas.engine.account.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record OpenAccountRequest(
    @NotNull(message = "customerId is required") UUID customerId,
    String accountTypeLabel,
    String accountName,
    @Pattern(regexp = "[A-Z]{3}", message = "currencyCode must be 3-letter ISO code")
    String currencyCode,
    BigDecimal minimumBalance,
    @PositiveOrZero(message = "openingDeposit must be zero or greater") BigDecimal openingDeposit
) {}
```

In `AccountService.java` rewrite `open(...)` to start the balance at the opening deposit, write an atomic CREDIT transaction when it is positive, and return the detail shape:

```java
    @Transactional
    public AccountDetailResponse open(OpenAccountRequest req) {
        requireContext();
        Customer customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND",
                "Customer " + req.customerId() + " not found"));

        String schema = PartnerContext.get().schemaName();
        String accountNumber = virtualAccountService.assignNext(schema);

        BigDecimal opening = req.openingDeposit() != null ? req.openingDeposit() : BigDecimal.ZERO;

        Account account = Account.builder()
            .customer(customer)
            .accountNumber(accountNumber)
            .accountTypeLabel(req.accountTypeLabel())
            .accountName(req.accountName() != null ? req.accountName()
                : customer.getFirstNameEncrypted() + " " + customer.getLastNameEncrypted())
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .minimumBalance(req.minimumBalance() != null ? req.minimumBalance() : BigDecimal.ZERO)
            .balance(opening)
            .availableBalance(opening)
            .build();

        Account saved = accountRepo.save(account);

        if (opening.compareTo(BigDecimal.ZERO) > 0) {
            txRepo.save(Transaction.builder()
                .account(saved).transactionType(TransactionType.CREDIT)
                .amount(opening).runningBalance(saved.getBalance())
                .currencyCode(saved.getCurrencyCode())
                .reference("OPENING_DEPOSIT").description("Opening deposit").build());
        }

        eventPublisher.publishEvent(new AccountOpenedEvent(
            saved.getId(), customer.getId(), saved.getAccountNumber(), schema));
        return toDetail(saved);
    }
```

> The `Account.@PrePersist onCreate()` only defaults `balance`/`availableBalance` to `ZERO` **when null** — passing `opening` (even `ZERO`) via the builder is honoured, so the zero-deposit path is byte-for-byte equivalent to today's behaviour. The CREDIT transaction and the account insert share the one `@Transactional` boundary, so they commit or roll back together.

In `AccountController.java` change `open` to return the detail shape and add the create guard:

```java
    @PreAuthorize("hasAuthority('CREATE_ACCOUNT')")
    @PostMapping
    public ResponseEntity<ApiResponse<AccountDetailResponse>> open(
            @Valid @RequestBody OpenAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(accountService.open(req)));
    }
```

> **Existing test impact:** `AccountControllerTest.openAccount_validCustomer_returns201WithNubanNumber` already asserts `data.get("balance")` is `0.0` and `accountNumber` is 10 digits — both still hold under the detail shape (it also carries `balance` and `accountNumber`). No change needed there. Re-run it in Step 4 to confirm.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountOpeningDepositTest,AccountControllerTest -q`
Expected: PASS (new opening-deposit tests + the pre-existing `AccountControllerTest` still green against the widened open response).

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/OpenAccountRequest.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountOpeningDepositTest.java
git commit -m "feat(engine): optional openingDeposit on open — atomic CREDIT + CREATE_ACCOUNT guard, open returns detail

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Money-movement status gating (legal-hold model)

**Files:**
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java` (`deposit` + `withdraw` status gate)
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java` (`@PreAuthorize` on deposit + withdraw)
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountMoneyGatingTest.java`

- [ ] **Step 1: Write the failing test** (reproduce the shared harness)

```java
@Test
void deposit_onFrozenAccount_succeeds() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze", HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "hold"), auth()), Map.class);

    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/deposit",
        HttpMethod.POST, new HttpEntity<>(Map.of("amount", 500.00), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Number) ((Map<?,?>) r.getBody().get("data")).get("runningBalance"))
        .doubleValue()).isEqualTo(500.0);
}

@Test
void deposit_onClosedAccount_409() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    restTemplate.exchange("/baas/v1/accounts/" + id + "/close", HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "closed"), auth()), Map.class);

    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/deposit",
        HttpMethod.POST, new HttpEntity<>(Map.of("amount", 500.00), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
    assertThat(errors.get(0).get("code")).isEqualTo("ACCOUNT_NOT_ACCEPTING_CREDITS");
}

@Test
void withdraw_onFrozenAccount_409() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    restTemplate.exchange("/baas/v1/accounts/" + id + "/deposit", HttpMethod.POST,
        new HttpEntity<>(Map.of("amount", 1000.00), auth()), Map.class);
    restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze", HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "hold"), auth()), Map.class);

    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/withdraw",
        HttpMethod.POST, new HttpEntity<>(Map.of("amount", 100.00), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
    assertThat(errors.get(0).get("code")).isEqualTo("ACCOUNT_NOT_ACCEPTING_DEBITS");
}

@Test
void withdraw_onActiveAccount_stillEnforcesBalanceFloor_400() {
    UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
    // ACTIVE, zero balance — debit beyond the floor is INSUFFICIENT_BALANCE (the gate passes first).
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/withdraw",
        HttpMethod.POST, new HttpEntity<>(Map.of("amount", 50.00), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
    assertThat(errors.get(0).get("code")).isEqualTo("INSUFFICIENT_BALANCE");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountMoneyGatingTest -q`
Expected: FAIL — today both `deposit` and `withdraw` reject any non-ACTIVE status with `ACCOUNT_NOT_ACTIVE` (`400`). Deposit-on-frozen returns 400 (want 200); deposit-on-closed returns 400 with the wrong code; withdraw-on-frozen returns 400 (want 409 `ACCOUNT_NOT_ACCEPTING_DEBITS`).

- [ ] **Step 3: Implement**

In `AccountService.java` replace the deposit status check. The current block is:

```java
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE",
                "Account must be ACTIVE to accept deposits");
        }
```

Replace it with the credit gate (ACTIVE or FROZEN allowed; only CLOSED blocks credits):

```java
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw BaasException.conflict("ACCOUNT_NOT_ACCEPTING_CREDITS",
                "A CLOSED account cannot accept credits");
        }
```

In `withdraw`, replace the current block:

```java
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE",
                "Account must be ACTIVE for withdrawals");
        }
```

with the debit gate (ACTIVE only; FROZEN and CLOSED block debits). Keep the existing minimum-balance / overdraft floor check **after** this gate (do not touch it):

```java
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw BaasException.conflict("ACCOUNT_NOT_ACCEPTING_DEBITS",
                "Account must be ACTIVE for debits (status: " + account.getStatus() + ")");
        }
```

In `AccountController.java` add the money guards:

```java
    @PreAuthorize("hasAuthority('DEPOSIT')")
    @PostMapping("/{id}/deposit")
    public ResponseEntity<ApiResponse<TransactionResponse>> deposit(
            @PathVariable UUID id, @Valid @RequestBody TransactionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.deposit(id, req)));
    }

    @PreAuthorize("hasAuthority('WITHDRAW')")
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<ApiResponse<TransactionResponse>> withdraw(
            @PathVariable UUID id, @Valid @RequestBody TransactionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.withdraw(id, req)));
    }
```

(Replace the existing `deposit`/`withdraw` methods in place — only the `@PreAuthorize` line is new.)

Also add the read guard to the remaining unguarded endpoint, `transactions`:

```java
    @PreAuthorize("hasAuthority('READ_ACCOUNT')")
    @GetMapping("/{id}/transactions")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> transactions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getTransactions(id, page, size)));
    }
```

> Every `AccountController` endpoint now carries a `@PreAuthorize` (open=CREATE_ACCOUNT, getById/list/status-events/transactions=READ_ACCOUNT, deposit=DEPOSIT, withdraw=WITHDRAW, freeze/unfreeze/close=UPDATE_ACCOUNT) — spec §3.3 fully satisfied.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountMoneyGatingTest,AccountControllerTest -q`
Expected: PASS. (`AccountControllerTest.depositAndWithdraw_updatesBalance` and `withdraw_insufficientBalance_returns400` still pass — they operate on ACTIVE accounts; the floor check is unchanged.)

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountMoneyGatingTest.java
git commit -m "feat(engine): money-movement status gating — freeze blocks debits, allows credits; DEPOSIT/WITHDRAW guards

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: RBAC enforcement test (403 when authority missing)

**Files:**
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountAuthzTest.java`

> This task adds no production code — it proves the `@PreAuthorize` guards added in Tasks 4–9 actually deny. It uses an **operator** Keycloak JWT (stubbed JWKS) whose `user_roles` grant **only** `READ_ACCOUNT`, so reads pass but writes (freeze) are forbidden. Mirrors `CustomerAuthzTest` exactly. The denial error envelope uses code `ACCESS_DENIED`.

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.keycloak.OperatorJwtDecoderFactory;
import com.nubbank.baas.engine.auth.keycloak.TestJwks;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AccountAuthzTest extends AbstractIntegrationTest {

    static final TestJwks JWKS = new TestJwks();

    @TestConfiguration
    static class StubDecoderConfig {
        @Bean @Primary OperatorJwtDecoderFactory stubFactory() { return issuer -> JWKS.decoder(); }
    }

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;
    @Autowired PermissionRepository permRepo;
    @Autowired UserRoleRepository userRoleRepo;
    @Autowired CustomerRepository customerRepo;

    private String issuer;
    private PartnerOrganization org;
    private UUID accountId;

    @BeforeEach
    void setup() {
        issuer = "https://auth.nubbank.test/realms/baas-partner-" + UUID.randomUUID();
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        org = orgRepo.save(PartnerOrganization.builder()
            .name("Acct Authz").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .keycloakIssuer(issuer).contactEmail("z@test.com").build());
        provisioning.provision(org.getId(), schema);

        // Seed a customer + account directly in the tenant schema for the read/write targets.
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema,
            "BASIC", "PRODUCTION", "TEST", null));
        UUID customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Grace").lastNameEncrypted("Hopper").build()).getId();
        Account a = Account.builder()
            .customer(customerRepo.findById(customerId).orElseThrow())
            .accountNumber("0000000001").accountTypeLabel("Savings")
            .status(AccountStatus.ACTIVE).balance(java.math.BigDecimal.ZERO)
            .availableBalance(java.math.BigDecimal.ZERO).minimumBalance(java.math.BigDecimal.ZERO)
            .currencyCode("NGN").build();
        // accountRepo not autowired here on purpose — use the entity manager via the repo from context.
        accountId = saveAccount(schema, a);
        PartnerContext.clear();
    }

    // Persist the account through its repository inside the active PartnerContext.
    @Autowired AccountRepository accountRepo;
    private UUID saveAccount(String schema, Account a) {
        return accountRepo.save(a).getId();
    }

    private HttpHeaders bearer(String sub) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(JWKS.sign(issuer, sub, 300));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void operatorWithReadAccount_canList_butCannotFreeze() {
        UUID sub = UUID.randomUUID();

        PartnerContext.set(new PartnerContext(org.getId().toString(), org.getSchemaName(),
            "BASIC", "PRODUCTION", "OPERATOR_JWT", sub.toString()));
        try {
            Permission read = permRepo.findAll().stream()
                .filter(p -> p.getCode().equals("READ_ACCOUNT")).findFirst().orElseThrow();
            Role viewer = roleRepo.save(Role.builder().name("ACCT_VIEWER")
                .permissions(Set.of(read)).build());
            userRoleRepo.save(UserRole.builder().userId(sub).role(viewer).build());
        } finally {
            PartnerContext.clear();
        }

        // READ_ACCOUNT granted → list is 200
        ResponseEntity<Map> listResp = restTemplate.exchange("/baas/v1/accounts",
            HttpMethod.GET, new HttpEntity<>(bearer(sub.toString())), Map.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // UPDATE_ACCOUNT NOT granted → freeze is 403
        ResponseEntity<Map> freezeResp = restTemplate.exchange(
            "/baas/v1/accounts/" + accountId + "/freeze", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "hold"), bearer(sub.toString())), Map.class);
        assertThat(freezeResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> errors =
            (List<Map<String,Object>>) freezeResp.getBody().get("errors");
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).get("code")).isEqualTo("ACCESS_DENIED");
    }
}
```

> The two `@Autowired` declarations placed mid-class (`accountRepo`) are legal Java field declarations; they are grouped near the helper that uses them for readability. If the reviewer prefers, move `@Autowired AccountRepository accountRepo;` up with the other field injections — behaviour is identical.

- [ ] **Step 2: Run test to verify it fails (or passes immediately)**

Run: `cd baas-engine && ./mvnw test -Dtest=AccountAuthzTest -q`
Expected: PASS — the guards from Tasks 4 and 5 already enforce this; this test locks the behaviour in. If it FAILS with `200` on freeze, a `@PreAuthorize` is missing on the freeze endpoint — re-check Task 5 Step 3.

- [ ] **Step 3: Commit**

```bash
git add baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountAuthzTest.java
git commit -m "test(engine): RBAC — operator without UPDATE_ACCOUNT is 403 on freeze, 200 on list

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: API docs

**Files:**
- Modify: `docs/api-reference.html` (Accounts section)

- [ ] **Step 1: Update the Accounts section** of `docs/api-reference.html` with the new/changed endpoints, matching the existing table/markup style in that file:
  - `GET /accounts` — list; query params `page`, `size`, `status` (ACTIVE|FROZEN|CLOSED), `search` (account-number prefix); returns a `Page<AccountSummaryResponse>`.
  - `GET /accounts/{id}` — widened to `AccountDetailResponse` (adds `customerName`, `availableBalance`, `minimumBalance`, `allowOverdraft`, `overdraftLimit`, `openedAt`).
  - `POST /accounts` — now accepts optional `openingDeposit` (≥ 0); returns the detail shape; on a positive deposit writes one CREDIT transaction (reference `OPENING_DEPOSIT`).
  - `POST /accounts/{id}/freeze|unfreeze|close` — body `{ "reason": "..." }`; `400 INVALID_ACCOUNT_TRANSITION` for an illegal edge (incl. close-from-FROZEN), `409 ACCOUNT_BALANCE_NONZERO` when closing a non-zero account.
  - `GET /accounts/{id}/status-events` — append-only lifecycle history (oldest first).
  - `POST /accounts/{id}/deposit` — `409 ACCOUNT_NOT_ACCEPTING_CREDITS` on a CLOSED account (FROZEN still accepts credits).
  - `POST /accounts/{id}/withdraw` — `409 ACCOUNT_NOT_ACCEPTING_DEBITS` on a FROZEN or CLOSED account; `400 INSUFFICIENT_BALANCE` below the floor on ACTIVE.
  - RBAC column: reads `READ_ACCOUNT`; open `CREATE_ACCOUNT`; deposit `DEPOSIT`; withdraw `WITHDRAW`; freeze/unfreeze/close `UPDATE_ACCOUNT`.

- [ ] **Step 2: Commit**

```bash
git add docs/api-reference.html
git commit -m "docs(api-reference): accounts lifecycle + list + opening-deposit endpoints

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: Full-suite verification

- [ ] **Step 1: Run the whole engine suite**

Run: `cd baas-engine && ./mvnw test -q`
Expected: BUILD SUCCESS, 0 failures (all pre-existing tests + the new account tests green). In particular confirm `AccountControllerTest` (pre-existing) and every new `Account*Test` pass together.

- [ ] **Step 2: Push + open PR** (handled by `finishing-a-development-branch`)

```bash
git push -u origin feat/baas-engine-accounts-lifecycle
```

The SESSION COMPLETION GATE (baas-log.md, CLAUDE.md Confirmed Platform Versions, `docs/api-reference.html` already done in Task 11, and the Figma "Accounts — As Built" frames per spec §7) is completed before merge. The Figma frames are a frontend-PR (PR B) / shared deliverable — coordinate per the spec; they are not a `baas-engine` code artifact.

---

## Notes for the implementer

- **`@PreAuthorize` works in tests** because a first-party partner JWT gets full tenant authority — but only over the permission codes that exist in the schema. `UPDATE_ACCOUNT` must be seeded (Task 1) or the lifecycle tests would 403 even with the admin JWT.
- **First-party authority set is data-driven** — `AuthorityResolver.fullTenantAuthorities()` returns `permissionRepo.findAllCodes()`. Spec §3.3 "add `UPDATE_ACCOUNT` to the first-party full-tenant-authority set" is satisfied entirely by the V6 `INSERT INTO permissions` row; there is **no Java authority list to edit**. (Confirmed by reading `PartnerContextFilter.populateAuthorities()` → `AuthorityResolver`.)
- **Tenant routing is automatic** — every repository call runs against the partner schema resolved from `PartnerContext`. Do not add a `partnerId` filter.
- **Account FK requires a customer** — every test must persist a `Customer` first (the shared harness does this). Opening an account with a random `customerId` returns `404 CUSTOMER_NOT_FOUND`.
- **JOIN FETCH needs an explicit `countQuery`** — Spring Data cannot derive a count over a fetch join; the `search` query in Task 7 supplies both. Omitting `countQuery` throws at query-execution time.
- **`customerName` from the fetch-join** — the `Account.customer` `@ManyToOne` is `LAZY`, but `toDetail`/`toSummary` read it inside the open `@Transactional` session (or after a `JOIN FETCH`), decrypting first+last via the `Customer` entity converter — no cross-repository converter pitfall (the Customer is loaded through the JPA session, not a foreign repository).
- **Close is ACTIVE-only** — a FROZEN account must be unfrozen before it can be closed (`400 INVALID_ACCOUNT_TRANSITION`), and the balance must be zero (`409 ACCOUNT_BALANCE_NONZERO`). Both guards live in `transition`.
- **Money gating precedes the balance floor** — `withdraw` checks the status gate first (`409 ACCOUNT_NOT_ACCEPTING_DEBITS`), then the existing minimum-balance / overdraft floor (`400 INSUFFICIENT_BALANCE`). Do not reorder.
- **`open` returns the detail shape after Task 8** — the pre-existing `AccountControllerTest` still passes because `AccountDetailResponse` also carries `balance` and `accountNumber`. Re-run it in Tasks 8, 9, and 12.
