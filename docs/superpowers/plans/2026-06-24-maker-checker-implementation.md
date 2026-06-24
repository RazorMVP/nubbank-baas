# Maker-Checker / Four-Eyes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a general, command-first maker-checker (four-eyes) framework to `baas-engine` that defers a guarded command into a `PENDING` task which a *different* authorised user must approve before it replays through the real service — with `ACCOUNT_OPEN` as the first guarded command.

**Architecture:** A new `com.nubbank.baas.engine.makerchecker` package holds two tenant-schema tables (`maker_checker_tasks`, `maker_checker_config`), a typed command-handler registry, and an orchestration service. A guarded `POST /accounts` no longer executes synchronously — it persists the serialized request as a `PENDING` task (`202`); on approval the task is replayed through the *identical* `AccountService.open(...)` the synchronous path uses, so re-validation against current state is automatic. Enforcement is per-partner, per-command, opt-in, and only active in `PRODUCTION`.

**Tech Stack:** Java 21, Spring Boot 3.5, Hibernate (SCHEMA multi-tenancy), Flyway (tenant migrations), Jackson (`ObjectMapper`) for JSONB payloads, Spring Security method security (`@PreAuthorize` / authorities), JUnit 5 + Testcontainers (PostgreSQL 16) + `TestRestTemplate`.

**Spec:** `docs/superpowers/specs/2026-06-18-maker-checker-design.md` — read it before starting. Closes **DEF-1C-13**. Depends on Spec A (Granular Partner RBAC, already merged) for `PARTNER_MAKER`/`PARTNER_APPROVER` roles and the `APPROVE_*` permission space.

---

## Critical context (read first)

**There is a LEGACY passive maker-checker already in the repo — do NOT touch it and do NOT collide with it.**
- `com.nubbank.baas.engine.social.MakerCheckerRequest` (table `maker_checker_requests`, seeded in `V2`), `social.MakerCheckerService`, `social.MakerCheckerController` (route `/baas/v1/makercheckers`). It is a Mifos-ported *registry* — it stores requests but guards no real command path. This plan builds a **separate** command-first framework. Leave the legacy module entirely alone.
- **Spring bean-name collision trap:** Spring derives a default bean id from the simple class name. A class named `MakerCheckerService` or `MakerCheckerController` in the new package would collide with the legacy beans `makerCheckerService` / `makerCheckerController` → `ConflictingBeanDefinitionException` at startup. **Therefore every new bean uses a distinct simple name:** `MakerCheckerTaskService`, `MakerCheckerTaskController`, `MakerCheckerTaskRepository`, `MakerCheckerConfigRepository`, `MakerCheckerCommandRegistry`. Entities (`MakerCheckerTask`, `MakerCheckerConfig`) are not beans, so they are safe, but the `Task` suffix on the task entity also keeps it clearly distinct from `MakerCheckerRequest`.

**Confirmed integration facts (verified against `main`):**
- `AccountController.open` → `@PreAuthorize("hasAuthority('CREATE_ACCOUNT')")`, `@PostMapping`, path `/baas/v1/accounts`, returns `ResponseEntity<ApiResponse<AccountDetailResponse>>` at **201**. The class is `@RequiredArgsConstructor` with `private final AccountService accountService;`.
- `AccountService.open(OpenAccountRequest req)` returns `AccountDetailResponse` (a record whose first component is `UUID id`). Calling `.id()` yields the created account id.
- `OpenAccountRequest` is a record with `@NotNull UUID customerId`, plus nullable `accountTypeLabel`, `accountName`, `@Pattern("[A-Z]{3}") currencyCode`, `minimumBalance`, `@PositiveOrZero openingDeposit`.
- `PartnerContext` is `record(String partnerId, String schemaName, String tier, String environment, String authMode, String userId)` with static `get()/set()/clear()`. `environment` is the string `"PRODUCTION"` or `"SANDBOX"`. `userId` is the JWT subject (partner user UUID) / api-key id.
- `BaasException` factories: `notFound`, `badRequest`, `conflict`, `unauthorized`, `forbidden` (each `(String code, String message)`), mapped to HTTP by `common.GlobalExceptionHandler` (`@RestControllerAdvice`). Validation errors → `400 VALIDATION_ERROR`; `AccessDeniedException` → `403 ACCESS_DENIED`.
- `ApiResponse.ok(T)` wraps data; `ApiResponse.error(code,msg)` / `ApiResponse.fieldError(code,msg,field)` wrap errors.
- JSONB-as-String pattern: `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") String x;` (see `partner.PartnerApiKey.scopes`). `ObjectMapper` is an injectable Spring bean.
- `AuthorityResolver.partnerUserAuthorities(UUID userId): List<String>` returns the user's *current* permission codes (superuser short-circuits to all). `permissionRepo.findAllCodes()` exists.
- `UserRoleRepository` already has `countDistinctUsersWithRole(String)`, `findUserIdsByRoleName(String)`, `existsSuperuserRoleByUserId(UUID)`, `findPermissionCodesByUserId(UUID)`.
- `PartnerUser` (table `public.partner_users`) has `boolean active`; `PartnerUserRepository extends JpaRepository<PartnerUser, UUID>` (so `findById` is available) with `isActive()` getter (Lombok).
- `CustomerRepository extends JpaRepository<Customer, UUID>` (so `findById(customerId)` is available; already used by `AccountService.open`).
- Latest tenant migration is `V7__partner_rbac.sql`; **next is `V8`**. Latest public migration is `V3`.
- `permissions` columns: `grouping`, `code` (unique), `entity_name`, `action_name`, `can_maker_checker` (boolean, has a DB default — `V7` inserts omit it). `roles` columns include `built_in`, `role_scope`, `is_superuser`. `role_permissions(role_id, permission_id)`.
- Tests extend `AbstractIntegrationTest` (`@SpringBootTest(RANDOM_PORT)`, `@ActiveProfiles("test")`, shared PostgreSQL 16 Testcontainer, `TestRestTemplate restTemplate`, `RoleRepository roleRepo`, `UserRoleRepository userRoleRepo`, `PartnerJwtService partnerJwtService`). Helpers: `grantAdmin(String schema, UUID userId)`, `adminJwt(PartnerOrganization org, String schema)`. `@AfterEach` clears `PartnerContext`.
- `PartnerJwtService.issue(userId, email, role, orgId, orgName, schemaName, tier, environment)` — 8 String args.
- Provisioning a tenant in tests: save a `PartnerOrganization` (status `PartnerStatus.PRO`, tier `PartnerTier.PRO`, env `PartnerEnvironment.PRODUCTION`, a unique `schemaName`), then `provisioning.provision(org.getId(), schema)` (autowire `TenantProvisioningService provisioning`). See `tenant.PartnerAuthorityIntegrationTest` for the exact shape.

**Run tests:** `cd ~/nubbank-baas/baas-engine && ./mvnw -o test` (whole suite) or `./mvnw -o test -Dtest=ClassName` (one class). Use `-o` (offline) after deps are cached; drop `-o` the first time if a new dependency is needed (none is).

---

## File structure

**New files (package `com.nubbank.baas.engine.makerchecker`):**
- `MakerCheckerCommandType.java` — command-type string constants (`ACCOUNT_OPEN`).
- `TaskStatus.java` — enum `PENDING, APPROVED, REJECTED, WITHDRAWN` (kept open for a future `EXPIRED`).
- `MakerCheckerTask.java` — entity, table `maker_checker_tasks`.
- `MakerCheckerConfig.java` — entity, table `maker_checker_config`.
- `MakerCheckerTaskRepository.java`, `MakerCheckerConfigRepository.java`.
- `MakerCheckerCommandHandler.java` — handler interface.
- `MakerCheckerCommandRegistry.java` — resolves a handler by command type.
- `MakerCheckerTaskService.java` — submit/approve/reject/withdraw/list/get/dry-run/config orchestration.
- `MakerCheckerTaskController.java` — REST at `/baas/v1/maker-checker`.
- `dto/TaskResponse.java`, `dto/TaskDetailResponse.java`, `dto/ConfigResponse.java`, `dto/ConfigUpdateRequest.java`, `dto/RejectRequest.java`.

**New file (package `com.nubbank.baas.engine.account`):**
- `AccountOpenCommandHandler.java` — implements the handler interface; `execute` replays `AccountService.open`.

**New migration:**
- `src/main/resources/db/migration/tenant/V8__maker_checker_tasks.sql`.

**Modified files:**
- `account/AccountController.java` — `open()` defers to the framework (202) or executes synchronously (201).
- `role/UserRoleRepository.java` — add `countDistinctUsersWithPermission`, `countDistinctSuperusers`.

**Dependency direction:** `account` → `makerchecker` (one-way). `makerchecker` never imports `account`; it receives the `AccountOpenCommandHandler` at runtime as a `MakerCheckerCommandHandler` bean. No cycle.

---

### Task 1: V8 migration — tables, permissions, approver grant

**Files:**
- Create: `baas-engine/src/main/resources/db/migration/tenant/V8__maker_checker_tasks.sql`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/V8MigrationTest.java`

- [ ] **Step 1: Write the failing test**

`baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/V8MigrationTest.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class V8MigrationTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PermissionRepository permissionRepo;

    @Test
    void v8_seedsApproveAccountAndManagePermissions_andGrantsApproverApproveAccount() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("MC").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("mc@t.com").build());
        provisioning.provision(org.getId(), schema);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            List<String> codes = permissionRepo.findAllCodes();
            assertThat(codes).contains("APPROVE_ACCOUNT", "MANAGE_MAKER_CHECKER");

            // CREATE_ACCOUNT is now maker-checkable
            Permission createAccount = permissionRepo.findAll().stream()
                .filter(p -> p.getCode().equals("CREATE_ACCOUNT")).findFirst().orElseThrow();
            assertThat(createAccount.isCanMakerChecker()).isTrue();

            // PARTNER_APPROVER holds APPROVE_ACCOUNT
            Role approver = roleRepo.findByName("PARTNER_APPROVER").orElseThrow();
            assertThat(approver.getPermissions().stream().map(Permission::getCode))
                .contains("APPROVE_ACCOUNT");
        } finally {
            PartnerContext.clear();
        }
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -o test -Dtest=V8MigrationTest`
Expected: FAIL — `APPROVE_ACCOUNT`/`MANAGE_MAKER_CHECKER` not present (migration doesn't exist yet).

- [ ] **Step 3: Write the migration**

`baas-engine/src/main/resources/db/migration/tenant/V8__maker_checker_tasks.sql`:

```sql
-- V8__maker_checker_tasks.sql — command-first maker-checker framework (Spec B).
-- Distinct from the legacy passive social.maker_checker_requests (V2): this one
-- actually guards real command paths (ACCOUNT_OPEN first) and replays on approve.

CREATE TABLE maker_checker_tasks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    command_type  VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    made_by       UUID         NOT NULL,
    made_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    checked_by    UUID,
    checked_at    TIMESTAMPTZ,
    reject_reason TEXT,
    result_id     UUID,
    expires_at    TIMESTAMPTZ,            -- reserved TTL seam, unused in v1
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mc_tasks_status       ON maker_checker_tasks(status);
CREATE INDEX idx_mc_tasks_command_type ON maker_checker_tasks(command_type);

CREATE TABLE maker_checker_config (
    command_type VARCHAR(100) PRIMARY KEY,
    enabled      BOOLEAN     NOT NULL DEFAULT false,
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Default config row: ACCOUNT_OPEN present but OFF (opt-in, back-compatible).
INSERT INTO maker_checker_config (command_type, enabled) VALUES ('ACCOUNT_OPEN', false);

-- New permissions (one APPROVE_* per guarded command, plus the config gate).
INSERT INTO permissions (grouping, code, entity_name, action_name, can_maker_checker) VALUES
  ('accounts','APPROVE_ACCOUNT',     'ACCOUNT',       'APPROVE', true),
  ('admin',   'MANAGE_MAKER_CHECKER','MAKER_CHECKER', 'MANAGE',  false);

-- CREATE_ACCOUNT now has a deferred counterpart.
UPDATE permissions SET can_maker_checker = true WHERE code = 'CREATE_ACCOUNT';

-- PARTNER_APPROVER (Spec A) gains the four-eyes checker authority for account-open.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'APPROVE_ACCOUNT'
   WHERE r.name = 'PARTNER_APPROVER';
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `./mvnw -o test -Dtest=V8MigrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/resources/db/migration/tenant/V8__maker_checker_tasks.sql \
        baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/V8MigrationTest.java
git commit -m "feat(engine): maker-checker V8 migration — tasks/config tables + APPROVE_ACCOUNT"
```

---

### Task 2: Entities, repositories, and approver-count queries

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/TaskStatus.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerTask.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerConfig.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerTaskRepository.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerConfigRepository.java`
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/role/UserRoleRepository.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerPersistenceTest.java`

- [ ] **Step 1: Write the failing test**

`baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerPersistenceTest.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class MakerCheckerPersistenceTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired MakerCheckerTaskRepository taskRepo;
    @Autowired MakerCheckerConfigRepository configRepo;

    @Test
    void task_roundTrips_andStatusFilterWorks() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("P").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("p@t.com").build());
        provisioning.provision(org.getId(), schema);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            UUID maker = UUID.randomUUID();
            MakerCheckerTask saved = taskRepo.save(MakerCheckerTask.builder()
                .commandType("ACCOUNT_OPEN").payload("{\"customerId\":\"x\"}")
                .madeBy(maker).status(TaskStatus.PENDING).build());

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getMadeAt()).isNotNull();

            List<MakerCheckerTask> pending = taskRepo.findByStatusOrderByMadeAtDesc(TaskStatus.PENDING);
            assertThat(pending).extracting(MakerCheckerTask::getId).contains(saved.getId());

            // default config row from V8
            assertThat(configRepo.findById("ACCOUNT_OPEN")).isPresent()
                .get().extracting(MakerCheckerConfig::isEnabled).isEqualTo(false);
        } finally {
            PartnerContext.clear();
        }
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -o test -Dtest=MakerCheckerPersistenceTest`
Expected: FAIL — `MakerCheckerTask`/repositories don't exist (compile error).

- [ ] **Step 3: Create the enum**

`makerchecker/TaskStatus.java`:

```java
package com.nubbank.baas.engine.makerchecker;

/** Open enum: a future EXPIRED state (TTL seam) may be appended — see spec §10. */
public enum TaskStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }
```

- [ ] **Step 4: Create the task entity**

`makerchecker/MakerCheckerTask.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maker_checker_tasks")  // tenant-schema table — NO schema annotation
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MakerCheckerTask {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "command_type", nullable = false, length = 100)
    private String commandType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "made_by", nullable = false)
    private UUID madeBy;

    @Column(name = "made_at", nullable = false)
    private Instant madeAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @Column(name = "checked_by")
    private UUID checkedBy;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "reject_reason", columnDefinition = "text")
    private String rejectReason;

    @Column(name = "result_id")
    private UUID resultId;

    @Column(name = "expires_at")
    private Instant expiresAt;       // reserved TTL seam — unused in v1

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (madeAt == null) madeAt = now;
        if (status == null) status = TaskStatus.PENDING;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
```

- [ ] **Step 5: Create the config entity**

`makerchecker/MakerCheckerConfig.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "maker_checker_config")  // tenant-schema table
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MakerCheckerConfig {

    @Id
    @Column(name = "command_type", length = 100)
    private String commandType;

    @Column(nullable = false)
    private boolean enabled;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
```

- [ ] **Step 6: Create the repositories**

`makerchecker/MakerCheckerTaskRepository.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MakerCheckerTaskRepository extends JpaRepository<MakerCheckerTask, UUID> {

    List<MakerCheckerTask> findAllByOrderByMadeAtDesc();
    List<MakerCheckerTask> findByStatusOrderByMadeAtDesc(TaskStatus status);
    List<MakerCheckerTask> findByCommandTypeOrderByMadeAtDesc(String commandType);
    List<MakerCheckerTask> findByStatusAndCommandTypeOrderByMadeAtDesc(TaskStatus status, String commandType);

    /** SELECT ... FOR UPDATE — serializes concurrent approve/withdraw on the same task (spec §10). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MakerCheckerTask t where t.id = :id")
    Optional<MakerCheckerTask> findByIdForUpdate(UUID id);
}
```

`makerchecker/MakerCheckerConfigRepository.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MakerCheckerConfigRepository extends JpaRepository<MakerCheckerConfig, String> {}
```

- [ ] **Step 7: Add approver-count queries to UserRoleRepository**

Add these two methods inside the existing `role/UserRoleRepository.java` interface body (alongside the existing `@Query` methods):

```java
    /** Count distinct users who hold a permission code through any assigned role. */
    @Query("select count(distinct ur.userId) from UserRole ur join ur.role r join r.permissions p where p.code = :code")
    long countDistinctUsersWithPermission(String code);

    /** Count distinct users holding a superuser role (they hold every permission implicitly). */
    @Query("select count(distinct ur.userId) from UserRole ur join ur.role r where r.superuser = true")
    long countDistinctSuperusers();
```

- [ ] **Step 8: Run it to confirm it passes**

Run: `./mvnw -o test -Dtest=MakerCheckerPersistenceTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/ \
        baas-engine/src/main/java/com/nubbank/baas/engine/role/UserRoleRepository.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerPersistenceTest.java
git commit -m "feat(engine): maker-checker task/config entities, repositories, approver-count queries"
```

---

### Task 3: Command-type constants, handler interface, registry

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerCommandType.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerCommandHandler.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerCommandRegistry.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerCommandRegistryTest.java`

- [ ] **Step 1: Write the failing test**

`baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerCommandRegistryTest.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.common.BaasException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class MakerCheckerCommandRegistryTest {

    private MakerCheckerCommandHandler stub(String type) {
        return new MakerCheckerCommandHandler() {
            public String commandType() { return type; }
            public String requiredAuthorityToSubmit() { return "CREATE_X"; }
            public String requiredAuthorityToApprove() { return "APPROVE_X"; }
            public Class<?> payloadType() { return String.class; }
            public void validate(Object payload) { }
            public UUID execute(Object payload) { return UUID.randomUUID(); }
        };
    }

    @Test
    void require_resolvesKnownHandler() {
        var registry = new MakerCheckerCommandRegistry(List.of(stub("ACCOUNT_OPEN")));
        assertThat(registry.require("ACCOUNT_OPEN").commandType()).isEqualTo("ACCOUNT_OPEN");
    }

    @Test
    void require_throwsBadRequest_forUnknownType() {
        var registry = new MakerCheckerCommandRegistry(List.of(stub("ACCOUNT_OPEN")));
        assertThatThrownBy(() -> registry.require("WIRE_TRANSFER"))
            .isInstanceOf(BaasException.class)
            .hasMessageContaining("WIRE_TRANSFER");
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -o test -Dtest=MakerCheckerCommandRegistryTest`
Expected: FAIL — types don't exist (compile error).

- [ ] **Step 3: Create the command-type constants**

`makerchecker/MakerCheckerCommandType.java`:

```java
package com.nubbank.baas.engine.makerchecker;

/** Guardable command-type identifiers. Add one per guarded command (spec §6). */
public final class MakerCheckerCommandType {
    private MakerCheckerCommandType() {}
    public static final String ACCOUNT_OPEN = "ACCOUNT_OPEN";
}
```

- [ ] **Step 4: Create the handler interface**

`makerchecker/MakerCheckerCommandHandler.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import java.util.UUID;

/**
 * One implementation per guarded command. The deferred (approve) path invokes the
 * SAME service entry point as the synchronous path — execute() must never be a
 * stripped re-implementation (spec §6 cardinal rule).
 */
public interface MakerCheckerCommandHandler {

    /** e.g. {@link MakerCheckerCommandType#ACCOUNT_OPEN}. */
    String commandType();

    /** Authority the maker must hold to submit, e.g. {@code CREATE_ACCOUNT}. */
    String requiredAuthorityToSubmit();

    /** Authority the checker must hold to approve, e.g. {@code APPROVE_ACCOUNT}. */
    String requiredAuthorityToApprove();

    /** Concrete request DTO type the payload deserializes to. */
    Class<?> payloadType();

    /** Submit-time courtesy validation (subset of real validation). Throws BaasException on failure. */
    void validate(Object payload);

    /** Replay through the real, fully-validating service method; return the created resource id. */
    UUID execute(Object payload);
}
```

- [ ] **Step 5: Create the registry**

`makerchecker/MakerCheckerCommandRegistry.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.common.BaasException;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class MakerCheckerCommandRegistry {

    private final Map<String, MakerCheckerCommandHandler> byType;

    public MakerCheckerCommandRegistry(List<MakerCheckerCommandHandler> handlers) {
        this.byType = handlers.stream()
            .collect(Collectors.toMap(MakerCheckerCommandHandler::commandType, Function.identity()));
    }

    public MakerCheckerCommandHandler require(String commandType) {
        MakerCheckerCommandHandler h = byType.get(commandType);
        if (h == null)
            throw BaasException.badRequest("UNKNOWN_COMMAND_TYPE", "No maker-checker handler for " + commandType);
        return h;
    }

    public Optional<MakerCheckerCommandHandler> find(String commandType) {
        return Optional.ofNullable(byType.get(commandType));
    }
}
```

- [ ] **Step 6: Run it to confirm it passes**

Run: `./mvnw -o test -Dtest=MakerCheckerCommandRegistryTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerCommandType.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerCommandHandler.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerCommandRegistry.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerCommandRegistryTest.java
git commit -m "feat(engine): maker-checker command handler interface + registry"
```

---

### Task 4: AccountOpenCommandHandler (the first guarded command)

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountOpenCommandHandler.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountOpenCommandHandlerTest.java`

> **Verify before coding:** confirm `CustomerRepository` lives in `com.nubbank.baas.engine.customer` and that `Customer` exposes a builder for test setup. If the customer create endpoint is simpler, the test may POST `/baas/v1/customers` instead — but the handler code below only needs `CustomerRepository.findById`, which is inherited from `JpaRepository`.

- [ ] **Step 1: Write the failing test**

`baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountOpenCommandHandlerTest.java`:

```java
package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.dto.OpenAccountRequest;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.makerchecker.MakerCheckerCommandType;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AccountOpenCommandHandlerTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired AccountOpenCommandHandler handler;

    private String provision() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("H").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("h@t.com").build());
        provisioning.provision(org.getId(), schema);
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        return schema;
    }

    @Test
    void declares_correctTypeAndAuthorities() {
        assertThat(handler.commandType()).isEqualTo(MakerCheckerCommandType.ACCOUNT_OPEN);
        assertThat(handler.requiredAuthorityToSubmit()).isEqualTo("CREATE_ACCOUNT");
        assertThat(handler.requiredAuthorityToApprove()).isEqualTo("APPROVE_ACCOUNT");
        assertThat(handler.payloadType()).isEqualTo(OpenAccountRequest.class);
    }

    @Test
    void validate_throwsWhenCustomerMissing() {
        try {
            provision();
            OpenAccountRequest req = new OpenAccountRequest(
                UUID.randomUUID(), "SAVINGS", null, "NGN", BigDecimal.ZERO, BigDecimal.ZERO);
            assertThatThrownBy(() -> handler.validate(req))
                .isInstanceOf(BaasException.class)
                .hasMessageContaining("Customer");
        } finally {
            PartnerContext.clear();
        }
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -o test -Dtest=AccountOpenCommandHandlerTest`
Expected: FAIL — `AccountOpenCommandHandler` does not exist.

- [ ] **Step 3: Create the handler**

`account/AccountOpenCommandHandler.java`:

```java
package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.account.dto.OpenAccountRequest;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.makerchecker.MakerCheckerCommandHandler;
import com.nubbank.baas.engine.makerchecker.MakerCheckerCommandType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AccountOpenCommandHandler implements MakerCheckerCommandHandler {

    private final AccountService accountService;
    private final CustomerRepository customerRepo;

    @Override public String commandType() { return MakerCheckerCommandType.ACCOUNT_OPEN; }
    @Override public String requiredAuthorityToSubmit() { return "CREATE_ACCOUNT"; }
    @Override public String requiredAuthorityToApprove() { return "APPROVE_ACCOUNT"; }
    @Override public Class<?> payloadType() { return OpenAccountRequest.class; }

    @Override
    public void validate(Object payload) {
        OpenAccountRequest req = (OpenAccountRequest) payload;
        if (customerRepo.findById(req.customerId()).isEmpty())
            throw BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer " + req.customerId() + " not found");
    }

    @Override
    public UUID execute(Object payload) {
        // Replays the SAME service method the synchronous POST /accounts uses (spec §6 cardinal rule).
        return accountService.open((OpenAccountRequest) payload).id();
    }
}
```

> If `CustomerRepository` is not in `com.nubbank.baas.engine.customer`, fix the import to its real package (a compile error will point you there). If `AccountDetailResponse`'s id accessor is not `.id()`, adjust `execute` to the real accessor.

- [ ] **Step 4: Run it to confirm it passes**

Run: `./mvnw -o test -Dtest=AccountOpenCommandHandlerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountOpenCommandHandler.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountOpenCommandHandlerTest.java
git commit -m "feat(engine): AccountOpenCommandHandler — replays AccountService.open on approve"
```

---

### Task 5: MakerCheckerTaskService — submit, approve, reject, withdraw, list, config

This task builds the whole orchestration service and a comprehensive integration test that drives the service methods directly (the HTTP layer is Task 6). The test sets `PartnerContext` (for tenant routing + environment + current user) and `SecurityContextHolder` (for the dynamic authority checks).

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerTaskService.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerTaskServiceTest.java`

- [ ] **Step 1: Write the failing test**

`baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerTaskServiceTest.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.AccountRepository;
import com.nubbank.baas.engine.account.dto.OpenAccountRequest;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MakerCheckerTaskServiceTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository partnerUserRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired MakerCheckerTaskService service;
    @Autowired MakerCheckerConfigRepository configRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired CustomerRepository customerRepo;

    private PartnerOrganization org;
    private String schema;

    private void provision() {
        schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        org = orgRepo.save(PartnerOrganization.builder()
            .name("Svc").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("s@t.com").build());
        provisioning.provision(org.getId(), schema);
    }

    /** Create an active partner_users row whose id is used as the JWT subject / made_by. */
    private UUID makeUser(String roleName) {
        UUID id = partnerUserRepo.save(PartnerUser.builder()
            .organization(org).email(UUID.randomUUID() + "@t.com")
            .passwordHash("x").role(roleName).active(true).build()).getId();
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            Role r = roleRepo.findByName(roleName).orElseThrow();
            userRoleRepo.save(UserRole.builder().userId(id).role(r).build());
        } finally { PartnerContext.clear(); }
        return id;
    }

    /** Set tenant context + security authorities for a user, then run the body. */
    private void actAs(UUID userId, List<String> authorities, Runnable body) {
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", userId.toString()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            userId.toString(), null, authorities.stream().map(SimpleGrantedAuthority::new).toList()));
        try { body.run(); }
        finally { SecurityContextHolder.clearContext(); PartnerContext.clear(); }
    }

    private UUID seedCustomer() {
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            return customerRepo.save(Customer.builder()
                .firstNameEncrypted("Ada").lastNameEncrypted("Lovelace")
                .emailEncrypted("ada@t.com").phoneEncrypted("0800").build()).getId();
        } finally { PartnerContext.clear(); }
    }

    private void enableAccountOpen() {
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            MakerCheckerConfig c = configRepo.findById("ACCOUNT_OPEN").orElseThrow();
            c.setEnabled(true);
            configRepo.save(c);
        } finally { PartnerContext.clear(); }
    }

    private OpenAccountRequest req(UUID customerId) {
        return new OpenAccountRequest(customerId, "SAVINGS", null, "NGN", BigDecimal.ZERO, BigDecimal.ZERO);
    }

    // --- guard / submit -------------------------------------------------

    @Test
    void notGuarded_whenConfigDisabled_returnsEmpty() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            assertThat(service.submitIfGuarded("ACCOUNT_OPEN", req(cust))).isEmpty());
    }

    @Test
    void notGuarded_whenSandbox_returnsEmpty() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        // SANDBOX environment in context → never guarded
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "SANDBOX", "SANDBOX", "JWT", maker.toString()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            maker.toString(), null, List.of(new SimpleGrantedAuthority("CREATE_ACCOUNT"))));
        try { assertThat(service.submitIfGuarded("ACCOUNT_OPEN", req(cust))).isEmpty(); }
        finally { SecurityContextHolder.clearContext(); PartnerContext.clear(); }
    }

    @Test
    void guarded_persistsPendingTask_noAccountCreated() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        actAs(maker, List.of("CREATE_ACCOUNT"), () -> {
            Optional<MakerCheckerTask> t = service.submitIfGuarded("ACCOUNT_OPEN", req(cust));
            assertThat(t).isPresent();
            assertThat(t.get().getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(t.get().getMadeBy()).isEqualTo(maker);
            assertThat(accountRepo.count()).isZero();   // nothing entered the domain tables
        });
    }

    // --- approve --------------------------------------------------------

    @Test
    void approve_createsAccount_attributedToMaker() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID approver = makeUser(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();

        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());

        actAs(approver, List.of("APPROVE_ACCOUNT", "READ_ACCOUNT"), () -> {
            MakerCheckerTask done = service.approve(taskId[0]);
            assertThat(done.getStatus()).isEqualTo(TaskStatus.APPROVED);
            assertThat(done.getCheckedBy()).isEqualTo(approver);
            assertThat(done.getResultId()).isNotNull();
        });
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { assertThat(accountRepo.count()).isEqualTo(1); }
        finally { PartnerContext.clear(); }
    }

    @Test
    void approve_byMaker_isForbidden_fourEyes() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        // maker also holds APPROVE_ACCOUNT but is still blocked from approving own task
        actAs(maker, List.of("CREATE_ACCOUNT", "APPROVE_ACCOUNT"), () ->
            assertThatThrownBy(() -> service.approve(taskId[0]))
                .isInstanceOf(BaasException.class).hasMessageContaining("maker"));
    }

    @Test
    void approve_withoutApproveAuthority_isForbidden() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID other = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        actAs(other, List.of("CREATE_ACCOUNT"), () ->   // no APPROVE_ACCOUNT
            assertThatThrownBy(() -> service.approve(taskId[0]))
                .isInstanceOf(BaasException.class).hasMessageContaining("APPROVE_ACCOUNT"));
    }

    @Test
    void approve_revokedMaker_isBlocked() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID approver = makeUser(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        // strip the maker's role after submit
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { userRoleRepo.deleteByUserId(maker); } finally { PartnerContext.clear(); }
        actAs(approver, List.of("APPROVE_ACCOUNT", "READ_ACCOUNT"), () ->
            assertThatThrownBy(() -> service.approve(taskId[0]))
                .isInstanceOf(BaasException.class).hasMessageContaining("maker"));
    }

    @Test
    void approve_drift_customerDeleted_rollsBack_taskStaysPending() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID approver = makeUser(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        // delete the customer between submit and approve
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { customerRepo.deleteById(cust); } finally { PartnerContext.clear(); }
        actAs(approver, List.of("APPROVE_ACCOUNT", "READ_ACCOUNT"), () ->
            assertThatThrownBy(() -> service.approve(taskId[0])).isInstanceOf(BaasException.class));
        // task remains PENDING, no account created
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            assertThat(service.get(taskId[0]).getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(accountRepo.count()).isZero();
        } finally { PartnerContext.clear(); }
    }

    @Test
    void approve_twice_secondIsConflict_oneAccount() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID approver = makeUser(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        actAs(approver, List.of("APPROVE_ACCOUNT", "READ_ACCOUNT"), () -> {
            service.approve(taskId[0]);
            assertThatThrownBy(() -> service.approve(taskId[0]))
                .isInstanceOf(BaasException.class).hasMessageContaining("PENDING");
        });
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { assertThat(accountRepo.count()).isEqualTo(1); } finally { PartnerContext.clear(); }
    }

    // --- reject / withdraw ----------------------------------------------

    @Test
    void reject_marksRejected_noAccount() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID approver = makeUser(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        actAs(approver, List.of("APPROVE_ACCOUNT"), () -> {
            MakerCheckerTask t = service.reject(taskId[0], "not now");
            assertThat(t.getStatus()).isEqualTo(TaskStatus.REJECTED);
            assertThat(t.getRejectReason()).isEqualTo("not now");
        });
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { assertThat(accountRepo.count()).isZero(); } finally { PartnerContext.clear(); }
    }

    @Test
    void withdraw_byMakerOk_byOtherForbidden() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID other = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        actAs(other, List.of("CREATE_ACCOUNT"), () ->
            assertThatThrownBy(() -> service.withdraw(taskId[0]))
                .isInstanceOf(BaasException.class).hasMessageContaining("maker"));
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            assertThat(service.withdraw(taskId[0]).getStatus()).isEqualTo(TaskStatus.WITHDRAWN));
    }

    // --- config / viability ---------------------------------------------

    @Test
    void enableConfig_withNoEligibleApprover_isConflict() {
        provision();
        // no PARTNER_APPROVER and no superuser user exists
        UUID admin = UUID.randomUUID();
        actAs(admin, List.of("MANAGE_MAKER_CHECKER"), () ->
            assertThatThrownBy(() -> service.updateConfig("ACCOUNT_OPEN", true))
                .isInstanceOf(BaasException.class).hasMessageContaining("NO_ELIGIBLE_APPROVER"));
    }

    @Test
    void enableConfig_withApproverPresent_succeeds() {
        provision();
        makeUser(PartnerRoles.APPROVER);
        UUID admin = UUID.randomUUID();
        actAs(admin, List.of("MANAGE_MAKER_CHECKER"), () ->
            assertThat(service.updateConfig("ACCOUNT_OPEN", true).isEnabled()).isTrue());
    }

    // --- live validity --------------------------------------------------

    @Test
    void dryRun_flagsNowInvalidTask() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            assertThat(service.dryRunInvalidReason(service.get(taskId[0]))).isNull();   // valid now
            customerRepo.deleteById(cust);
            assertThat(service.dryRunInvalidReason(service.get(taskId[0]))).contains("Customer");  // invalid
        } finally { PartnerContext.clear(); }
    }
}
```

> **Verify before coding:** confirm `Customer.builder()` field names (`firstNameEncrypted`, `lastNameEncrypted`, `emailEncrypted`, `phoneEncrypted`) and `AccountRepository` package/`count()`. If a required non-null Customer field is missing, add it to `seedCustomer()`. If `PartnerUser.builder()` requires more fields, add them. These are test-fixture details; the service code below does not depend on them.

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -o test -Dtest=MakerCheckerTaskServiceTest`
Expected: FAIL — `MakerCheckerTaskService` does not exist.

- [ ] **Step 3: Write the service**

`makerchecker/MakerCheckerTaskService.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubbank.baas.engine.auth.AuthorityResolver;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.partner.PartnerUser;
import com.nubbank.baas.engine.partner.PartnerUserRepository;
import com.nubbank.baas.engine.role.UserRoleRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MakerCheckerTaskService {

    private final MakerCheckerTaskRepository taskRepo;
    private final MakerCheckerConfigRepository configRepo;
    private final MakerCheckerCommandRegistry registry;
    private final AuthorityResolver authorityResolver;
    private final UserRoleRepository userRoleRepo;
    private final PartnerUserRepository partnerUserRepo;
    private final ObjectMapper objectMapper;

    /**
     * Submit-or-defer. Returns empty when the command is NOT guarded (the caller then executes
     * synchronously, today's behaviour). Returns a PENDING task when guarded — nothing enters
     * the domain tables until approval. Spec §5.
     */
    @Transactional
    public Optional<MakerCheckerTask> submitIfGuarded(String commandType, Object payload) {
        MakerCheckerCommandHandler handler = registry.require(commandType);
        if (!isGuarded(commandType)) return Optional.empty();

        handler.validate(payload);   // courtesy validation — non-authoritative (spec §5/§6)

        UUID makerId = currentUserId();
        if (!currentAuthorities().contains(handler.requiredAuthorityToSubmit()))
            throw BaasException.forbidden("MISSING_SUBMIT_AUTHORITY",
                "Missing authority " + handler.requiredAuthorityToSubmit());

        String json;
        try { json = objectMapper.writeValueAsString(payload); }
        catch (Exception e) { throw BaasException.badRequest("PAYLOAD_SERIALIZATION", "Cannot serialize command payload"); }

        MakerCheckerTask task = MakerCheckerTask.builder()
            .commandType(commandType).payload(json).madeBy(makerId).status(TaskStatus.PENDING).build();
        return Optional.of(taskRepo.save(task));
    }

    private boolean isGuarded(String commandType) {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null || !"PRODUCTION".equals(ctx.environment())) return false;   // spec §8 / D4
        return configRepo.findById(commandType).map(MakerCheckerConfig::isEnabled).orElse(false);
    }

    /** Approve: four-eyes + authority re-checks + replay-execute, all in one transaction. Spec §5/§7. */
    @Transactional
    public MakerCheckerTask approve(UUID taskId) {
        MakerCheckerTask task = taskRepo.findByIdForUpdate(taskId)
            .orElseThrow(() -> BaasException.notFound("TASK_NOT_FOUND", "Task " + taskId + " not found"));
        requirePending(task);
        MakerCheckerCommandHandler handler = registry.require(task.getCommandType());
        UUID checkerId = currentUserId();

        if (checkerId.equals(task.getMadeBy()))
            throw BaasException.forbidden("SELF_APPROVAL", "A task cannot be approved by its maker");

        if (!currentAuthorities().contains(handler.requiredAuthorityToApprove()))
            throw BaasException.forbidden("MISSING_APPROVE_AUTHORITY",
                "Missing authority " + handler.requiredAuthorityToApprove());

        // Maker must still be active AND still hold the submit authority (closes the revocation backdoor).
        boolean makerActive = partnerUserRepo.findById(task.getMadeBy())
            .map(PartnerUser::isActive).orElse(false);
        boolean makerStillAuthorised = authorityResolver.partnerUserAuthorities(task.getMadeBy())
            .contains(handler.requiredAuthorityToSubmit());
        if (!makerActive || !makerStillAuthorised)
            throw BaasException.forbidden("MAKER_NO_LONGER_AUTHORISED",
                "Original maker is no longer active or authorised");

        // Re-validate against current state by replaying the real service method; on any failure the
        // whole transaction rolls back (status flip + side effect undo together) and the task stays PENDING.
        Object payload = deserialize(task, handler);
        UUID resultId = handler.execute(payload);

        task.setStatus(TaskStatus.APPROVED);
        task.setCheckedBy(checkerId);
        task.setCheckedAt(Instant.now());
        task.setResultId(resultId);
        return taskRepo.save(task);   // @Version guards the double-approve race
    }

    @Transactional
    public MakerCheckerTask reject(UUID taskId, String reason) {
        MakerCheckerTask task = taskRepo.findByIdForUpdate(taskId)
            .orElseThrow(() -> BaasException.notFound("TASK_NOT_FOUND", "Task " + taskId + " not found"));
        requirePending(task);
        MakerCheckerCommandHandler handler = registry.require(task.getCommandType());
        if (!currentAuthorities().contains(handler.requiredAuthorityToApprove()))
            throw BaasException.forbidden("MISSING_APPROVE_AUTHORITY",
                "Missing authority " + handler.requiredAuthorityToApprove());
        task.setStatus(TaskStatus.REJECTED);
        task.setCheckedBy(currentUserId());
        task.setCheckedAt(Instant.now());
        task.setRejectReason(reason);
        return taskRepo.save(task);
    }

    @Transactional
    public MakerCheckerTask withdraw(UUID taskId) {
        MakerCheckerTask task = taskRepo.findByIdForUpdate(taskId)
            .orElseThrow(() -> BaasException.notFound("TASK_NOT_FOUND", "Task " + taskId + " not found"));
        requirePending(task);
        if (!currentUserId().equals(task.getMadeBy()))
            throw BaasException.forbidden("NOT_TASK_MAKER", "Only the maker may withdraw their own task");
        task.setStatus(TaskStatus.WITHDRAWN);
        return taskRepo.save(task);
    }

    @Transactional(readOnly = true)
    public List<MakerCheckerTask> list(TaskStatus status, String commandType) {
        if (status != null && commandType != null)
            return taskRepo.findByStatusAndCommandTypeOrderByMadeAtDesc(status, commandType);
        if (status != null)      return taskRepo.findByStatusOrderByMadeAtDesc(status);
        if (commandType != null) return taskRepo.findByCommandTypeOrderByMadeAtDesc(commandType);
        return taskRepo.findAllByOrderByMadeAtDesc();
    }

    @Transactional(readOnly = true)
    public MakerCheckerTask get(UUID taskId) {
        return taskRepo.findById(taskId)   // tenant-schema isolation makes other-org ids naturally 404 (spec §9)
            .orElseThrow(() -> BaasException.notFound("TASK_NOT_FOUND", "Task " + taskId + " not found"));
    }

    /** Dry-run the handler's validation against current state; null = valid, else the failure message (spec §7.2). */
    @Transactional(readOnly = true)
    public String dryRunInvalidReason(MakerCheckerTask task) {
        MakerCheckerCommandHandler handler = registry.require(task.getCommandType());
        try { handler.validate(deserialize(task, handler)); return null; }
        catch (BaasException ex) { return ex.getMessage(); }
    }

    @Transactional(readOnly = true)
    public List<MakerCheckerConfig> listConfig() { return configRepo.findAll(); }

    /** Viability guard: cannot enable a command with no eligible approver (spec §8 / D5). */
    @Transactional
    public MakerCheckerConfig updateConfig(String commandType, boolean enabled) {
        MakerCheckerCommandHandler handler = registry.require(commandType);
        if (enabled && countEligibleApprovers(handler.requiredAuthorityToApprove()) < 1)
            throw BaasException.conflict("NO_ELIGIBLE_APPROVER",
                "Cannot enable " + commandType + ": no user holds " + handler.requiredAuthorityToApprove());
        MakerCheckerConfig cfg = configRepo.findById(commandType)
            .orElseGet(() -> MakerCheckerConfig.builder().commandType(commandType).build());
        cfg.setEnabled(enabled);
        return configRepo.save(cfg);
    }

    private long countEligibleApprovers(String approveAuthority) {
        // Explicit holders of the APPROVE_* permission, plus superusers (who hold everything implicitly).
        // Double-counting a user who is both is harmless for the >=1 viability predicate.
        return userRoleRepo.countDistinctUsersWithPermission(approveAuthority)
             + userRoleRepo.countDistinctSuperusers();
    }

    // --- helpers --------------------------------------------------------

    private void requirePending(MakerCheckerTask task) {
        if (task.getStatus() != TaskStatus.PENDING)
            throw BaasException.conflict("TASK_NOT_PENDING", "Task is " + task.getStatus() + ", not PENDING");
    }

    private Object deserialize(MakerCheckerTask task, MakerCheckerCommandHandler handler) {
        try { return objectMapper.readValue(task.getPayload(), handler.payloadType()); }
        catch (Exception e) { throw BaasException.badRequest("PAYLOAD_DESERIALIZATION", "Corrupt command payload"); }
    }

    private UUID currentUserId() {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null || ctx.userId() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        try { return UUID.fromString(ctx.userId()); }
        catch (IllegalArgumentException e) {
            throw BaasException.unauthorized("INVALID_PRINCIPAL", "Principal is not a user");
        }
    }

    private Set<String> currentAuthorities() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Set.of();
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `./mvnw -o test -Dtest=MakerCheckerTaskServiceTest`
Expected: PASS (all test methods green).

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerTaskService.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerTaskServiceTest.java
git commit -m "feat(engine): MakerCheckerTaskService — submit/approve/reject/withdraw/config + four-eyes"
```

---

### Task 6: REST controller + AccountController 202/201 integration

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/dto/TaskResponse.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/dto/TaskDetailResponse.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/dto/ConfigResponse.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/dto/ConfigUpdateRequest.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/dto/RejectRequest.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/MakerCheckerTaskController.java`
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerHttpTest.java`

- [ ] **Step 1: Write the failing test**

`baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerHttpTest.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.AccountRepository;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class MakerCheckerHttpTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository partnerUserRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired MakerCheckerConfigRepository configRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired CustomerRepository customerRepo;

    private PartnerOrganization org;
    private String schema;

    private void provision() {
        schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        org = orgRepo.save(PartnerOrganization.builder()
            .name("Http").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("http@t.com").build());
        provisioning.provision(org.getId(), schema);
    }

    private String tokenFor(UUID userId, String roleName) {
        partnerUserRepo.save(PartnerUser.builder().id(userId).organization(org)
            .email(userId + "@t.com").passwordHash("x").role(roleName).active(true).build());
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { userRoleRepo.save(UserRole.builder().userId(userId)
            .role(roleRepo.findByName(roleName).orElseThrow()).build()); }
        finally { PartnerContext.clear(); }
        return partnerJwtService.issue(userId.toString(), userId + "@t.com", roleName,
            org.getId().toString(), org.getName(), schema, "PRO", "PRODUCTION");
    }

    private UUID seedCustomer() {
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { return customerRepo.save(Customer.builder()
            .firstNameEncrypted("Ada").lastNameEncrypted("Lovelace")
            .emailEncrypted("ada@t.com").phoneEncrypted("0800").build()).getId(); }
        finally { PartnerContext.clear(); }
    }

    private void enableAccountOpen() {
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { MakerCheckerConfig c = configRepo.findById("ACCOUNT_OPEN").orElseThrow();
              c.setEnabled(true); configRepo.save(c); }
        finally { PartnerContext.clear(); }
    }

    private HttpHeaders bearer(String jwt) {
        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void guardedPostAccounts_returns202_thenApproveCreatesAccount() {
        provision();
        UUID maker = UUID.randomUUID(), approver = UUID.randomUUID();
        String makerJwt = tokenFor(maker, PartnerRoles.MAKER);
        String approverJwt = tokenFor(approver, PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();

        // guarded POST /accounts → 202 + taskId
        String body = "{\"customerId\":\"" + cust + "\",\"accountTypeLabel\":\"SAVINGS\",\"currencyCode\":\"NGN\"}";
        ResponseEntity<Map> create = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(body, bearer(makerJwt)), Map.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map data = (Map) create.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("PENDING");
        String taskId = (String) data.get("id");

        // no account yet
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { assertThat(accountRepo.count()).isZero(); } finally { PartnerContext.clear(); }

        // maker cannot approve own task
        ResponseEntity<Map> selfApprove = restTemplate.exchange("/baas/v1/maker-checker/tasks/" + taskId + "/approve",
            HttpMethod.POST, new HttpEntity<>(bearer(makerJwt)), Map.class);
        assertThat(selfApprove.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // approver approves → 200, account created
        ResponseEntity<Map> approve = restTemplate.exchange("/baas/v1/maker-checker/tasks/" + taskId + "/approve",
            HttpMethod.POST, new HttpEntity<>(bearer(approverJwt)), Map.class);
        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { assertThat(accountRepo.count()).isEqualTo(1); } finally { PartnerContext.clear(); }
    }

    @Test
    void unguardedPostAccounts_returns201_directly() {
        provision();
        UUID maker = UUID.randomUUID();
        String makerJwt = tokenFor(maker, PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        // config left OFF → synchronous path
        String body = "{\"customerId\":\"" + cust + "\",\"accountTypeLabel\":\"SAVINGS\",\"currencyCode\":\"NGN\"}";
        ResponseEntity<Map> create = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(body, bearer(makerJwt)), Map.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((Map) create.getBody().get("data")).containsKey("accountNumber");
    }

    @Test
    void inbox_listsPendingTask() {
        provision();
        UUID maker = UUID.randomUUID();
        String makerJwt = tokenFor(maker, PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        String body = "{\"customerId\":\"" + cust + "\",\"accountTypeLabel\":\"SAVINGS\",\"currencyCode\":\"NGN\"}";
        restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(body, bearer(makerJwt)), Map.class);

        ResponseEntity<Map> list = restTemplate.exchange("/baas/v1/maker-checker/tasks?status=PENDING",
            HttpMethod.GET, new HttpEntity<>(bearer(makerJwt)), Map.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> tasks = (List<?>) list.getBody().get("data");
        assertThat(tasks).hasSize(1);
    }
}
```

> **Verify before coding:** confirm `PartnerUser.builder().id(...)` is allowed (the entity uses `@GeneratedValue` — if id can't be set on a new row, instead let it generate and capture the returned id, then issue the JWT with that id). Adjust `tokenFor` accordingly so the JWT subject equals the persisted `partner_users.id`.

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -o test -Dtest=MakerCheckerHttpTest`
Expected: FAIL — controller + DTOs don't exist; `POST /accounts` still returns 201 for the guarded case.

- [ ] **Step 3: Create the DTOs**

`makerchecker/dto/TaskResponse.java`:

```java
package com.nubbank.baas.engine.makerchecker.dto;

import com.nubbank.baas.engine.makerchecker.MakerCheckerTask;
import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
    UUID id, String commandType, String status, UUID madeBy, Instant madeAt,
    UUID checkedBy, Instant checkedAt, UUID resultId, String rejectReason
) {
    public static TaskResponse of(MakerCheckerTask t) {
        return new TaskResponse(t.getId(), t.getCommandType(), t.getStatus().name(),
            t.getMadeBy(), t.getMadeAt(), t.getCheckedBy(), t.getCheckedAt(), t.getResultId(), t.getRejectReason());
    }
}
```

`makerchecker/dto/TaskDetailResponse.java`:

```java
package com.nubbank.baas.engine.makerchecker.dto;

import com.nubbank.baas.engine.makerchecker.MakerCheckerTask;
import java.time.Instant;
import java.util.UUID;

public record TaskDetailResponse(
    UUID id, String commandType, String payload, String status, UUID madeBy, Instant madeAt,
    UUID checkedBy, Instant checkedAt, UUID resultId, String rejectReason,
    boolean valid, String wouldFailBecause
) {
    public static TaskDetailResponse of(MakerCheckerTask t, String invalidReason) {
        return new TaskDetailResponse(t.getId(), t.getCommandType(), t.getPayload(), t.getStatus().name(),
            t.getMadeBy(), t.getMadeAt(), t.getCheckedBy(), t.getCheckedAt(), t.getResultId(), t.getRejectReason(),
            invalidReason == null, invalidReason);
    }
}
```

`makerchecker/dto/ConfigResponse.java`:

```java
package com.nubbank.baas.engine.makerchecker.dto;

import com.nubbank.baas.engine.makerchecker.MakerCheckerConfig;

public record ConfigResponse(String commandType, boolean enabled) {
    public static ConfigResponse of(MakerCheckerConfig c) {
        return new ConfigResponse(c.getCommandType(), c.isEnabled());
    }
}
```

`makerchecker/dto/ConfigUpdateRequest.java`:

```java
package com.nubbank.baas.engine.makerchecker.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfigUpdateRequest(@NotBlank String commandType, boolean enabled) {}
```

`makerchecker/dto/RejectRequest.java`:

```java
package com.nubbank.baas.engine.makerchecker.dto;

public record RejectRequest(String reason) {}
```

- [ ] **Step 4: Create the controller**

`makerchecker/MakerCheckerTaskController.java`:

```java
package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.makerchecker.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/maker-checker")
@RequiredArgsConstructor
public class MakerCheckerTaskController {

    private final MakerCheckerTaskService service;

    @GetMapping("/tasks")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> list(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) String type) {
        List<TaskResponse> tasks = service.list(status, type).stream().map(TaskResponse::of).toList();
        return ResponseEntity.ok(ApiResponse.ok(tasks));
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<ApiResponse<TaskDetailResponse>> get(@PathVariable UUID id) {
        MakerCheckerTask task = service.get(id);
        String invalidReason = service.dryRunInvalidReason(task);
        return ResponseEntity.ok(ApiResponse.ok(TaskDetailResponse.of(task, invalidReason)));
    }

    // No static @PreAuthorize: the per-command APPROVE_* authority is checked dynamically in the service.
    @PostMapping("/tasks/{id}/approve")
    public ResponseEntity<ApiResponse<TaskResponse>> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(TaskResponse.of(service.approve(id))));
    }

    @PostMapping("/tasks/{id}/reject")
    public ResponseEntity<ApiResponse<TaskResponse>> reject(@PathVariable UUID id,
            @RequestBody(required = false) RejectRequest req) {
        String reason = req == null ? null : req.reason();
        return ResponseEntity.ok(ApiResponse.ok(TaskResponse.of(service.reject(id, reason))));
    }

    @PostMapping("/tasks/{id}/withdraw")
    public ResponseEntity<ApiResponse<TaskResponse>> withdraw(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(TaskResponse.of(service.withdraw(id))));
    }

    @GetMapping("/config")
    @PreAuthorize("hasAuthority('MANAGE_MAKER_CHECKER')")
    public ResponseEntity<ApiResponse<List<ConfigResponse>>> getConfig() {
        List<ConfigResponse> cfgs = service.listConfig().stream().map(ConfigResponse::of).toList();
        return ResponseEntity.ok(ApiResponse.ok(cfgs));
    }

    @PutMapping("/config")
    @PreAuthorize("hasAuthority('MANAGE_MAKER_CHECKER')")
    public ResponseEntity<ApiResponse<ConfigResponse>> updateConfig(@Valid @RequestBody ConfigUpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(ConfigResponse.of(service.updateConfig(req.commandType(), req.enabled()))));
    }
}
```

- [ ] **Step 5: Wire AccountController to the framework**

In `account/AccountController.java`: add the field to the constructor dependencies and rewrite the `open` method. Add imports for `MakerCheckerTaskService`, `MakerCheckerTask`, `MakerCheckerCommandType`, `TaskResponse`, and `java.util.Optional`.

Add to the field list (the class is `@RequiredArgsConstructor`):

```java
    private final MakerCheckerTaskService makerChecker;
```

Replace the existing `open` method with:

```java
    @PreAuthorize("hasAuthority('CREATE_ACCOUNT')")
    @PostMapping
    public ResponseEntity<ApiResponse<Object>> open(@Valid @RequestBody OpenAccountRequest req) {
        Optional<MakerCheckerTask> deferred = makerChecker.submitIfGuarded(MakerCheckerCommandType.ACCOUNT_OPEN, req);
        if (deferred.isPresent()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.<Object>ok(TaskResponse.of(deferred.get())));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.<Object>ok(accountService.open(req)));
    }
```

Imports to add at the top of `AccountController.java`:

```java
import com.nubbank.baas.engine.makerchecker.MakerCheckerCommandType;
import com.nubbank.baas.engine.makerchecker.MakerCheckerTask;
import com.nubbank.baas.engine.makerchecker.MakerCheckerTaskService;
import com.nubbank.baas.engine.makerchecker.dto.TaskResponse;
import java.util.Optional;
```

> Only the `open` method's return type changes (`ApiResponse<AccountDetailResponse>` → `ApiResponse<Object>`). Leave every other method in `AccountController` untouched.

- [ ] **Step 6: Run it to confirm it passes**

Run: `./mvnw -o test -Dtest=MakerCheckerHttpTest`
Expected: PASS.

- [ ] **Step 7: Run the full suite to confirm no regressions**

Run: `./mvnw -o test`
Expected: BUILD SUCCESS, 0 failures (existing account/RBAC tests still green; the synchronous `POST /accounts` path is unchanged when config is OFF).

- [ ] **Step 8: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/makerchecker/ \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/makerchecker/MakerCheckerHttpTest.java
git commit -m "feat(engine): maker-checker REST controller + POST /accounts 202/201 integration"
```

---

### Task 7: Documentation + session close (completion gate)

This task satisfies the BaaS skill's End-of-Session Gate. No production code changes.

**Files:**
- Modify: `docs/api-reference.html`
- Modify: `baas-log.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update `docs/api-reference.html`**

Add a "Maker-Checker (Four-Eyes)" section documenting the six new endpoints and the changed account-open contract. Find the existing endpoint-table markup style in the file and mirror it exactly. Document:
- `GET /baas/v1/maker-checker/tasks?status=&type=` — approvals inbox (org-scoped).
- `GET /baas/v1/maker-checker/tasks/{id}` — task detail incl. live `valid` / `wouldFailBecause`.
- `POST /baas/v1/maker-checker/tasks/{id}/approve` — checker approves; requires the command's `APPROVE_*` and `checker ≠ maker`; **200** on success.
- `POST /baas/v1/maker-checker/tasks/{id}/reject` — body `{ "reason": "..." }`.
- `POST /baas/v1/maker-checker/tasks/{id}/withdraw` — original maker only.
- `GET` / `PUT /baas/v1/maker-checker/config` — `MANAGE_MAKER_CHECKER`; PUT body `{ "commandType": "ACCOUNT_OPEN", "enabled": true }`; `409 NO_ELIGIBLE_APPROVER` if no eligible approver.
- Note on `POST /baas/v1/accounts`: returns **202** `{ id, status: "PENDING", ... }` when guarded (config enabled + PRODUCTION), else **201** + account as before.

- [ ] **Step 2: Verify the build one more time**

Run: `cd ~/nubbank-baas/baas-engine && ./mvnw -o test`
Expected: BUILD SUCCESS, record the `Tests run: N, Failures: 0` line for the log.

- [ ] **Step 3: Add the `baas-log.md` session entry**

Prepend a new session entry at the top of the Change History using the template in the `/baas` skill. Include: one-line summary + final commit SHA (fill after the docs commit), New/Updated Files table (the makerchecker package, V8 migration, AccountController, UserRoleRepository, api-reference.html), Key Decisions (command-first replay; distinct bean names vs legacy `social` maker-checker; PRODUCTION-only enforcement; viability guard; per-command dynamic approve authority; enumeration-safety via schema isolation), Build Verification line, and the Confirmed Platform Versions block with the SHA from `git log --oneline -1 -- baas-engine/`.

- [ ] **Step 4: Update `CLAUDE.md`**

- Bump the Confirmed Platform Versions SHA to match the latest `baas-engine` commit.
- Add the maker-checker framework to the Module Catalogue (new module, ✅) with its endpoints and the `ACCOUNT_OPEN`-guarded note.
- Add a Known Gotchas row: "New maker-checker beans MUST use distinct simple class names (`MakerCheckerTaskService/Controller/Repository`) — a `MakerCheckerService`/`MakerCheckerController` collides with the legacy `social.*` beans → `ConflictingBeanDefinitionException`."

- [ ] **Step 5: Commit the docs**

```bash
git add docs/api-reference.html baas-log.md CLAUDE.md
git commit -m "docs(baas-log+claude+api): Session N — maker-checker / four-eyes (Spec B, DEF-1C-13)"
```

- [ ] **Step 6: Backfill the SHA references**

Update the just-written `baas-log.md` and `CLAUDE.md` SHA placeholders to the actual `git log --oneline -1 -- baas-engine/` SHA, then amend:

```bash
git add baas-log.md CLAUDE.md
git commit --amend --no-edit
```

---

## Self-Review

**1. Spec coverage** (each spec section → task):
- §3 D1 general framework / `ACCOUNT_OPEN` first → Tasks 3,4 (registry + first handler).
- §3 D2 command-first replay → Task 4 `execute` calls `AccountService.open`; Task 5 `approve` deserializes + replays.
- §3 D3 per-partner/per-command, default OFF → Task 1 config row `enabled=false`; Task 5 `isGuarded`.
- §3 D4 PRODUCTION-only → Task 5 `isGuarded` env check; Task 5 test `notGuarded_whenSandbox`.
- §3 D5 viability guard → Task 2 count queries; Task 5 `updateConfig` + tests.
- §3 D6 execute authoritative → Task 4/5 replay; Task 5 `approve_drift` test.
- §3 D7 per-command `APPROVE_*`, `PARTNER_APPROVER` bundles → Task 1 migration grant; dynamic check in Task 5.
- §3 D8 withdraw, no TTL (seam reserved) → Task 2 `expires_at` column unused; Task 5 `withdraw`.
- §4.1/4.2 data model → Task 1 (DDL) + Task 2 (entities).
- §4.3 `APPROVE_ACCOUNT` + `MANAGE_MAKER_CHECKER` → Task 1.
- §5 flow (202 guarded / 201 not; approve transaction) → Tasks 5,6.
- §6 command registry → Task 3.
- §7.1 four-eyes + maker re-check → Task 5 `approve` (self-approval, maker active+authorised).
- §7.2 informed approval (dry-run) → Task 5 `dryRunInvalidReason`; Task 6 `GET /tasks/{id}`.
- §7.3 withdraw race → Task 5 `withdraw` + `requirePending` + `findByIdForUpdate`.
- §8 config + viability + `MANAGE_MAKER_CHECKER` gate → Tasks 1,5,6.
- §9 API surface (6 endpoints + account 202) → Task 6; other-org → 404 via schema isolation (Task 5 `get`).
- §10 double-approve / stale / revoked / 202 contract / self-approval / viability / TTL seam → Tasks 1,2,5,6.
- §11 testing scenarios → Tasks 5,6 cover guarded happy path, four-eyes, authority, drift, revoked maker, config/env, viability, idempotency, withdraw, live validity.
- §12 relationship to Spec A → Task 1 reuses the `permissions`/`PARTNER_APPROVER` catalogue.
✅ No gaps.

**2. Placeholder scan:** No "TBD"/"handle edge cases"/"similar to Task N". Every code step shows complete code; every "verify before coding" note names the exact thing to confirm and the compile-error fallback. ✅

**3. Type consistency:** `submitIfGuarded(String, Object): Optional<MakerCheckerTask>`, `approve/reject/withdraw(UUID): MakerCheckerTask`, `updateConfig(String, boolean): MakerCheckerConfig`, `dryRunInvalidReason(MakerCheckerTask): String`, `list(TaskStatus, String): List<MakerCheckerTask>` — used identically in service, controller, and tests. `TaskResponse.of`, `TaskDetailResponse.of(task, invalidReason)`, `ConfigResponse.of`, `MakerCheckerCommandHandler` method names match across Tasks 3–6. `findByIdForUpdate`, `findByStatusOrderByMadeAtDesc`, `countDistinctUsersWithPermission`, `countDistinctSuperusers` match between Task 2 repos and Task 5 service. ✅
