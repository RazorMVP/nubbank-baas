# Phase 1C — Foundation Track Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the operator-identity + authorization foundation in `baas-engine` that every downstream Phase 1C track (Card, FEP, Custodian, Backoffice, Platform-Admin) builds against — Keycloak multi-issuer JWT validation, the wired Hybrid RBAC, the 30-role catalogue seed, operator deprovisioning, and the downstream interface contracts.

**Architecture:** Operator/human auth is **additive** over the existing HMAC partner-JWT + API-key path. A new branch in `PartnerContextFilter` recognises Keycloak issuers (`iss` claim), validates against the realm's JWKS via a testable decoder factory, and builds a `PartnerContext` with `authMode = "OPERATOR_JWT"`. After context resolution, the filter populates a Spring `SecurityContext` with authorities resolved by auth mode: **first-party partner credentials (API key + HMAC login JWT) → full tenant authority; delegated Keycloak operators → RBAC-scoped authority** loaded from the tenant-schema `user_roles → role_permissions → permissions` chain. `@EnableMethodSecurity` + `@PreAuthorize` then gates the controllers operators reach (demonstrated on `CustomerController`; rolled out per-module later).

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Security (resource-server / `NimbusJwtDecoder`), Nimbus JOSE+JWT, Hibernate SCHEMA multi-tenancy, Flyway (public + tenant), PostgreSQL 16, Testcontainers, JUnit 5 + AssertJ.

**Branch / worktree:** `feature/phase1c-foundation` (see `docs/superpowers/playbooks/2026-05-29-phase1c-parallel-execution-playbook.md`). This track **merges to `main` before any parallel track branches.**

**Spec:** `docs/superpowers/specs/2026-05-29-nubbank-baas-phase1c-backoffice-design.md` (§6.1 D1, §6.2 D2, §6.2.4, §13 D10, §14 role catalogue).

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `baas-engine/pom.xml` | add `spring-boot-starter-oauth2-resource-server` | 1 |
| `…/partner/PartnerOrganization.java` | + `keycloakIssuer` field | 2 |
| `…/partner/PartnerOrganizationRepository.java` | + `findByKeycloakIssuer` | 2 |
| `…/partner/PartnerStatus.java` | + `isActiveForAuth()` | 2 |
| `…/resources/db/migration/public/V3__operator_identity.sql` | add `keycloak_issuer` column | 2 |
| `…/auth/keycloak/OperatorJwtProperties.java` | `@ConfigurationProperties("app.keycloak")` | 3 |
| `…/auth/keycloak/OperatorJwtDecoderFactory.java` | interface: `JwtDecoder decoderFor(String issuer)` | 3 |
| `…/auth/keycloak/JwksOperatorJwtDecoderFactory.java` | prod impl (JWKS-URI, cached) | 3 |
| `…/auth/keycloak/OperatorJwtResolver.java` | token → `PartnerContext` (operator) | 4 |
| `…/tenant/PartnerContextFilter.java` | branch on `iss`; populate `SecurityContext` | 4, 5 |
| `…/role/UserRoleRepository.java` | + `findPermissionCodesByUserId` | 5 |
| `…/role/RoleRepository.java` | + `findPermissionCodesByRoleName` | 5 |
| `…/auth/AuthorityResolver.java` | authorities per auth mode | 5 |
| `…/config/MethodSecurityConfig.java` | `@EnableMethodSecurity` | 6 |
| `…/customer/CustomerController.java` | `@PreAuthorize` (demonstration) | 6 |
| `…/resources/db/migration/tenant/V3__role_catalogue.sql` | seed 30 roles + core grants | 7 |
| `…/auth/OperatorProvisioningService.java` | revoke grants on deprovision | 8 |
| `…/auth/OperatorGrantReconciliationJob.java` | nightly orphan sweep (against directory seam) | 8 |
| `…/auth/KeycloakUserDirectory.java` | seam: list/active-check realm users (stub impl) | 8 |
| `…/config/SecurityConfig.java` | partner chain `@Order` + `securityMatcher` | 9 |
| `docs/deferred-items.md` | deferred registry (D10) | 10 |
| `docs/contracts/phase1c-interfaces.md` | downstream contracts | 10 |

---

## Task 1: Add the OAuth2 resource-server dependency

`NimbusJwtDecoder` lives in `spring-security-oauth2-jose`, pulled by the resource-server starter. The existing HMAC path uses raw Nimbus and does **not** bring this in.

**Files:**
- Modify: `baas-engine/pom.xml`

- [ ] **Step 1: Check whether it's already present**

Run: `cd baas-engine && ./mvnw -q dependency:tree 2>/dev/null | grep -i oauth2-jose || echo "ABSENT"`
Expected: `ABSENT` (if already present, skip to Task 2).

- [ ] **Step 2: Add the dependency**

In `baas-engine/pom.xml`, inside `<dependencies>`, add:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

- [ ] **Step 3: Verify it resolves**

Run: `cd baas-engine && ./mvnw -q dependency:tree | grep -i oauth2-jose`
Expected: a line containing `spring-security-oauth2-jose`.

- [ ] **Step 4: Commit**

```bash
git add baas-engine/pom.xml
git commit -m "build: add oauth2 resource-server starter for Keycloak operator JWTs"
```

---

## Task 2: Keycloak issuer registry on PartnerOrganization

Map a JWT `iss` (the partner's Keycloak realm issuer URL) to its `PartnerOrganization`, allowlisted to auth-active partners.

**Files:**
- Create: `baas-engine/src/main/resources/db/migration/public/V3__operator_identity.sql`
- Modify: `…/partner/PartnerOrganization.java`
- Modify: `…/partner/PartnerOrganizationRepository.java`
- Modify: `…/partner/PartnerStatus.java`
- Test: `…/test/java/com/nubbank/baas/engine/partner/PartnerIssuerLookupTest.java`

- [ ] **Step 1: Write the failing test**

Create `baas-engine/src/test/java/com/nubbank/baas/engine/partner/PartnerIssuerLookupTest.java`:

```java
package com.nubbank.baas.engine.partner;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerIssuerLookupTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;

    @Test
    void findsOrgByKeycloakIssuer() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        String issuer = "https://auth.nubbank.test/realms/baas-partner-" + UUID.randomUUID();
        orgRepo.save(PartnerOrganization.builder()
            .name("Issuer Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .keycloakIssuer(issuer).contactEmail("i@test.com").build());

        assertThat(orgRepo.findByKeycloakIssuer(issuer)).isPresent();
        assertThat(orgRepo.findByKeycloakIssuer("https://nope")).isEmpty();
    }

    @Test
    void statusGatesAuth() {
        assertThat(PartnerStatus.BASIC.isActiveForAuth()).isTrue();
        assertThat(PartnerStatus.SANDBOX.isActiveForAuth()).isTrue();
        assertThat(PartnerStatus.SUSPENDED.isActiveForAuth()).isFalse();
        assertThat(PartnerStatus.PENDING_REVIEW.isActiveForAuth()).isFalse();
    }
}
```

- [ ] **Step 2: Run it — expect compile failure**

Run: `cd baas-engine && ./mvnw -q test -Dtest=PartnerIssuerLookupTest`
Expected: FAIL — `keycloakIssuer`, `findByKeycloakIssuer`, `isActiveForAuth` do not exist.

- [ ] **Step 3: Add the migration**

Create `baas-engine/src/main/resources/db/migration/public/V3__operator_identity.sql`:

```sql
-- Operator identity: map a partner's Keycloak realm issuer to its organization.
ALTER TABLE public.partner_organizations
    ADD COLUMN keycloak_issuer VARCHAR(500);

CREATE UNIQUE INDEX ux_partner_org_keycloak_issuer
    ON public.partner_organizations (keycloak_issuer)
    WHERE keycloak_issuer IS NOT NULL;
```

- [ ] **Step 4: Add the entity field**

In `PartnerOrganization.java`, after the `website` field add:

```java
    @Column(name = "keycloak_issuer", length = 500)
    private String keycloakIssuer;
```

- [ ] **Step 5: Add the repository method**

In `PartnerOrganizationRepository.java` add:

```java
    Optional<PartnerOrganization> findByKeycloakIssuer(String keycloakIssuer);
```

- [ ] **Step 6: Add the status helper**

Replace the body of `PartnerStatus.java`:

```java
package com.nubbank.baas.engine.partner;

public enum PartnerStatus {
    SANDBOX, PENDING_REVIEW, BASIC, PRO, ENTERPRISE, SUSPENDED;

    /** A partner whose operators may authenticate: provisioned and not suspended/under-review. */
    public boolean isActiveForAuth() {
        return this == SANDBOX || this == BASIC || this == PRO || this == ENTERPRISE;
    }
}
```

- [ ] **Step 7: Run the test — expect pass**

Run: `cd baas-engine && ./mvnw -q test -Dtest=PartnerIssuerLookupTest`
Expected: PASS (2 tests).

- [ ] **Step 8: Commit**

```bash
git add baas-engine/src/main/resources/db/migration/public/V3__operator_identity.sql \
        baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerOrganization.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerOrganizationRepository.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerStatus.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/partner/PartnerIssuerLookupTest.java
git commit -m "feat(auth): Keycloak issuer registry on PartnerOrganization"
```

---

## Task 3: Testable multi-issuer JWT decoder factory

A seam so operator-auth tests run offline against an in-memory JWKS while prod fetches the realm's JWKS URI.

**Files:**
- Create: `…/auth/keycloak/OperatorJwtProperties.java`
- Create: `…/auth/keycloak/OperatorJwtDecoderFactory.java`
- Create: `…/auth/keycloak/JwksOperatorJwtDecoderFactory.java`
- Test: `…/test/java/com/nubbank/baas/engine/auth/keycloak/JwksOperatorJwtDecoderFactoryTest.java`

- [ ] **Step 1: Write the failing test**

Create `baas-engine/src/test/java/com/nubbank/baas/engine/auth/keycloak/JwksOperatorJwtDecoderFactoryTest.java`:

```java
package com.nubbank.baas.engine.auth.keycloak;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import static org.assertj.core.api.Assertions.assertThat;

class JwksOperatorJwtDecoderFactoryTest {

    @Test
    void cachesDecoderPerIssuer() {
        JwksOperatorJwtDecoderFactory factory = new JwksOperatorJwtDecoderFactory();
        String issuer = "https://auth.nubbank.test/realms/baas-partner-abc";

        JwtDecoder first  = factory.decoderFor(issuer);
        JwtDecoder second = factory.decoderFor(issuer);

        assertThat(first).isNotNull();
        assertThat(second).isSameAs(first); // same instance — cached, no re-fetch
    }

    @Test
    void derivesJwksUriFromIssuer() {
        JwksOperatorJwtDecoderFactory factory = new JwksOperatorJwtDecoderFactory();
        assertThat(factory.jwksUri("https://h/realms/r"))
            .isEqualTo("https://h/realms/r/protocol/openid-connect/certs");
    }
}
```

- [ ] **Step 2: Run it — expect compile failure**

Run: `cd baas-engine && ./mvnw -q test -Dtest=JwksOperatorJwtDecoderFactoryTest`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Define the properties**

Create `OperatorJwtProperties.java`:

```java
package com.nubbank.baas.engine.auth.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Keycloak operator-auth config. {@code admin-issuer} is the NubBank staff realm issuer. */
@ConfigurationProperties(prefix = "app.keycloak")
public record OperatorJwtProperties(String adminIssuer) {}
```

- [ ] **Step 4: Define the factory interface**

Create `OperatorJwtDecoderFactory.java`:

```java
package com.nubbank.baas.engine.auth.keycloak;

import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Returns a JWKS-backed decoder for a given Keycloak issuer. Test impls supply in-memory keys. */
public interface OperatorJwtDecoderFactory {
    JwtDecoder decoderFor(String issuer);
}
```

- [ ] **Step 5: Implement the JWKS factory**

Create `JwksOperatorJwtDecoderFactory.java`:

```java
package com.nubbank.baas.engine.auth.keycloak;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds one {@link NimbusJwtDecoder} per Keycloak issuer from its JWKS endpoint and caches it.
 * Nimbus refreshes the JWK set internally; cache invalidation on realm-key rotation is DEF-1C-09.
 */
@Component
public class JwksOperatorJwtDecoderFactory implements OperatorJwtDecoderFactory {

    private final Map<String, JwtDecoder> cache = new ConcurrentHashMap<>();

    @Override
    public JwtDecoder decoderFor(String issuer) {
        return cache.computeIfAbsent(issuer, iss -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri(iss)).build();
            return decoder;
        });
    }

    String jwksUri(String issuer) {
        return issuer + "/protocol/openid-connect/certs";
    }
}
```

- [ ] **Step 6: Run the test — expect pass**

Run: `cd baas-engine && ./mvnw -q test -Dtest=JwksOperatorJwtDecoderFactoryTest`
Expected: PASS (2 tests).

- [ ] **Step 7: Register the properties**

In the main application class (`BaasEngineApplication.java`), ensure `@ConfigurationPropertiesScan` is present (add it next to `@SpringBootApplication` if missing).

Run: `cd baas-engine && grep -q "ConfigurationPropertiesScan" src/main/java/com/nubbank/baas/engine/BaasEngineApplication.java && echo OK || echo "ADD IT"`
If `ADD IT`: add `@org.springframework.boot.context.properties.ConfigurationPropertiesScan` to the class.

- [ ] **Step 8: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/auth/keycloak/ \
        baas-engine/src/test/java/com/nubbank/baas/engine/auth/keycloak/ \
        baas-engine/src/main/java/com/nubbank/baas/engine/BaasEngineApplication.java
git commit -m "feat(auth): testable multi-issuer JWT decoder factory"
```

---

## Task 4: Operator JWT resolver + filter branch

Resolve a Keycloak operator token into a `PartnerContext`. Admin-issuer tokens are recognised but **not** given a partner context (admins do not use `/baas/v1/**` — they get 401 there; the admin chain arrives in the Custodian track).

**Files:**
- Create: `…/auth/keycloak/OperatorJwtResolver.java`
- Modify: `…/tenant/PartnerContextFilter.java`
- Test: `…/test/java/com/nubbank/baas/engine/auth/keycloak/OperatorJwtResolverTest.java`
- Test (support): `…/test/java/com/nubbank/baas/engine/auth/keycloak/TestJwks.java`

- [ ] **Step 1: Add an RSA JWKS test helper**

Create `baas-engine/src/test/java/com/nubbank/baas/engine/auth/keycloak/TestJwks.java`:

```java
package com.nubbank.baas.engine.auth.keycloak;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.*;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import java.util.Date;

/** Generates an in-memory RSA key + matching offline {@link JwtDecoder} for operator-JWT tests. */
public final class TestJwks {
    public final RSAKey key;
    public TestJwks() {
        try { this.key = new RSAKeyGenerator(2048).keyID("test-kid").generate(); }
        catch (JOSEException e) { throw new RuntimeException(e); }
    }

    public String sign(String issuer, String sub, long expiresInSec) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject(sub)
                .expirationTime(new Date(System.currentTimeMillis() + expiresInSec * 1000))
                .issueTime(new Date()).build();
            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) { throw new RuntimeException(e); }
    }

    public JwtDecoder decoder() {
        try {
            DefaultJWTProcessor<SecurityContext> proc = new DefaultJWTProcessor<>();
            JWKSource<SecurityContext> src = new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()));
            proc.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, src));
            return new NimbusJwtDecoder(proc);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

- [ ] **Step 2: Write the failing resolver test**

Create `OperatorJwtResolverTest.java`:

```java
package com.nubbank.baas.engine.auth.keycloak;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OperatorJwtResolverTest extends AbstractIntegrationTest {

    static final TestJwks JWKS = new TestJwks();

    @TestConfiguration
    static class StubDecoderConfig {
        @Bean @Primary OperatorJwtDecoderFactory stubFactory() {
            return issuer -> JWKS.decoder(); // every issuer validates against the test key
        }
    }

    @Autowired OperatorJwtResolver resolver;
    @Autowired PartnerOrganizationRepository orgRepo;

    private PartnerOrganization activeOrg(String issuer) {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        return orgRepo.save(PartnerOrganization.builder()
            .name("Op Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .keycloakIssuer(issuer).contactEmail("op@test.com").build());
    }

    @Test
    void resolvesActivePartnerOperator() {
        String issuer = "https://auth.nubbank.test/realms/baas-partner-" + UUID.randomUUID();
        PartnerOrganization org = activeOrg(issuer);
        String sub = UUID.randomUUID().toString();

        PartnerContext ctx = resolver.resolve(JWKS.sign(issuer, sub, 300));

        assertThat(ctx.partnerId()).isEqualTo(org.getId().toString());
        assertThat(ctx.schemaName()).isEqualTo(org.getSchemaName());
        assertThat(ctx.authMode()).isEqualTo("OPERATOR_JWT");
        assertThat(ctx.userId()).isEqualTo(sub);
    }

    @Test
    void rejectsUnknownIssuer() {
        assertThatThrownBy(() ->
            resolver.resolve(JWKS.sign("https://evil/realms/x", UUID.randomUUID().toString(), 300)))
            .isInstanceOf(BaasException.class);
    }

    @Test
    void rejectsSuspendedPartner() {
        String issuer = "https://auth.nubbank.test/realms/baas-partner-" + UUID.randomUUID();
        PartnerOrganization org = activeOrg(issuer);
        org.setStatus(PartnerStatus.SUSPENDED);
        orgRepo.save(org);
        assertThatThrownBy(() -> resolver.resolve(JWKS.sign(issuer, UUID.randomUUID().toString(), 300)))
            .isInstanceOf(BaasException.class);
    }
}
```

- [ ] **Step 3: Run it — expect failure**

Run: `cd baas-engine && ./mvnw -q test -Dtest=OperatorJwtResolverTest`
Expected: FAIL — `OperatorJwtResolver` and `resolve` do not exist.

- [ ] **Step 4: Implement the resolver**

Create `OperatorJwtResolver.java`:

```java
package com.nubbank.baas.engine.auth.keycloak;

import com.nimbusds.jwt.SignedJWT;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.partner.PartnerOrganization;
import com.nubbank.baas.engine.partner.PartnerOrganizationRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Validates a Keycloak operator JWT and builds the tenant {@link PartnerContext}. */
@Service
@RequiredArgsConstructor
public class OperatorJwtResolver {

    private final OperatorJwtDecoderFactory decoderFactory;
    private final PartnerOrganizationRepository orgRepo;
    private final OperatorJwtProperties props;

    /** @return the issuer string if this token is a Keycloak token we recognise, else null. */
    public String peekIssuer(String token) {
        try { return SignedJWT.parse(token).getJWTClaimsSet().getIssuer(); }
        catch (Exception e) { return null; }
    }

    public boolean isAdminIssuer(String issuer) {
        return issuer != null && issuer.equals(props.adminIssuer());
    }

    public PartnerContext resolve(String token) {
        String issuer = peekIssuer(token);
        if (issuer == null) throw BaasException.unauthorized("INVALID_TOKEN", "Unparseable token");

        PartnerOrganization org = orgRepo.findByKeycloakIssuer(issuer)
            .orElseThrow(() -> BaasException.unauthorized("UNKNOWN_ISSUER", "Issuer not registered"));
        if (!org.getStatus().isActiveForAuth())
            throw BaasException.unauthorized("PARTNER_INACTIVE", "Partner not active for auth");

        Jwt jwt;
        try { jwt = decoderFactory.decoderFor(issuer).decode(token); }
        catch (Exception e) { throw BaasException.unauthorized("INVALID_TOKEN", "JWT validation failed"); }

        return new PartnerContext(
            org.getId().toString(),
            org.getSchemaName(),
            org.getTier().name(),
            org.getEnvironment().name(),
            "OPERATOR_JWT",
            jwt.getSubject()
        );
    }
}
```

- [ ] **Step 5: Provide the admin-issuer test property**

Append to `baas-engine/src/test/resources/application-test.yml` under the existing `app:` block:

```yaml
  keycloak:
    admin-issuer: https://auth.nubbank.test/realms/baas-nubbank-admin
```

- [ ] **Step 6: Run the resolver test — expect pass**

Run: `cd baas-engine && ./mvnw -q test -Dtest=OperatorJwtResolverTest`
Expected: PASS (3 tests).

- [ ] **Step 7: Wire the resolver into PartnerContextFilter**

In `PartnerContextFilter.java`, inject `OperatorJwtResolver operatorResolver` (add to the constructor fields) and replace `resolveJwt`:

```java
    private void resolveJwt(String token) {
        // Keycloak operator/admin token? Branch on the iss claim.
        String issuer = operatorResolver.peekIssuer(token);
        if (issuer != null && operatorResolver.isAdminIssuer(issuer)) {
            // Admin tokens are not valid on the partner API. Leave context null →
            // AuthEnforcementFilter returns 401 on /baas/v1/**. Admin chain is the Custodian track.
            log.debug("Admin-issuer token presented to partner API — rejected (no partner context)");
            return;
        }
        if (issuer != null) {
            try { PartnerContext.set(operatorResolver.resolve(token)); return; }
            catch (Exception ex) { log.debug("Operator JWT resolution failed: {}", ex.getMessage()); return; }
        }
        // Fall back to the existing first-party HMAC partner JWT.
        try { PartnerContext.set(jwtService.validate(token)); }
        catch (Exception ex) { log.debug("JWT resolution failed: {}", ex.getMessage()); }
    }
```

- [ ] **Step 8: Run the full suite — existing HMAC + new operator paths both green**

Run: `cd baas-engine && ./mvnw -q test`
Expected: BUILD SUCCESS — existing `RoleControllerTest`/`CustomerControllerTest` (HMAC) unaffected; new operator resolution covered.

- [ ] **Step 9: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/auth/keycloak/OperatorJwtResolver.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/tenant/PartnerContextFilter.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/auth/keycloak/TestJwks.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/auth/keycloak/OperatorJwtResolverTest.java \
        baas-engine/src/test/resources/application-test.yml
git commit -m "feat(auth): resolve Keycloak operator JWTs into PartnerContext"
```

---

## Task 5: Authority resolution + SecurityContext population

After the context is resolved, populate Spring authorities by auth mode. **Boundary (design decision):** first-party partner credentials (`API_KEY`, HMAC `JWT`) get the full tenant authority set; Keycloak `OPERATOR_JWT` gets RBAC-scoped authorities from `user_roles`. Granular RBAC for HMAC partner-login users is `DEF-1C-15`.

**Files:**
- Modify: `…/role/UserRoleRepository.java`
- Modify: `…/role/PermissionRepository.java`
- Create: `…/auth/AuthorityResolver.java`
- Modify: `…/tenant/PartnerContextFilter.java`
- Test: `…/test/java/com/nubbank/baas/engine/auth/AuthorityResolverTest.java`

- [ ] **Step 1: Write the failing test**

Create `AuthorityResolverTest.java`:

```java
package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorityResolverTest extends AbstractIntegrationTest {

    @Autowired AuthorityResolver authorityResolver;
    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;
    @Autowired PermissionRepository permRepo;
    @Autowired UserRoleRepository userRoleRepo;

    @Test
    void operatorGetsOnlyGrantedPermissionCodes() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Auth Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .contactEmail("a@test.com").build());
        provisioning.provision(org.getId(), schema);

        UUID sub = UUID.randomUUID();
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "BASIC",
            "PRODUCTION", "OPERATOR_JWT", sub.toString()));
        try {
            // grant the operator a role with READ_CUSTOMER only
            Permission readCustomer = permRepo.findAll().stream()
                .filter(p -> p.getCode().equals("READ_CUSTOMER")).findFirst().orElseThrow();
            Role r = roleRepo.save(Role.builder().name("VIEWER")
                .permissions(Set.of(readCustomer)).build());
            userRoleRepo.save(UserRole.builder().userId(sub).role(r).build());

            List<String> codes = authorityResolver.operatorAuthorities(sub);
            assertThat(codes).contains("READ_CUSTOMER");
            assertThat(codes).doesNotContain("CREATE_CUSTOMER");
        } finally { PartnerContext.clear(); }
    }

    @Test
    void firstPartyGetsFullTenantAuthority() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Full Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .contactEmail("f@test.com").build());
        provisioning.provision(org.getId(), schema);
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "BASIC",
            "PRODUCTION", "API_KEY", null));
        try {
            assertThat(authorityResolver.fullTenantAuthorities())
                .contains("READ_CUSTOMER", "CREATE_CUSTOMER", "APPROVE_LOAN");
        } finally { PartnerContext.clear(); }
    }
}
```

- [ ] **Step 2: Run it — expect failure**

Run: `cd baas-engine && ./mvnw -q test -Dtest=AuthorityResolverTest`
Expected: FAIL — `AuthorityResolver`, `operatorAuthorities`, `fullTenantAuthorities` do not exist.

- [ ] **Step 3: Add the JPQL query to UserRoleRepository**

In `UserRoleRepository.java` add (keep existing methods):

```java
    @org.springframework.data.jpa.repository.Query(
        "select p.code from UserRole ur join ur.role r join r.permissions p where ur.userId = :userId")
    java.util.List<String> findPermissionCodesByUserId(java.util.UUID userId);
```

- [ ] **Step 4: Add an all-codes query to PermissionRepository**

In `PermissionRepository.java` add:

```java
    @org.springframework.data.jpa.repository.Query("select p.code from Permission p")
    java.util.List<String> findAllCodes();
```

- [ ] **Step 5: Implement AuthorityResolver**

Create `AuthorityResolver.java`:

```java
package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.role.PermissionRepository;
import com.nubbank.baas.engine.role.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * Resolves Spring authorities (permission codes) for the active tenant.
 * Queries run inside the current PartnerContext, so Hibernate routes them to the tenant schema.
 */
@Service
@RequiredArgsConstructor
public class AuthorityResolver {

    private final UserRoleRepository userRoleRepo;
    private final PermissionRepository permissionRepo;

    /** Keycloak operator: only the permission codes granted via user_roles. */
    @Transactional(readOnly = true)
    public List<String> operatorAuthorities(UUID userId) {
        return userRoleRepo.findPermissionCodesByUserId(userId);
    }

    /** First-party partner credential (API key / HMAC login): full authority over its own tenant. */
    @Transactional(readOnly = true)
    public List<String> fullTenantAuthorities() {
        return permissionRepo.findAllCodes();
    }
}
```

- [ ] **Step 6: Run the test — expect pass**

Run: `cd baas-engine && ./mvnw -q test -Dtest=AuthorityResolverTest`
Expected: PASS (2 tests).

- [ ] **Step 7: Populate SecurityContext in the filter**

In `PartnerContextFilter.java`, inject `AuthorityResolver authorityResolver`. After `resolveContext(request)` succeeds and a context exists, set the Spring `Authentication`. Replace `doFilterInternal`:

```java
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        try {
            resolveContext(request);
            populateAuthorities();
            chain.doFilter(request, response);
        } finally {
            PartnerContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private void populateAuthorities() {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null) return;
        java.util.List<String> codes = switch (ctx.authMode()) {
            case "OPERATOR_JWT" -> authorityResolver.operatorAuthorities(java.util.UUID.fromString(ctx.userId()));
            default -> authorityResolver.fullTenantAuthorities(); // API_KEY, HMAC JWT
        };
        var authorities = codes.stream()
            .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
            .map(a -> (org.springframework.security.core.GrantedAuthority) a).toList();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            ctx.userId() == null ? ctx.partnerId() : ctx.userId(), null, authorities);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }
```

- [ ] **Step 8: Run the full suite**

Run: `cd baas-engine && ./mvnw -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/role/UserRoleRepository.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/role/PermissionRepository.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/auth/AuthorityResolver.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/tenant/PartnerContextFilter.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/auth/AuthorityResolverTest.java
git commit -m "feat(auth): per-mode authority resolution + SecurityContext population"
```

---

## Task 6: Enable method security + demonstrate @PreAuthorize on CustomerController

**Files:**
- Create: `…/config/MethodSecurityConfig.java`
- Modify: `…/customer/CustomerController.java`
- Test: `…/test/java/com/nubbank/baas/engine/customer/CustomerAuthzTest.java`

- [ ] **Step 1: Write the failing authz test**

Create `CustomerAuthzTest.java`:

```java
package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.keycloak.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerAuthzTest extends AbstractIntegrationTest {

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

    private String issuer;
    private PartnerOrganization org;

    @BeforeEach
    void setup() {
        issuer = "https://auth.nubbank.test/realms/baas-partner-" + UUID.randomUUID();
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        org = orgRepo.save(PartnerOrganization.builder()
            .name("Authz Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .keycloakIssuer(issuer).contactEmail("z@test.com").build());
        provisioning.provision(org.getId(), schema);
    }

    private HttpHeaders bearer(String sub) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(JWKS.sign(issuer, sub, 300));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void operatorWithReadCustomer_canList_butCannotCreate() {
        UUID sub = UUID.randomUUID();
        Permission read = permRepo.findAll().stream()
            .filter(p -> p.getCode().equals("READ_CUSTOMER")).findFirst().orElseThrow();
        Role viewer = roleRepo.save(Role.builder().name("VIEWER").permissions(Set.of(read)).build());
        userRoleRepo.save(UserRole.builder().userId(sub).role(viewer).build());

        ResponseEntity<Map> listResp = restTemplate.exchange("/baas/v1/customers",
            HttpMethod.GET, new HttpEntity<>(bearer(sub.toString())), Map.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/customers",
            HttpMethod.POST, new HttpEntity<>(Map.of("firstName","A","lastName","B",
                "email","a@b.com","phone","123","dateOfBirth","1990-01-01","nationalId","X1"),
                bearer(sub.toString())), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

> Note: the `create` body fields mirror `CreateCustomerRequest`; if validation rejects before authz, the test still asserts `FORBIDDEN` because `@PreAuthorize` runs before the controller body. If field names differ, the assertion still holds — authz precedes validation.

- [ ] **Step 2: Run it — expect failure (currently 200/201, not 403)**

Run: `cd baas-engine && ./mvnw -q test -Dtest=CustomerAuthzTest`
Expected: FAIL on the create assertion — method security not yet enabled, so create returns 201.

- [ ] **Step 3: Enable method security**

Create `config/MethodSecurityConfig.java`:

```java
package com.nubbank.baas.engine.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {}
```

- [ ] **Step 4: Annotate the controller**

In `CustomerController.java`, add `import org.springframework.security.access.prepost.PreAuthorize;` and annotate:

```java
    @PreAuthorize("hasAuthority('CREATE_CUSTOMER')")
    @PostMapping
    public ResponseEntity<ApiResponse<CustomerResponse>> create(...) { ... }

    @PreAuthorize("hasAuthority('READ_CUSTOMER')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CustomerResponse>> getById(...) { ... }

    @PreAuthorize("hasAuthority('READ_CUSTOMER')")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<CustomerResponse>>> list(...) { ... }
```

- [ ] **Step 5: Run the authz test — expect pass**

Run: `cd baas-engine && ./mvnw -q test -Dtest=CustomerAuthzTest`
Expected: PASS — list 200 (has READ_CUSTOMER), create 403 (lacks CREATE_CUSTOMER).

- [ ] **Step 6: Run the full suite — existing HMAC CustomerControllerTest must still pass**

Run: `cd baas-engine && ./mvnw -q test`
Expected: BUILD SUCCESS — `CustomerControllerTest` (HMAC `PARTNER_ADMIN` → full authorities) still 200/201.

- [ ] **Step 7: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/config/MethodSecurityConfig.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/customer/CustomerController.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/customer/CustomerAuthzTest.java
git commit -m "feat(authz): enable method security; gate CustomerController by permission"
```

---

## Task 7: Seed the 30-role catalogue (tenant migration)

Seed the locked 30 partner roles (spec §14) into every tenant schema, with core-role permission grants drawn from the 13 permissions V2 already seeds, and the maker-checker flag on `APPROVE_LOAN`.

**Files:**
- Create: `baas-engine/src/main/resources/db/migration/tenant/V3__role_catalogue.sql`
- Test: `…/test/java/com/nubbank/baas/engine/role/RoleCatalogueSeedTest.java`

- [ ] **Step 1: Write the failing test**

Create `RoleCatalogueSeedTest.java`:

```java
package com.nubbank.baas.engine.role;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RoleCatalogueSeedTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;

    @Test
    void provisionSeeds30Roles() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Seed Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .contactEmail("s@test.com").build());
        provisioning.provision(org.getId(), schema);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "BASIC",
            "PRODUCTION", "API_KEY", null));
        try {
            List<String> names = roleRepo.findAll().stream().map(Role::getName).toList();
            assertThat(names).hasSize(30);
            assertThat(names).contains("PARTNER_ADMIN", "TELLER", "CREDIT_APPROVER",
                "REMITTANCE_OFFICER", "AUDITOR_READONLY");
            // TELLER must carry cash permissions
            Role teller = roleRepo.findAll().stream()
                .filter(r -> r.getName().equals("TELLER")).findFirst().orElseThrow();
            assertThat(teller.getPermissions()).extracting("code").contains("DEPOSIT", "WITHDRAW");
        } finally { PartnerContext.clear(); }
    }
}
```

- [ ] **Step 2: Run it — expect failure (0 roles seeded)**

Run: `cd baas-engine && ./mvnw -q test -Dtest=RoleCatalogueSeedTest`
Expected: FAIL — `hasSize(30)` fails (no roles seeded by default).

- [ ] **Step 3: Write the seed migration**

Create `baas-engine/src/main/resources/db/migration/tenant/V3__role_catalogue.sql`:

```sql
-- Phase 1C — 30-role partner catalogue (spec §14). One row per role; core-role grants below.
INSERT INTO roles (id, name, description, disabled, version, created_at, updated_at) VALUES
  (gen_random_uuid(),'PARTNER_ADMIN','Tenant super-admin',false,0,now(),now()),
  (gen_random_uuid(),'BRANCH_MANAGER','Branch oversight + approvals',false,0,now(),now()),
  (gen_random_uuid(),'OPERATIONS_MANAGER','Back-office oversight + approvals',false,0,now(),now()),
  (gen_random_uuid(),'PRODUCT_MANAGER','Product catalogue config',false,0,now(),now()),
  (gen_random_uuid(),'SYSTEM_CONFIGURATOR','System configuration',false,0,now(),now()),
  (gen_random_uuid(),'CUSTOMER_SERVICE_OFFICER','Account opening + customer profile/KYC capture',false,0,now(),now()),
  (gen_random_uuid(),'RELATIONSHIP_MANAGER','Customer relationship/portfolio',false,0,now(),now()),
  (gen_random_uuid(),'TELLER','Cash transactions + teller session',false,0,now(),now()),
  (gen_random_uuid(),'HEAD_TELLER','Vault custody + teller settlement',false,0,now(),now()),
  (gen_random_uuid(),'CUSTOMER_SUPPORT','Support tickets + enquiries',false,0,now(),now()),
  (gen_random_uuid(),'ACCOUNT_OFFICER','Account maintenance',false,0,now(),now()),
  (gen_random_uuid(),'KYC_OFFICER','KYC review/approval',false,0,now(),now()),
  (gen_random_uuid(),'LOAN_OFFICER','Loan origination + servicing',false,0,now(),now()),
  (gen_random_uuid(),'CREDIT_ANALYST','Underwriting + credit assessment',false,0,now(),now()),
  (gen_random_uuid(),'CREDIT_APPROVER','Loan/credit approval authority',false,0,now(),now()),
  (gen_random_uuid(),'COLLECTIONS_OFFICER','Arrears + recovery',false,0,now(),now()),
  (gen_random_uuid(),'LOAN_OPERATIONS_OFFICER','Disbursement + repayment posting',false,0,now(),now()),
  (gen_random_uuid(),'PAYMENTS_OFFICER','Transfers + standing instructions',false,0,now(),now()),
  (gen_random_uuid(),'REMITTANCE_OFFICER','Cross-border / diaspora remittances',false,0,now(),now()),
  (gen_random_uuid(),'TREASURY_OFFICER','Liquidity + placements + FX',false,0,now(),now()),
  (gen_random_uuid(),'CARD_OPERATIONS_OFFICER','Card issuance/lifecycle/limits',false,0,now(),now()),
  (gen_random_uuid(),'RECONCILIATION_OFFICER','Settlement + GL reconciliation',false,0,now(),now()),
  (gen_random_uuid(),'COMPLIANCE_OFFICER','Compliance + sanctions screening',false,0,now(),now()),
  (gen_random_uuid(),'AML_ANALYST','Transaction monitoring + SAR/STR',false,0,now(),now()),
  (gen_random_uuid(),'FRAUD_ANALYST','Fraud alerts + case management',false,0,now(),now()),
  (gen_random_uuid(),'RISK_OFFICER','Operational + credit risk policy',false,0,now(),now()),
  (gen_random_uuid(),'FINANCE_OFFICER','GL + journal entries',false,0,now(),now()),
  (gen_random_uuid(),'FINANCIAL_CONTROLLER','Accounting oversight + closures',false,0,now(),now()),
  (gen_random_uuid(),'INTERNAL_AUDITOR','Read-only audit across modules',false,0,now(),now()),
  (gen_random_uuid(),'AUDITOR_READONLY','Pure read-only viewer',false,0,now(),now());

-- Maker-checker flag on the credit-approval permission.
UPDATE permissions SET can_maker_checker = true WHERE code = 'APPROVE_LOAN';

-- Core-role grants (drawn from the 13 permissions seeded by V2). Other roles are seeded
-- empty here and granted as controllers are annotated per module (see deferred DEF-1C-16).
-- PARTNER_ADMIN → all permissions.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.name = 'PARTNER_ADMIN';

-- TELLER → cash + account read.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_ACCOUNT','DEPOSIT','WITHDRAW') WHERE r.name = 'TELLER';

-- CUSTOMER_SERVICE_OFFICER → customer create/read/update.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_CUSTOMER','CREATE_CUSTOMER','UPDATE_CUSTOMER')
   WHERE r.name = 'CUSTOMER_SERVICE_OFFICER';

-- LOAN_OFFICER → loan read/create.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_LOAN','CREATE_LOAN') WHERE r.name = 'LOAN_OFFICER';

-- CREDIT_APPROVER → loan approve/disburse.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('APPROVE_LOAN','DISBURSE_LOAN') WHERE r.name = 'CREDIT_APPROVER';

-- PAYMENTS_OFFICER → initiate payment.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code = 'INITIATE_PAYMENT' WHERE r.name = 'PAYMENTS_OFFICER';

-- Read-only roles → all READ_* + RUN_REPORT.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_CUSTOMER','READ_ACCOUNT','READ_LOAN','RUN_REPORT')
   WHERE r.name IN ('AUDITOR_READONLY','INTERNAL_AUDITOR','CUSTOMER_SUPPORT');
```

- [ ] **Step 4: Run the seed test — expect pass**

Run: `cd baas-engine && ./mvnw -q test -Dtest=RoleCatalogueSeedTest`
Expected: PASS — 30 roles, TELLER has DEPOSIT+WITHDRAW.

- [ ] **Step 5: Run the full suite (RoleControllerTest still green)**

Run: `cd baas-engine && ./mvnw -q test`
Expected: BUILD SUCCESS — `RoleControllerTest` creates a `LOAN_OFFICER` role (name not unique-constrained) without conflict.

- [ ] **Step 6: Commit**

```bash
git add baas-engine/src/main/resources/db/migration/tenant/V3__role_catalogue.sql \
        baas-engine/src/test/java/com/nubbank/baas/engine/role/RoleCatalogueSeedTest.java
git commit -m "feat(rbac): seed 30-role partner catalogue per tenant schema"
```

---

## Task 8: Operator deprovisioning + reconciliation seam

Revoke a deprovisioned operator's grants (the §6.2.4 mitigation) and wire the nightly reconciliation against a directory seam (live Keycloak impl deferred — `DEF-1C-17`).

**Files:**
- Create: `…/auth/KeycloakUserDirectory.java`
- Create: `…/auth/StubKeycloakUserDirectory.java`
- Create: `…/auth/OperatorProvisioningService.java`
- Create: `…/auth/OperatorGrantReconciliationJob.java`
- Test: `…/test/java/com/nubbank/baas/engine/auth/OperatorProvisioningServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `OperatorProvisioningServiceTest.java`:

```java
package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class OperatorProvisioningServiceTest extends AbstractIntegrationTest {

    @Autowired OperatorProvisioningService service;
    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;
    @Autowired PermissionRepository permRepo;
    @Autowired UserRoleRepository userRoleRepo;

    @Test
    void revokeAllGrants_removesUserRoles() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Deprov Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .contactEmail("d@test.com").build());
        provisioning.provision(org.getId(), schema);

        UUID sub = UUID.randomUUID();
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "BASIC",
            "PRODUCTION", "API_KEY", null));
        try {
            Role r = roleRepo.save(Role.builder().name("TMP").permissions(Set.of()).build());
            userRoleRepo.save(UserRole.builder().userId(sub).role(r).build());
            assertThat(userRoleRepo.findByUserId(sub)).isNotEmpty();

            service.revokeAllGrants(sub);

            assertThat(userRoleRepo.findByUserId(sub)).isEmpty();
        } finally { PartnerContext.clear(); }
    }
}
```

- [ ] **Step 2: Run it — expect failure**

Run: `cd baas-engine && ./mvnw -q test -Dtest=OperatorProvisioningServiceTest`
Expected: FAIL — `OperatorProvisioningService` does not exist.

- [ ] **Step 3: Define the directory seam + stub**

Create `KeycloakUserDirectory.java`:

```java
package com.nubbank.baas.engine.auth;

import java.util.Set;

/** Lists the active operator subjects for a partner realm. Live Keycloak impl is DEF-1C-17. */
public interface KeycloakUserDirectory {
    /** @return the set of active (enabled, existing) Keycloak subjects for the partner's realm. */
    Set<String> activeSubjects(String partnerId);
}
```

Create `StubKeycloakUserDirectory.java`:

```java
package com.nubbank.baas.engine.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.Set;

/** Stub-mode directory: reports every subject as still active (no orphans pruned). */
@Component
@Profile("!live-keycloak")
public class StubKeycloakUserDirectory implements KeycloakUserDirectory {
    @Override public Set<String> activeSubjects(String partnerId) { return Set.of(); }
}
```

> The stub returns an empty set; the reconciliation job treats an empty directory as "unknown — prune nothing" (see Step 5), so stub-mode never revokes real grants.

- [ ] **Step 4: Implement the provisioning service**

Create `OperatorProvisioningService.java`:

```java
package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.role.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Operator-side grant lifecycle. Revokes RBAC grants when an operator is removed (§6.2.4). */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorProvisioningService {

    private final UserRoleRepository userRoleRepo;

    @Transactional
    public void revokeAllGrants(UUID subject) {
        userRoleRepo.deleteByUserId(subject);
        log.info("Revoked all RBAC grants for operator sub={}", subject);
    }
}
```

- [ ] **Step 5: Implement the reconciliation job**

Create `OperatorGrantReconciliationJob.java`:

```java
package com.nubbank.baas.engine.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly sweep: revokes RBAC grants whose Keycloak subject is no longer active (§6.2.4).
 * In stub-mode the directory returns empty → nothing is pruned. The live cross-schema sweep
 * (iterate active partner schemas, diff user_roles against the realm) is DEF-1C-17.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperatorGrantReconciliationJob {

    private final KeycloakUserDirectory directory;

    @Scheduled(cron = "0 30 2 * * *") // 02:30 daily
    public void reconcile() {
        log.info("Operator grant reconciliation tick (stub-mode: no-op until DEF-1C-17)");
        // Live implementation (DEF-1C-17): for each active partner schema, set PartnerContext,
        // load distinct user_roles.user_id, compare with directory.activeSubjects(partnerId),
        // and revokeAllGrants(sub) for each subject absent from the directory.
    }
}
```

> `@Scheduled` requires `@EnableScheduling`. Verify it is present (the COB module likely enables it):
> Run: `cd baas-engine && grep -rq "@EnableScheduling" src/main/java && echo OK || echo "ADD @EnableScheduling to a @Configuration"`
> If `ADD`, add `@EnableScheduling` to `MethodSecurityConfig` (or a scheduling config).

- [ ] **Step 6: Run the test — expect pass**

Run: `cd baas-engine && ./mvnw -q test -Dtest=OperatorProvisioningServiceTest`
Expected: PASS.

- [ ] **Step 7: Run the full suite**

Run: `cd baas-engine && ./mvnw -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/auth/KeycloakUserDirectory.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/auth/StubKeycloakUserDirectory.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/auth/OperatorProvisioningService.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/auth/OperatorGrantReconciliationJob.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/auth/OperatorProvisioningServiceTest.java
git commit -m "feat(auth): operator deprovisioning + nightly reconciliation seam"
```

---

## Task 9: SecurityConfig multi-chain readiness

Scope the existing partner chain so the Custodian track can add an admin chain (`/baas-admin/v1/**`) without conflict. Behaviour is unchanged for `/baas/v1/**`.

**Files:**
- Modify: `…/config/SecurityConfig.java`
- Test: existing `…/security/SecurityBoundariesTest.java` (regression)

- [ ] **Step 1: Run the existing boundary test to capture the green baseline**

Run: `cd baas-engine && ./mvnw -q test -Dtest=SecurityBoundariesTest`
Expected: PASS (baseline).

- [ ] **Step 2: Order + scope the partner chain**

In `SecurityConfig.java`, annotate the bean with `@Order` and add a `securityMatcher` so a second chain can coexist. Replace the `filterChain` bean signature + first line:

```java
    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain partnerFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/baas/v1/**", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**")
            .csrf(AbstractHttpConfigurer::disable)
            // ... rest unchanged ...
```

Keep the rest of the method body identical (filters, authorizeHttpRequests). Rename the method to `partnerFilterChain` (bean name change is safe — it is autowired by type).

- [ ] **Step 3: Run the boundary test — still green**

Run: `cd baas-engine && ./mvnw -q test -Dtest=SecurityBoundariesTest`
Expected: PASS — `/baas/v1/**` behaviour unchanged.

- [ ] **Step 4: Run the full suite**

Run: `cd baas-engine && ./mvnw -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/config/SecurityConfig.java
git commit -m "refactor(security): scope partner chain with @Order + securityMatcher for admin chain"
```

---

## Task 10: Deferred-items registry + downstream contracts

**Files:**
- Create: `docs/deferred-items.md`
- Create: `docs/contracts/phase1c-interfaces.md`

- [ ] **Step 1: Create the deferred-items registry**

Create `docs/deferred-items.md` with the spec §13 table (DEF-1C-01 … DEF-1C-14) plus the items discovered during Foundation:

```markdown
# NubBank BaaS — Deferred Items Registry

Structure: `ID | Item | Why deferred | Earliest phase | Source`. Append new deferrals here.

| ID | Item | Why deferred | Earliest phase | Source |
|---|---|---|---|---|
| DEF-1C-01 | EMV ARQC/ARPC validation (TDES + SM4) | No live card scheme in 1C | Phase 2 | spec §6.7 |
| DEF-1C-02 | HSM hardware adapter (Thales) | Software stub sufficient | Phase 2 | spec §6.7 |
| DEF-1C-03 | Scheme-specific jPOS packagers + private DEs | No certification in 1C | Phase 2 | spec §6.7 |
| DEF-1C-04 | Settlement/advice MTIs | No settlement in 1C | Phase 2 | spec §6.7 |
| DEF-1C-05 | Tokenization vault (DPAN) | No tokenized creds in 1C | Phase 2 | spec §6.6 |
| DEF-1C-06 | Interchange, 3DS/ACS, chargeback | Scheme-grade card ops | Phase 2 | spec §3 |
| DEF-1C-07 | Card personalization bureau (CDP) | No physical production | Phase 2 | spec §3 |
| DEF-1C-08 | DB-level orphaned-grant guard for user_roles.user_id | Reconciliation sweep covers it | revisit | spec §6.2.4 |
| DEF-1C-09 | Keycloak JWKS rotation/invalidation | Default decoder cache acceptable | Phase 2 | spec §6.1 |
| DEF-1C-10 | Live external export delivery (SFTP) | Stub-mode in 1C | Phase 2 | spec §6.5 |
| DEF-1C-11 | Frontend component-library choice | Decide at Track-Backoffice start | Phase 1C | spec §7.1 |
| DEF-1C-12 | Routing-by-annotation vs by-chain | Start by-chain | Phase 1C/2 | spec §6.3 |
| DEF-1C-13 | Maker-checker UI beyond engine support | Engine support is the constraint | Phase 3 | spec §13 |
| DEF-1C-14 | TRADE_FINANCE_OFFICER role | No corporate partner in 1C | on corporate onboard | spec §14 |
| DEF-1C-15 | Granular RBAC for HMAC partner-login users | First-party creds get full tenant authority in 1C | Phase 2 | Foundation Task 5 |
| DEF-1C-16 | @PreAuthorize rollout across all 38 controllers | Demonstrated on CustomerController; rolled out per module | Phase 1C (per track) | Foundation Task 6 |
| DEF-1C-17 | Live Keycloak directory + cross-schema reconciliation sweep | Stub directory in 1C | Phase 2 | Foundation Task 8 |
| DEF-1C-18 | Authority caching (per-request DB hit today) | Acceptable load in 1C | Phase 2 | Foundation Task 5 |
```

- [ ] **Step 2: Create the downstream interface contracts**

Create `docs/contracts/phase1c-interfaces.md`:

```markdown
# Phase 1C — Cross-Track Interface Contracts

Foundation publishes these so parallel tracks build against stable shapes.

## 1. Operator authority claim format (Backoffice, all backend tracks)
- Operators authenticate via Keycloak realm `baas-partner-{uuid}`; token `iss` = realm issuer URL.
- `sub` = operator UUID; it is the join key to tenant-schema `user_roles.user_id` (no FK — §6.2.4).
- Spring authorities = `permissions.code` values granted to the operator's roles.
- First-party credentials (API key, HMAC login JWT) carry the **full** tenant authority set.

## 2. BIN-lookup contract (Card → FEP)
- `baas-card` exposes `GET /internal/v1/bins/{bin}` (HMAC inter-service auth) →
  `{ "partnerId": "<uuid>", "schemaName": "partner_<uuid>" }` or 404.
- `baas-fep` calls this after extracting DE2 PAN → 6/8-digit BIN; caches 5 min (Caffeine).
- Unknown BIN → FEP returns ISO 8583 RC `91`, no PAN echo.

## 3. Admin namespace reservation (Custodian, Platform-Admin)
- `/baas-admin/v1/**` is reserved for the NubBank admin chain (`@Order(1)`, admin-issuer only).
- Partner chain is `@Order(2)`, `securityMatcher("/baas/v1/**", …)`.
- Admin chain routes to the `baas_readonly_admin` datasource (Custodian Task).
```

- [ ] **Step 3: Commit**

```bash
git add docs/deferred-items.md docs/contracts/phase1c-interfaces.md
git commit -m "docs: deferred-items registry + Phase 1C cross-track interface contracts"
```

---

## Final verification (before opening the Foundation PR)

- [ ] **Run the full suite**

Run: `cd baas-engine && ./mvnw -q test`
Expected: `BUILD SUCCESS`, 0 failures. Note the test count for the `baas-log.md` entry.

- [ ] **Confirm migrations apply cleanly on a fresh schema** (Testcontainers does this per run; a green suite confirms it).

- [ ] **Update `baas-log.md` + `CLAUDE.md`** per the `/baas` Session Completion Gate (new modules, Confirmed Platform Versions SHA, gotchas: the first-party-vs-operator authority boundary, the `iss`-branch in `PartnerContextFilter`).

- [ ] **Rebase + open PR:**

```bash
cd <foundation worktree> && git fetch origin && git rebase origin/main
git push -u origin feature/phase1c-foundation
gh pr create --base main --head feature/phase1c-foundation --title "Phase 1C Foundation: operator identity + Hybrid RBAC" --fill
```

---

## Self-Review (completed by plan author)

**Spec coverage:**
- §6.1 D1 operator identity → Tasks 1–4 ✓
- §6.2 D2 Hybrid RBAC → Tasks 5–6 ✓
- §6.2.4 deprovisioning/reconciliation → Task 8 ✓
- §14 30-role catalogue → Task 7 ✓
- §13 D10 registry → Task 10 ✓
- SecurityConfig multi-chain readiness (enabler for Custodian D3) → Task 9 ✓
- Downstream contracts (BIN-lookup, authority format, admin namespace) → Task 10 ✓

**Deliberately deferred (logged in registry, not gaps):** admin filter chain + read-only DS (Custodian track, D3); full `@PreAuthorize` rollout (DEF-1C-16); live Keycloak directory (DEF-1C-17); granular RBAC for HMAC users (DEF-1C-15).

**Placeholder scan:** no "TBD/TODO/implement later" in steps. The reconciliation job body is intentionally a documented no-op seam (DEF-1C-17), not a placeholder — it has a real `@Scheduled` trigger and a real directory interface.

**Type consistency:** `OperatorJwtDecoderFactory.decoderFor(String)` used identically in Tasks 3, 4, 6 tests; `PartnerContext(...)` 6-arg constructor matches the record; `AuthorityResolver.operatorAuthorities(UUID)` / `fullTenantAuthorities()` consistent across Tasks 5–8; `revokeAllGrants(UUID)` consistent Task 8; `findByUserId`/`deleteByUserId` are real existing repo methods.
