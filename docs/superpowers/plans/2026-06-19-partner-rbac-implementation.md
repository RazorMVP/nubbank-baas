# Granular Partner RBAC — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace blanket full authority for first-party partner principals with explicit, role-driven RBAC — a partner admin can create scoped users/keys; the only path to full authority is the `PARTNER_ADMIN` superuser marker.

**Architecture:** Extends the existing tenant-schema RBAC tables (`roles`/`permissions`/`role_permissions`/`user_roles`), already used by Keycloak operators, to partner users and API keys. `AuthorityResolver` gains partner-user and API-key resolution; `PartnerContextFilter` stops granting blanket full authority. A V7 tenant migration adds role markers + seeds built-in roles; an idempotent reconciler migrates + grandfathers existing tenants at startup and seeds the admin grant at provision time.

**Tech Stack:** Java 21 (Homebrew openjdk@21 at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`), Spring Boot 3.5.3, Hibernate (SCHEMA multi-tenancy), Flyway, PostgreSQL 16, Testcontainers + `TestRestTemplate`. Spec: `docs/superpowers/specs/2026-06-18-partner-rbac-design.md`.

**Build/test:** from `baas-engine/`, with `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, run `./mvnw test`. A Postgres Testcontainer boots automatically.

---

## File Structure

**Create**
- `baas-engine/src/main/resources/db/migration/tenant/V7__partner_rbac.sql` — role markers + seeded built-in roles + `MANAGE_*` permissions.
- `baas-engine/src/main/java/com/nubbank/baas/engine/role/PartnerRoles.java` — well-known role-name constants.
- `baas-engine/src/main/java/com/nubbank/baas/engine/partner/rbac/PartnerRbacReconciler.java` — provision-time + startup backfill (migrate, grandfather, admin grant).
- `baas-engine/src/main/java/com/nubbank/baas/engine/partner/user/PartnerUserController.java`, `PartnerUserService.java`, `dto/CreatePartnerUserRequest.java`, `dto/UpdateUserRolesRequest.java`, `dto/PartnerUserResponse.java`.
- `baas-engine/src/main/java/com/nubbank/baas/engine/partner/key/PartnerApiKeyController.java`, `PartnerApiKeyService.java`, `dto/IssueApiKeyRequest.java`, `dto/IssuedApiKeyResponse.java`.
- Tests under `baas-engine/src/test/java/com/nubbank/baas/engine/...` (one per task below).

**Modify**
- `role/Role.java` — add `builtIn`, `roleScope`, `isSuperuser`.
- `role/RoleRepository.java` — `findByRoleScopeIn(...)`.
- `role/UserRoleRepository.java` — `existsSuperuserRoleByUserId`, `countDistinctUsersWithRole`, `findUserIdsByRoleName`.
- `role/RoleService.java` + `RoleController.java` — scope listing/creation to `PARTNER`/`SHARED`; protect `built_in`.
- `auth/AuthorityResolver.java` — `partnerUserAuthorities`, `apiKeyAuthorities`.
- `tenant/PartnerContextFilter.java` — JWT→partner-user, API_KEY→key resolution; carry key id; drop blanket fallback.
- `tenant/TenantProvisioningService.java` — expose `migrateTenant(schema)`; call the reconciler after provisioning.
- `partner/PartnerUserRepository.java` — `findByOrganizationId`, `countByOrganizationIdAndActiveTrue`.

---

## Task 1: V7 tenant migration — role markers, built-in roles, MANAGE_* permissions

**Files**
- Create: `baas-engine/src/main/resources/db/migration/tenant/V7__partner_rbac.sql`
- Create test: `baas-engine/src/test/java/com/nubbank/baas/engine/partner/rbac/V7MigrationTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.engine.partner.rbac;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class V7MigrationTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired JdbcTemplate jdbc;

    @Test
    void v7_addsMarkers_seedsBuiltInRoles_andManagePermissions() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("V7 Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("v7@test.com").build());
        provisioning.provision(org.getId(), schema);

        // new columns exist
        List<Map<String, Object>> cols = jdbc.queryForList(
            "select column_name from information_schema.columns " +
            "where table_schema = ? and table_name = 'roles'", schema);
        Set<String> names = new HashSet<>();
        cols.forEach(c -> names.add((String) c.get("column_name")));
        assertThat(names).contains("built_in", "role_scope", "is_superuser");

        // PARTNER_ADMIN is the superuser marker
        Integer superusers = jdbc.queryForObject(
            "select count(*) from " + schema + ".roles where name = 'PARTNER_ADMIN' and is_superuser = true",
            Integer.class);
        assertThat(superusers).isEqualTo(1);

        // built-in partner roles seeded
        Integer partnerRoles = jdbc.queryForObject(
            "select count(*) from " + schema + ".roles " +
            "where role_scope = 'PARTNER' and name in ('PARTNER_MAKER','PARTNER_APPROVER','PARTNER_VIEWER')",
            Integer.class);
        assertThat(partnerRoles).isEqualTo(3);

        // new MANAGE_* permissions
        Integer managePerms = jdbc.queryForObject(
            "select count(*) from " + schema + ".permissions " +
            "where code in ('MANAGE_PARTNER_USERS','MANAGE_ROLES')", Integer.class);
        assertThat(managePerms).isEqualTo(2);

        // PARTNER_MAKER can CREATE_ACCOUNT but not READ-only; PARTNER_VIEWER is read-only
        Integer makerCreate = jdbc.queryForObject(
            "select count(*) from " + schema + ".role_permissions rp " +
            "join " + schema + ".roles r on r.id = rp.role_id " +
            "join " + schema + ".permissions p on p.id = rp.permission_id " +
            "where r.name = 'PARTNER_MAKER' and p.code = 'CREATE_ACCOUNT'", Integer.class);
        assertThat(makerCreate).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw test -Dtest=V7MigrationTest`
Expected: FAIL — `built_in`/`role_scope`/`is_superuser` columns and the seeded roles do not exist.

- [ ] **Step 3: Write the migration**

```sql
-- V7__partner_rbac.sql — granular partner RBAC: role markers + built-in partner roles.

ALTER TABLE roles ADD COLUMN built_in     BOOLEAN     NOT NULL DEFAULT false;
ALTER TABLE roles ADD COLUMN role_scope   VARCHAR(20) NOT NULL DEFAULT 'OPERATOR';
ALTER TABLE roles ADD COLUMN is_superuser BOOLEAN     NOT NULL DEFAULT false;

-- The existing PARTNER_ADMIN (seeded in V3) becomes the partner superuser marker.
-- Its stale CROSS JOIN role_permissions grant is now irrelevant (the resolver short-circuits
-- on is_superuser); drop it so the marker is the single source of full authority.
UPDATE roles SET is_superuser = true, built_in = true, role_scope = 'SHARED'
 WHERE name = 'PARTNER_ADMIN';
DELETE FROM role_permissions
 WHERE role_id IN (SELECT id FROM roles WHERE name = 'PARTNER_ADMIN');

-- New management permissions.
INSERT INTO permissions (grouping, code, entity_name, action_name) VALUES
  ('admin', 'MANAGE_PARTNER_USERS', 'PARTNER_USER', 'MANAGE'),
  ('admin', 'MANAGE_ROLES',         'ROLE',         'MANAGE');

-- Built-in PARTNER-scoped roles. PARTNER_APPROVER's APPROVE_* grants are added by the
-- maker-checker migration (Spec B); here it is read-only until then.
INSERT INTO roles (id, name, description, disabled, built_in, role_scope, is_superuser, version, created_at, updated_at) VALUES
  (gen_random_uuid(),'PARTNER_MAKER','Operate (create/transact), cannot approve',false,true,'PARTNER',false,0,now(),now()),
  (gen_random_uuid(),'PARTNER_APPROVER','Approve + read (four-eyes checker)',false,true,'PARTNER',false,0,now(),now()),
  (gen_random_uuid(),'PARTNER_VIEWER','Read-only',false,true,'PARTNER',false,0,now(),now());

-- PARTNER_MAKER: read everything + create/operate.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_CUSTOMER','CREATE_CUSTOMER','UPDATE_CUSTOMER',
                  'READ_ACCOUNT','CREATE_ACCOUNT','DEPOSIT','WITHDRAW',
                  'READ_LOAN','CREATE_LOAN','INITIATE_PAYMENT','RUN_REPORT')
   WHERE r.name = 'PARTNER_MAKER';

-- PARTNER_APPROVER: all READ_* (+ APPROVE_* later, Spec B).
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_CUSTOMER','READ_ACCOUNT','READ_LOAN','RUN_REPORT')
   WHERE r.name = 'PARTNER_APPROVER';

-- PARTNER_VIEWER: read-only.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_CUSTOMER','READ_ACCOUNT','READ_LOAN','RUN_REPORT')
   WHERE r.name = 'PARTNER_VIEWER';
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./mvnw test -Dtest=V7MigrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/resources/db/migration/tenant/V7__partner_rbac.sql \
        baas-engine/src/test/java/com/nubbank/baas/engine/partner/rbac/V7MigrationTest.java
git commit -m "feat(engine): V7 tenant migration — partner role markers + built-in roles

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `Role` entity — map the new columns

**Files**
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/role/Role.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/role/PartnerRoles.java`

- [ ] **Step 1: Add the constants file**

```java
package com.nubbank.baas.engine.role;

/** Well-known built-in role names + scope values. */
public final class PartnerRoles {
    private PartnerRoles() {}
    public static final String ADMIN    = "PARTNER_ADMIN";
    public static final String MAKER    = "PARTNER_MAKER";
    public static final String APPROVER = "PARTNER_APPROVER";
    public static final String VIEWER   = "PARTNER_VIEWER";
    public static final String SCOPE_PARTNER  = "PARTNER";
    public static final String SCOPE_OPERATOR = "OPERATOR";
    public static final String SCOPE_SHARED   = "SHARED";
}
```

- [ ] **Step 2: Add fields to `Role`** (place after the `disabled` field)

```java
    @Column(name = "built_in", nullable = false) private boolean builtIn;
    @Column(name = "role_scope", nullable = false, length = 20) private String roleScope;
    @Column(name = "is_superuser", nullable = false) private boolean superuser;
```

Update `@PrePersist onCreate()` to default scope when unset:

```java
    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now(); disabled = false;
        if (roleScope == null) roleScope = PartnerRoles.SCOPE_PARTNER;
    }
```

- [ ] **Step 3: Run the full suite to confirm mapping compiles + existing tests pass**

Run: `./mvnw test -Dtest=V7MigrationTest,RoleControllerTest`
Expected: PASS (Hibernate validates the entity against the V7 columns).

- [ ] **Step 4: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/role/Role.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/role/PartnerRoles.java
git commit -m "feat(engine): map built_in/role_scope/is_superuser on Role + PartnerRoles constants

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Repository queries (superuser check, role-scope filter, org user listing, last-admin count)

**Files**
- Modify: `role/UserRoleRepository.java`, `role/RoleRepository.java`, `partner/PartnerUserRepository.java`
- Create test: `baas-engine/src/test/java/com/nubbank/baas/engine/role/RbacQueriesTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.engine.role;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RbacQueriesTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;
    @Autowired UserRoleRepository userRoleRepo;

    @Test
    void superuserCheck_and_scopeFilter() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Q").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema).contactEmail("q@t.com").build());
        provisioning.provision(org.getId(), schema);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema,
            "SANDBOX", "SANDBOX", "JWT", null));
        try {
            UUID admin = UUID.randomUUID();
            UUID viewer = UUID.randomUUID();
            UUID adminRole = roleRepo.findAll().stream()
                .filter(r -> r.getName().equals("PARTNER_ADMIN")).findFirst().orElseThrow().getId();
            UUID viewerRole = roleRepo.findAll().stream()
                .filter(r -> r.getName().equals("PARTNER_VIEWER")).findFirst().orElseThrow().getId();
            userRoleRepo.save(UserRole.builder().userId(admin).role(roleRepo.findById(adminRole).get()).build());
            userRoleRepo.save(UserRole.builder().userId(viewer).role(roleRepo.findById(viewerRole).get()).build());

            assertThat(userRoleRepo.existsSuperuserRoleByUserId(admin)).isTrue();
            assertThat(userRoleRepo.existsSuperuserRoleByUserId(viewer)).isFalse();
            assertThat(roleRepo.findByRoleScopeIn(List.of("PARTNER","SHARED")))
                .extracting(Role::getName).contains("PARTNER_MAKER","PARTNER_VIEWER","PARTNER_ADMIN")
                .doesNotContain("TELLER"); // operator-scoped
            assertThat(userRoleRepo.countDistinctUsersWithRole("PARTNER_ADMIN")).isEqualTo(1);
        } finally {
            PartnerContext.clear();
        }
    }
}
```

- [ ] **Step 2: Run it — expect compile failure (methods don't exist)**

Run: `./mvnw test -Dtest=RbacQueriesTest`
Expected: FAIL (cannot resolve `existsSuperuserRoleByUserId`, `findByRoleScopeIn`, `countDistinctUsersWithRole`).

- [ ] **Step 3: Add the queries**

`UserRoleRepository.java` — add:
```java
    @org.springframework.data.jpa.repository.Query(
        "select count(ur) > 0 from UserRole ur join ur.role r where ur.userId = :userId and r.superuser = true")
    boolean existsSuperuserRoleByUserId(java.util.UUID userId);

    @org.springframework.data.jpa.repository.Query(
        "select count(distinct ur.userId) from UserRole ur join ur.role r where r.name = :roleName")
    long countDistinctUsersWithRole(String roleName);

    @org.springframework.data.jpa.repository.Query(
        "select ur.userId from UserRole ur join ur.role r where r.name = :roleName")
    java.util.List<java.util.UUID> findUserIdsByRoleName(String roleName);
```

`RoleRepository.java` — add:
```java
    java.util.List<Role> findByRoleScopeIn(java.util.Collection<String> scopes);
    java.util.Optional<Role> findByName(String name);
```

`PartnerUserRepository.java` — add:
```java
    java.util.List<PartnerUser> findByOrganizationId(java.util.UUID orgId);
    long countByOrganizationIdAndActiveTrue(java.util.UUID orgId);
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./mvnw test -Dtest=RbacQueriesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/role/UserRoleRepository.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/role/RoleRepository.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerUserRepository.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/role/RbacQueriesTest.java
git commit -m "feat(engine): RBAC repo queries — superuser check, scope filter, org users, role count

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `AuthorityResolver` — partner-user + API-key resolution

**Files**
- Modify: `auth/AuthorityResolver.java`, `partner/PartnerApiKeyRepository.java` (no change — `findById` is inherited)
- Create test: `baas-engine/src/test/java/com/nubbank/baas/engine/auth/AuthorityResolverPartnerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorityResolverPartnerTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;
    @Autowired UserRoleRepository userRoleRepo;
    @Autowired AuthorityResolver resolver;
    @Autowired JdbcTemplate jdbc;

    @Test
    void partnerUser_admin_isDynamicFull_others_areScoped() {
        String schema = setupTenant();
        PartnerContext.set(new PartnerContext("x", schema, "SANDBOX", "SANDBOX", "JWT", null));
        try {
            UUID admin = assign(schema, "PARTNER_ADMIN");
            UUID viewer = assign(schema, "PARTNER_VIEWER");
            UUID unassigned = UUID.randomUUID();

            List<String> adminAuth = resolver.partnerUserAuthorities(admin);
            assertThat(adminAuth).contains("CREATE_ACCOUNT", "MANAGE_ROLES", "READ_ACCOUNT");

            // dynamic: a brand-new permission flows to admin without a re-seed
            jdbc.update("insert into " + schema + ".permissions(grouping,code) values('x','BRAND_NEW_PERM')");
            assertThat(resolver.partnerUserAuthorities(admin)).contains("BRAND_NEW_PERM");

            assertThat(resolver.partnerUserAuthorities(viewer))
                .contains("READ_ACCOUNT").doesNotContain("CREATE_ACCOUNT");
            assertThat(resolver.partnerUserAuthorities(unassigned)).isEmpty();
        } finally { PartnerContext.clear(); }
    }

    @Test
    void apiKey_star_isFull_explicit_isScoped_empty_isDenied() {
        String schema = setupTenant();
        PartnerOrganization org = orgRepo.findBySchemaName(schema).orElseThrow();
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "SANDBOX", "SANDBOX", "API_KEY", null));
        try {
            UUID full = key(org, "[\"*\"]");
            UUID scoped = key(org, "[\"READ_ACCOUNT\"]");
            UUID none = key(org, "[]");
            assertThat(resolver.apiKeyAuthorities(full)).contains("CREATE_ACCOUNT");
            assertThat(resolver.apiKeyAuthorities(scoped)).containsExactly("READ_ACCOUNT");
            assertThat(resolver.apiKeyAuthorities(none)).isEmpty();
        } finally { PartnerContext.clear(); }
    }

    // --- helpers ---
    private String setupTenant() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("AR").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema).contactEmail("ar@t.com").build());
        provisioning.provision(org.getId(), schema);
        return schema;
    }
    private UUID assign(String schema, String roleName) {
        UUID user = UUID.randomUUID();
        Role r = roleRepo.findByName(roleName).orElseThrow();
        userRoleRepo.save(UserRole.builder().userId(user).role(r).build());
        return user;
    }
    @Autowired PartnerApiKeyRepository keyRepo;
    private UUID key(PartnerOrganization org, String scopesJson) {
        PartnerApiKey k = PartnerApiKey.builder().organization(org)
            .keyHash(UUID.randomUUID().toString()).keyPrefix("cba_test")
            .scopes(scopesJson).tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX)
            .active(true).build();
        return keyRepo.save(k).getId();
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `./mvnw test -Dtest=AuthorityResolverPartnerTest`
Expected: FAIL (`partnerUserAuthorities` / `apiKeyAuthorities` don't exist).

- [ ] **Step 3: Implement in `AuthorityResolver`** (add fields + methods; keep `fullTenantAuthorities()` — it becomes the marker's engine)

```java
    private final RoleRepository roleRepo;            // add to constructor deps
    private final PartnerApiKeyRepository apiKeyRepo; // add to constructor deps
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper; // add

    /** Partner user: PARTNER_ADMIN marker → dynamic full; otherwise the union of assigned roles' codes. */
    @Transactional(readOnly = true)
    public List<String> partnerUserAuthorities(UUID partnerUserId) {
        if (userRoleRepo.existsSuperuserRoleByUserId(partnerUserId)) {
            return permissionRepo.findAllCodes();
        }
        return userRoleRepo.findPermissionCodesByUserId(partnerUserId);
    }

    /** API key: scopes ["*"] → dynamic full; otherwise the explicit codes; [] → deny. */
    @Transactional(readOnly = true)
    public List<String> apiKeyAuthorities(UUID apiKeyId) {
        return apiKeyRepo.findById(apiKeyId).map(k -> {
            List<String> scopes;
            try {
                scopes = objectMapper.readValue(
                    k.getScopes() == null ? "[]" : k.getScopes(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception ex) { return java.util.Collections.<String>emptyList(); }
            if (scopes.contains("*")) return permissionRepo.findAllCodes();
            return scopes;
        }).orElseGet(java.util.Collections::emptyList);
    }
```

> Note: `@RequiredArgsConstructor` regenerates the constructor from the new `final` fields — no manual constructor edit needed.

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./mvnw test -Dtest=AuthorityResolverPartnerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/auth/AuthorityResolver.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/auth/AuthorityResolverPartnerTest.java
git commit -m "feat(engine): partner-user (dynamic-admin) + API-key scope authority resolution

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `PartnerContextFilter` — role-driven resolution, drop blanket full

**Files**
- Modify: `tenant/PartnerContextFilter.java`
- Create test: `baas-engine/src/test/java/com/nubbank/baas/engine/tenant/PartnerAuthorityIntegrationTest.java`

- [ ] **Step 1: Write the failing test** (a partner JWT with no grants must be denied; with VIEWER it can read but not create)

```java
package com.nubbank.baas.engine.tenant;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerAuthorityIntegrationTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PartnerJwtService jwt;
    @Autowired RoleRepository roleRepo;
    @Autowired UserRoleRepository userRoleRepo;

    private String tokenFor(String schema, UUID userId, PartnerOrganization org) {
        return jwt.issue(userId.toString(), "u@t.com", "PARTNER_USER",
            org.getId().toString(), org.getName(), schema, "PRODUCTION", "PRODUCTION");
    }

    @Test
    void unassignedPartnerUser_isDenied_viewerCanReadNotCreate() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Z").status(PartnerStatus.PRODUCTION).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("z@t.com").build());
        provisioning.provision(org.getId(), schema);

        UUID unassigned = UUID.randomUUID();
        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(tokenFor(schema, unassigned, org));
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts?page=0&size=20",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // READ_ACCOUNT not held

        UUID viewer = UUID.randomUUID();
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { userRoleRepo.save(UserRole.builder().userId(viewer)
            .role(roleRepo.findByName("PARTNER_VIEWER").orElseThrow()).build()); }
        finally { PartnerContext.clear(); }

        HttpHeaders hv = new HttpHeaders(); hv.setBearerAuth(tokenFor(schema, viewer, org));
        assertThat(restTemplate.exchange("/baas/v1/accounts?page=0&size=20",
            HttpMethod.GET, new HttpEntity<>(hv), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (today the partner JWT gets blanket full, so the unassigned user is `200`, not `403`).

Run: `./mvnw test -Dtest=PartnerAuthorityIntegrationTest`
Expected: FAIL (`200 OK` where `403` is asserted).

- [ ] **Step 3: Rewrite `populateAuthorities()` and carry the API-key id**

In `populateAuthorities()`, replace the `if/else` body:
```java
        List<String> codes;
        switch (ctx.authMode()) {
            case "OPERATOR_JWT" -> {
                UUID operatorId;
                try { operatorId = UUID.fromString(ctx.userId()); }
                catch (IllegalArgumentException ex) {
                    log.warn("Operator JWT subject is not a valid UUID — denying request");
                    return;
                }
                codes = authorityResolver.operatorAuthorities(operatorId);
            }
            case "JWT" -> codes = authorityResolver.partnerUserAuthorities(UUID.fromString(ctx.userId()));
            case "API_KEY" -> codes = authorityResolver.apiKeyAuthorities(UUID.fromString(ctx.userId()));
            default -> { return; } // unknown authMode → deny (no blanket full fallback)
        }
```

In `resolveApiKey(...)`, set the key id as the principal so the resolver can read its scopes — change the `PartnerContext` constructor's last argument from `null` to `key.getId().toString()`:
```java
                PartnerContext ctx = new PartnerContext(
                    key.getOrganization().getId().toString(),
                    key.getOrganization().getSchemaName(),
                    key.getTier().name(),
                    key.getEnvironment().name(),
                    "API_KEY",
                    key.getId().toString()   // was: null
                );
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./mvnw test -Dtest=PartnerAuthorityIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Run the broader suite to catch regressions** (operator + API-key auth paths)

Run: `./mvnw test -Dtest=*Authority* -Dtest=PartnerContext*Test`
Expected: PASS. (If existing API-key tests assumed full authority, they will now require explicit `["*"]` scopes — fix those keys in the test fixtures to `["*"]`, matching the grandfather rule.)

- [ ] **Step 6: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/tenant/PartnerContextFilter.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/tenant/PartnerAuthorityIntegrationTest.java
git commit -m "feat(engine): role-driven partner authority resolution; drop blanket full fallback

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Reconciler — provision-time admin grant + idempotent startup backfill

**Files**
- Create: `partner/rbac/PartnerRbacReconciler.java`
- Modify: `tenant/TenantProvisioningService.java` (expose `migrateTenant`, call reconciler after provision)
- Create test: `baas-engine/src/test/java/com/nubbank/baas/engine/partner/rbac/PartnerRbacReconcilerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.engine.partner.rbac;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerRbacReconcilerTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository userRepo;
    @Autowired PartnerApiKeyRepository keyRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PartnerRbacReconciler reconciler;
    @Autowired UserRoleRepository userRoleRepo;

    @Test
    void reconcile_grantsAdminRole_and_grandfathersKeys_idempotently() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("R").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema).contactEmail("r@t.com").build());
        provisioning.provision(org.getId(), schema); // creates schema + runs V1..V7
        PartnerUser admin = userRepo.save(PartnerUser.builder().organization(org)
            .email("admin@r.com").passwordHash("x").role("PARTNER_ADMIN").active(true).build());
        PartnerApiKey key = keyRepo.save(PartnerApiKey.builder().organization(org)
            .keyHash(UUID.randomUUID().toString()).keyPrefix("cba_x").scopes("[]")
            .tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX).active(true).build());

        reconciler.reconcileOrg(org);
        reconciler.reconcileOrg(org); // idempotent

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "SANDBOX","SANDBOX","JWT",null));
        try {
            assertThat(userRoleRepo.existsSuperuserRoleByUserId(admin.getId())).isTrue();
        } finally { PartnerContext.clear(); }
        assertThat(keyRepo.findById(key.getId()).orElseThrow().getScopes()).isEqualTo("[\"*\"]");
    }
}
```

- [ ] **Step 2: Run — expect compile failure** (`PartnerRbacReconciler` / `reconcileOrg` don't exist).

Run: `./mvnw test -Dtest=PartnerRbacReconcilerTest`
Expected: FAIL.

- [ ] **Step 3: Expose `migrateTenant` on `TenantProvisioningService`**

Change `private void runTenantMigrations(String schemaName)` to `public void migrateTenant(String schemaName)` and update its two internal callers in `provision(...)`.

- [ ] **Step 4: Implement the reconciler**

```java
package com.nubbank.baas.engine.partner.rbac;

import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * Grandfathers existing partner principals into explicit grants and seeds the admin grant
 * for newly-provisioned tenants. Runs once at startup (blocking, before traffic) and is
 * called by TenantProvisioningService after each provision. Idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerRbacReconciler implements SmartInitializingSingleton {

    private final PartnerOrganizationRepository orgRepo;
    private final PartnerUserRepository userRepo;
    private final PartnerApiKeyRepository keyRepo;
    private final RoleRepository roleRepo;
    private final UserRoleRepository userRoleRepo;
    private final TenantProvisioningService provisioning;

    /** Blocking startup backfill across all existing tenants (before the web server serves). */
    @Override
    public void afterSingletonsInstantiated() {
        for (PartnerOrganization org : orgRepo.findAll()) {
            try { reconcileOrg(org); }
            catch (Exception ex) { log.error("RBAC reconcile failed for org {}: {}", org.getId(), ex.getMessage()); }
        }
    }

    /** Migrate the tenant schema (idempotent), grant existing users PARTNER_ADMIN, grandfather keys to ["*"]. */
    @Transactional
    public void reconcileOrg(PartnerOrganization org) {
        provisioning.migrateTenant(org.getSchemaName()); // applies V7 to pre-existing schemas; no-op if current
        PartnerContext.set(new PartnerContext(org.getId().toString(), org.getSchemaName(),
            org.getTier().name(), org.getEnvironment().name(), "JWT", null));
        try {
            Role admin = roleRepo.findByName(PartnerRoles.ADMIN).orElseThrow();
            for (PartnerUser u : userRepo.findByOrganizationId(org.getId())) {
                if (userRoleRepo.findById(new UserRoleId(u.getId(), admin.getId())).isEmpty()) {
                    userRoleRepo.save(UserRole.builder().userId(u.getId()).role(admin).build());
                }
            }
        } finally { PartnerContext.clear(); }

        for (PartnerApiKey k : keyRepo.findByOrganizationIdAndActiveTrueOrderByCreatedAtDesc(org.getId())) {
            if (k.getScopes() == null || k.getScopes().replaceAll("\\s","").equals("[]")) {
                k.setScopes("[\"*\"]");
                keyRepo.save(k);
            }
        }
    }
}
```

- [ ] **Step 5: Call the reconciler after provisioning** — in `TenantProvisioningService.provision(...)`, after migrations succeed, reconcile the org. Inject `@Lazy PartnerRbacReconciler reconciler` (breaks the construction cycle) and add at the end of `provision`:
```java
        orgRepo.findById(partnerId).ifPresent(reconciler::reconcileOrg);
```
(Add `private final PartnerOrganizationRepository orgRepo;` and `@org.springframework.context.annotation.Lazy private final PartnerRbacReconciler reconciler;` to `TenantProvisioningService`.)

- [ ] **Step 6: Run the test to confirm it passes**

Run: `./mvnw test -Dtest=PartnerRbacReconcilerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/partner/rbac/PartnerRbacReconciler.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/tenant/TenantProvisioningService.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/partner/rbac/PartnerRbacReconcilerTest.java
git commit -m "feat(engine): RBAC reconciler — provision-time admin grant + idempotent startup backfill

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Partner-user management API

**Files**
- Create: `partner/user/PartnerUserService.java`, `PartnerUserController.java`, `dto/CreatePartnerUserRequest.java`, `dto/UpdateUserRolesRequest.java`, `dto/PartnerUserResponse.java`
- Create test: `baas-engine/src/test/java/com/nubbank/baas/engine/partner/user/PartnerUserControllerTest.java`

- [ ] **Step 1: Write the failing test** (admin creates a MAKER; MAKER can't manage users; last-admin demotion blocked; cross-org 404)

```java
package com.nubbank.baas.engine.partner.user;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.partner.rbac.PartnerRbacReconciler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerUserControllerTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository userRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PartnerRbacReconciler reconciler;
    @Autowired PartnerJwtService jwt;

    record Ctx(PartnerOrganization org, String schema, PartnerUser admin, String adminJwt) {}

    private Ctx newOrgWithAdmin() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("U").status(PartnerStatus.PRODUCTION).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("u@t.com").build());
        provisioning.provision(org.getId(), schema);
        PartnerUser admin = userRepo.save(PartnerUser.builder().organization(org)
            .email("admin+" + schema + "@t.com").passwordHash("x").role("PARTNER_ADMIN").active(true).build());
        reconciler.reconcileOrg(org); // grants admin PARTNER_ADMIN
        String token = jwt.issue(admin.getId().toString(), admin.getEmail(), "PARTNER_ADMIN",
            org.getId().toString(), org.getName(), schema, "PRO", "PRODUCTION");
        return new Ctx(org, schema, admin, token);
    }
    private HttpHeaders auth(String t){ HttpHeaders h=new HttpHeaders(); h.setBearerAuth(t); h.setContentType(MediaType.APPLICATION_JSON); return h; }

    @Test
    void admin_createsMakerUser() {
        Ctx c = newOrgWithAdmin();
        // discover the PARTNER_MAKER role id via the roles endpoint
        ResponseEntity<Map> roles = restTemplate.exchange("/baas/v1/roles", HttpMethod.GET,
            new HttpEntity<>(auth(c.adminJwt())), Map.class);
        String makerRoleId = ((List<Map<String,Object>>) roles.getBody().get("data")).stream()
            .filter(r -> r.get("name").equals("PARTNER_MAKER")).findFirst().orElseThrow().get("id").toString();

        ResponseEntity<Map> created = restTemplate.exchange("/baas/v1/partner-users", HttpMethod.POST,
            new HttpEntity<>(Map.of("email","maker@u.com","password","secret12",
                "roleIds", List.of(makerRoleId)), auth(c.adminJwt())), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void createUser_withNoRoles_is400() {
        Ctx c = newOrgWithAdmin();
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/partner-users", HttpMethod.POST,
            new HttpEntity<>(Map.of("email","x@u.com","password","secret12","roleIds", List.of()),
                auth(c.adminJwt())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void crossOrg_userFetch_is404() {
        Ctx a = newOrgWithAdmin();
        Ctx b = newOrgWithAdmin();
        PartnerUser bUser = userRepo.save(PartnerUser.builder().organization(b.org())
            .email("other@b.com").passwordHash("x").role("PARTNER_ADMIN").active(true).build());
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/partner-users/" + bUser.getId(),
            HttpMethod.GET, new HttpEntity<>(auth(a.adminJwt())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (no controller).

Run: `./mvnw test -Dtest=PartnerUserControllerTest`
Expected: FAIL.

- [ ] **Step 3: DTOs**

`dto/CreatePartnerUserRequest.java`:
```java
package com.nubbank.baas.engine.partner.user.dto;
import jakarta.validation.constraints.*;
import java.util.*;
public record CreatePartnerUserRequest(
    @Email @NotBlank String email,
    @Size(min = 8) @NotBlank String password,
    @NotEmpty Set<UUID> roleIds) {}
```
`dto/UpdateUserRolesRequest.java`:
```java
package com.nubbank.baas.engine.partner.user.dto;
import jakarta.validation.constraints.NotEmpty;
import java.util.*;
public record UpdateUserRolesRequest(@NotEmpty Set<UUID> roleIds) {}
```
`dto/PartnerUserResponse.java`:
```java
package com.nubbank.baas.engine.partner.user.dto;
import java.util.*;
public record PartnerUserResponse(UUID id, String email, boolean active, List<String> roles) {}
```

- [ ] **Step 4: Service** (`partner/user/PartnerUserService.java`) — enforces org scope, non-empty roles, privilege ⊆ caller, last-admin guard, PARTNER-scope-only assignment.

```java
package com.nubbank.baas.engine.partner.user;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.partner.user.dto.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PartnerUserService {

    private final PartnerUserRepository userRepo;
    private final RoleRepository roleRepo;
    private final UserRoleRepository userRoleRepo;
    private final BCryptPasswordEncoder encoder;

    private UUID callerOrgId() {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null) throw BaasException.unauthorized("NO_CONTEXT", "No partner context");
        return UUID.fromString(ctx.partnerId());
    }

    private Set<String> callerAuthorities() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .map(a -> a.getAuthority()).collect(Collectors.toSet());
    }

    /** Roles must be PARTNER/SHARED-scoped and grant nothing the caller lacks (no escalation). */
    private List<Role> resolveAssignableRoles(Set<UUID> roleIds) {
        Set<String> mine = callerAuthorities();
        boolean superuser = roleRepo.findByRoleScopeIn(List.of(PartnerRoles.SCOPE_PARTNER, PartnerRoles.SCOPE_SHARED))
            .stream().anyMatch(r -> r.isSuperuser()
                && userRoleRepo.existsSuperuserRoleByUserId(callerUserId()));
        List<Role> roles = new ArrayList<>();
        for (UUID id : roleIds) {
            Role r = roleRepo.findById(id).filter(x ->
                    List.of(PartnerRoles.SCOPE_PARTNER, PartnerRoles.SCOPE_SHARED).contains(x.getRoleScope()))
                .orElseThrow(() -> BaasException.notFound("ROLE_NOT_FOUND", "Role " + id + " not found"));
            if (!superuser) {
                Set<String> roleCodes = r.getPermissions().stream().map(Permission::getCode).collect(Collectors.toSet());
                if (!mine.containsAll(roleCodes))
                    throw BaasException.forbidden("PRIVILEGE_ESCALATION",
                        "Cannot grant a role exceeding your own authority");
            }
            roles.add(r);
        }
        return roles;
    }

    private UUID callerUserId() {
        return UUID.fromString((String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Transactional
    public PartnerUser create(CreatePartnerUserRequest req) {
        if (userRepo.existsByEmail(req.email()))
            throw BaasException.conflict("EMAIL_TAKEN", "Email already exists");
        List<Role> roles = resolveAssignableRoles(req.roleIds()); // validates non-escalation
        PartnerOrganization org = orgRef();
        PartnerUser u = userRepo.save(PartnerUser.builder().organization(org)
            .email(req.email()).passwordHash(encoder.encode(req.password()))
            .role("PARTNER_USER").active(true).build());
        roles.forEach(r -> userRoleRepo.save(UserRole.builder().userId(u.getId()).role(r).build()));
        return u;
    }

    private PartnerOrganization orgRef() {
        return userRepo.findByOrganizationId(callerOrgId()).stream().findFirst()
            .map(PartnerUser::getOrganization)
            .orElseThrow(() -> BaasException.notFound("ORG_NOT_FOUND", "Org not found"));
    }

    @Transactional(readOnly = true)
    public List<PartnerUserResponse> list() {
        return userRepo.findByOrganizationId(callerOrgId()).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PartnerUserResponse get(UUID id) { return toResponse(requireOwnOrg(id)); }

    @Transactional
    public PartnerUserResponse replaceRoles(UUID id, UpdateUserRolesRequest req) {
        PartnerUser u = requireOwnOrg(id);
        List<Role> roles = resolveAssignableRoles(req.roleIds());
        boolean removingAdmin = userRoleRepo.findByUserId(id).stream()
            .anyMatch(ur -> ur.getRole().getName().equals(PartnerRoles.ADMIN))
            && roles.stream().noneMatch(r -> r.getName().equals(PartnerRoles.ADMIN));
        if (removingAdmin && userRoleRepo.countDistinctUsersWithRole(PartnerRoles.ADMIN) <= 1)
            throw BaasException.conflict("LAST_ADMIN", "Cannot remove the last PARTNER_ADMIN");
        userRoleRepo.deleteByUserId(id);
        roles.forEach(r -> userRoleRepo.save(UserRole.builder().userId(id).role(r).build()));
        return toResponse(requireOwnOrg(id));
    }

    @Transactional
    public void setActive(UUID id, boolean active) {
        PartnerUser u = requireOwnOrg(id);
        if (!active && userRoleRepo.findByUserId(id).stream()
                .anyMatch(ur -> ur.getRole().getName().equals(PartnerRoles.ADMIN))
            && userRoleRepo.countDistinctUsersWithRole(PartnerRoles.ADMIN) <= 1)
            throw BaasException.conflict("LAST_ADMIN", "Cannot deactivate the last PARTNER_ADMIN");
        u.setActive(active); userRepo.save(u);
    }

    private PartnerUser requireOwnOrg(UUID id) {
        PartnerUser u = userRepo.findById(id)
            .filter(x -> x.getOrganization().getId().equals(callerOrgId()))
            .orElseThrow(() -> BaasException.notFound("USER_NOT_FOUND", "User " + id + " not found"));
        return u;
    }

    private PartnerUserResponse toResponse(PartnerUser u) {
        List<String> roles = userRoleRepo.findRoleNamesByUserId(u.getId());
        return new PartnerUserResponse(u.getId(), u.getEmail(), u.isActive(), roles);
    }
}
```

- [ ] **Step 5: Controller** (`partner/user/PartnerUserController.java`)

```java
package com.nubbank.baas.engine.partner.user;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.partner.user.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/baas/v1/partner-users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_PARTNER_USERS')")
public class PartnerUserController {

    private final PartnerUserService service;

    @PostMapping
    public ResponseEntity<ApiResponse<PartnerUserResponse>> create(@Valid @RequestBody CreatePartnerUserRequest req) {
        var u = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.get(u.getId())));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PartnerUserResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.list()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PartnerUserResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
    }

    @PutMapping("/{id}/roles")
    public ResponseEntity<ApiResponse<PartnerUserResponse>> roles(@PathVariable UUID id,
            @Valid @RequestBody UpdateUserRolesRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.replaceRoles(id, req)));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable UUID id) {
        service.setActive(id, false); return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<ApiResponse<Void>> reactivate(@PathVariable UUID id) {
        service.setActive(id, true); return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
```

- [ ] **Step 6: Run the test to confirm it passes**

Run: `./mvnw test -Dtest=PartnerUserControllerTest`
Expected: PASS. (If `@PreAuthorize` on the class needs the principal to be a UUID string, confirm the partner JWT path sets `principal = ctx.userId()`, which it does.)

- [ ] **Step 7: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/partner/user/ \
        baas-engine/src/test/java/com/nubbank/baas/engine/partner/user/PartnerUserControllerTest.java
git commit -m "feat(engine): partner-user management API (create/list/roles/deactivate) with guardrails

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Scope Role CRUD to PARTNER/SHARED + protect built-in

**Files**
- Modify: `role/RoleService.java`, `role/RoleController.java`
- Create test: `baas-engine/src/test/java/com/nubbank/baas/engine/role/RoleScopingTest.java`

- [ ] **Step 1: Write the failing test** (list returns only PARTNER/SHARED roles; deleting a built-in → 409; created custom role is PARTNER-scoped)

```java
package com.nubbank.baas.engine.role;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.partner.rbac.PartnerRbacReconciler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RoleScopingTest extends AbstractIntegrationTest {
    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository userRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PartnerRbacReconciler reconciler;
    @Autowired PartnerJwtService jwt;
    @Autowired RoleRepository roleRepo;

    @Test
    void list_isPartnerScoped_and_builtInDelete_is409() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("RS").status(PartnerStatus.PRODUCTION).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("rs@t.com").build());
        provisioning.provision(org.getId(), schema);
        PartnerUser admin = userRepo.save(PartnerUser.builder().organization(org)
            .email("a@rs.com").passwordHash("x").role("PARTNER_ADMIN").active(true).build());
        reconciler.reconcileOrg(org);
        String token = jwt.issue(admin.getId().toString(), "a@rs.com", "PARTNER_ADMIN",
            org.getId().toString(), org.getName(), schema, "PRO", "PRODUCTION");
        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(token); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> list = restTemplate.exchange("/baas/v1/roles", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        List<Map<String,Object>> data = (List<Map<String,Object>>) list.getBody().get("data");
        assertThat(data).extracting(m -> m.get("name")).contains("PARTNER_MAKER").doesNotContain("TELLER");

        String adminRoleId = data.stream().filter(m -> m.get("name").equals("PARTNER_ADMIN"))
            .findFirst().orElseThrow().get("id").toString();
        ResponseEntity<Map> del = restTemplate.exchange("/baas/v1/roles/" + adminRoleId,
            HttpMethod.DELETE, new HttpEntity<>(h), Map.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // built-in protected
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`listRoles()` returns all roles incl operator; built-in delete succeeds).

Run: `./mvnw test -Dtest=RoleScopingTest`
Expected: FAIL.

- [ ] **Step 3: Update `RoleService`**

```java
    public List<Role> listRoles() {
        requireContext();
        return roleRepo.findByRoleScopeIn(List.of(PartnerRoles.SCOPE_PARTNER, PartnerRoles.SCOPE_SHARED));
    }

    public Role createRole(RoleRequest req) {
        requireContext();
        Role r = Role.builder().name(req.name()).description(req.description())
            .builtIn(false).roleScope(PartnerRoles.SCOPE_PARTNER).superuser(false).build();
        return roleRepo.save(r);
    }

    public void deleteRole(UUID id) {
        requireContext();
        Role r = roleRepo.findById(id).orElseThrow(() -> BaasException.notFound("ROLE_NOT_FOUND","Role not found"));
        if (r.isBuiltIn()) throw BaasException.conflict("BUILT_IN_ROLE", "Built-in roles cannot be deleted");
        roleRepo.deleteById(id);
    }

    public Role updateRole(UUID id, RoleRequest req) {
        requireContext();
        Role r = roleRepo.findById(id).orElseThrow(() -> BaasException.notFound("ROLE_NOT_FOUND","Role not found"));
        if (r.isBuiltIn()) throw BaasException.conflict("BUILT_IN_ROLE", "Built-in roles cannot be edited");
        r.setName(req.name()); r.setDescription(req.description());
        return roleRepo.save(r);
    }
```

Add `@PreAuthorize("hasAuthority('MANAGE_ROLES')")` at the `RoleController` class level (so create/update/delete/list/permissions are admin-gated). `BaasException` and `PartnerRoles` imports as needed.

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./mvnw test -Dtest=RoleScopingTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/role/RoleService.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/role/RoleController.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/role/RoleScopingTest.java
git commit -m "feat(engine): scope Role CRUD to PARTNER/SHARED; protect built-in; gate MANAGE_ROLES

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: API-key issuance with scopes

**Files**
- Create: `partner/key/PartnerApiKeyService.java`, `PartnerApiKeyController.java`, `dto/IssueApiKeyRequest.java`, `dto/IssuedApiKeyResponse.java`
- Create test: `baas-engine/src/test/java/com/nubbank/baas/engine/partner/key/PartnerApiKeyControllerTest.java`

- [ ] **Step 1: Write the failing test** (issue a read-only key → the key gets `403` on `CREATE_ACCOUNT`, `200` on read)

```java
package com.nubbank.baas.engine.partner.key;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.partner.rbac.PartnerRbacReconciler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerApiKeyControllerTest extends AbstractIntegrationTest {
    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository userRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PartnerRbacReconciler reconciler;
    @Autowired PartnerJwtService jwt;

    @Test
    void issuedReadOnlyKey_cannotCreateAccount() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("K").status(PartnerStatus.PRODUCTION).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("k@t.com").build());
        provisioning.provision(org.getId(), schema);
        PartnerUser admin = userRepo.save(PartnerUser.builder().organization(org)
            .email("a@k.com").passwordHash("x").role("PARTNER_ADMIN").active(true).build());
        reconciler.reconcileOrg(org);
        String adminJwt = jwt.issue(admin.getId().toString(), "a@k.com", "PARTNER_ADMIN",
            org.getId().toString(), org.getName(), schema, "PRO", "PRODUCTION");
        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(adminJwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> issued = restTemplate.exchange("/baas/v1/partner-api-keys", HttpMethod.POST,
            new HttpEntity<>(Map.of("name","read-only","scopes", List.of("READ_ACCOUNT")), h), Map.class);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String rawKey = (String) ((Map<?,?>) issued.getBody().get("data")).get("apiKey");
        assertThat(rawKey).isNotBlank();

        HttpHeaders k = new HttpHeaders(); k.set("Authorization", "ApiKey " + rawKey); k.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> create = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("customerId", UUID.randomUUID().toString()), k), Map.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // CREATE_ACCOUNT not in scopes
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (no key-issuance endpoint).

Run: `./mvnw test -Dtest=PartnerApiKeyControllerTest`
Expected: FAIL.

- [ ] **Step 3: DTOs**

```java
// dto/IssueApiKeyRequest.java
package com.nubbank.baas.engine.partner.key.dto;
import jakarta.validation.constraints.NotBlank;
import java.util.*;
public record IssueApiKeyRequest(@NotBlank String name, List<String> scopes) {}
```
```java
// dto/IssuedApiKeyResponse.java — apiKey is returned ONCE
package com.nubbank.baas.engine.partner.key.dto;
import java.util.UUID;
public record IssuedApiKeyResponse(UUID id, String keyPrefix, String apiKey) {}
```

- [ ] **Step 4: Service** (`partner/key/PartnerApiKeyService.java`) — generate a `cba_`-prefixed key, store SHA-256 hash + scopes JSON, return the raw key once.

```java
package com.nubbank.baas.engine.partner.key;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.partner.key.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PartnerApiKeyService {
    private final PartnerApiKeyRepository keyRepo;
    private final PartnerOrganizationRepository orgRepo;
    private final ObjectMapper objectMapper;

    @Transactional
    public IssuedApiKeyResponse issue(IssueApiKeyRequest req) {
        PartnerContext ctx = PartnerContext.get();
        PartnerOrganization org = orgRepo.findById(UUID.fromString(ctx.partnerId()))
            .orElseThrow(() -> BaasException.notFound("ORG_NOT_FOUND", "Org not found"));
        byte[] rand = new byte[32]; new SecureRandom().nextBytes(rand);
        String raw = "cba_" + Base64.getUrlEncoder().withoutPadding().encodeToString(rand);
        List<String> scopes = req.scopes() == null ? List.of() : req.scopes();
        String scopesJson;
        try { scopesJson = objectMapper.writeValueAsString(scopes); }
        catch (Exception e) { throw BaasException.badRequest("BAD_SCOPES", "Invalid scopes"); }
        PartnerApiKey k = keyRepo.save(PartnerApiKey.builder().organization(org)
            .keyHash(sha256Hex(raw)).keyPrefix(raw.substring(0, 12)).name(req.name())
            .scopes(scopesJson).tier(org.getTier()).environment(org.getEnvironment()).active(true).build());
        return new IssuedApiKeyResponse(k.getId(), k.getKeyPrefix(), raw);
    }

    private String sha256Hex(String in) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(in.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
```

- [ ] **Step 5: Controller** (`partner/key/PartnerApiKeyController.java`) — gated `MANAGE_PARTNER_USERS` (admin).

```java
package com.nubbank.baas.engine.partner.key;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.partner.key.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/baas/v1/partner-api-keys")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_PARTNER_USERS')")
public class PartnerApiKeyController {
    private final PartnerApiKeyService service;

    @PostMapping
    public ResponseEntity<ApiResponse<IssuedApiKeyResponse>> issue(@Valid @RequestBody IssueApiKeyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.issue(req)));
    }
}
```

- [ ] **Step 6: Run the test to confirm it passes**

Run: `./mvnw test -Dtest=PartnerApiKeyControllerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/partner/key/ \
        baas-engine/src/test/java/com/nubbank/baas/engine/partner/key/PartnerApiKeyControllerTest.java
git commit -m "feat(engine): scoped API-key issuance (cba_ key, SHA-256 hash, scopes JSON, shown once)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Full-suite green + docs

**Files**
- Modify: `docs/backoffice-operations.md` (new endpoints + RBAC codes), `docs/deferred-items.md` (mark DEF-1C-15 resolved), `docs/api-reference.html` (partner-users, roles, partner-api-keys).

- [ ] **Step 1: Run the entire test suite**

Run: from `baas-engine/`, `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw test`
Expected: BUILD SUCCESS, 0 failures. Fix any fixtures that assumed first-party blanket authority (give their API keys `["*"]` scopes or assign their JWT users a role).

- [ ] **Step 2: Update docs** — add `/baas/v1/partner-users`, `/baas/v1/partner-api-keys`, role-scoping, and the new `MANAGE_PARTNER_USERS`/`MANAGE_ROLES` codes to `docs/api-reference.html` + `docs/backoffice-operations.md`; flip DEF-1C-15 to ✅ in `docs/deferred-items.md`.

- [ ] **Step 3: Commit**

```bash
git add docs/
git commit -m "docs(rbac): partner-users + API-key + role-scoping endpoints; DEF-1C-15 resolved

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review Checklist (run before opening the PR)

- **Spec coverage:** §4 (markers + scopes) → Task 1–2; §6 resolution → Task 4–5; §7 provisioning/backfill → Task 6; §5 API → Task 7–9; §8 guardrails → Task 7–8; §9 testing → woven through.
- **Deny-by-default:** Task 5's `default -> return` (no fallback) + the unassigned-user `403` test prove it.
- **Dynamic admin:** Task 4's "BRAND_NEW_PERM flows to admin" test proves the marker is dynamic.
- **Type consistency:** `roleScope`/`isSuperuser` naming matches between `Role`, the queries, the migration column names (`role_scope`/`is_superuser`), and the seeds.
- **Sequencing for Spec B:** `PARTNER_APPROVER` ships read-only here; Spec B's migration adds `APPROVE_ACCOUNT` and grants it.

---

## Execution Handoff

Two options to execute this plan:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task with two-stage review (spec-compliance, then code-quality) between tasks.
2. **Inline** — execute tasks here with checkpoints.

Tasks 1–6 are sequential (each builds on the last); 7–9 are independent of each other once 1–6 land, so they can be parallelized across worktrees if desired.
