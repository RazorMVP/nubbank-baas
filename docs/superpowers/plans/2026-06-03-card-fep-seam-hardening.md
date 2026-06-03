# Card/FEP Seam Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the eight findings (F1–F8) on the `baas-card`/`baas-fep` authorization seam so Stage 5 wiring builds on a correct foundation.

**Architecture:** Two independent Spring Boot 3.5 / Java 21 modules sharing one PostgreSQL (Hibernate SCHEMA multi-tenancy). `baas-card` (8081) owns card domain + the internal authorize/reversal decision; `baas-fep` (8082/8583) is a stateless ISO 8583 front-end that calls card over body-signed HMAC. This plan does NOT add the real balance check or actual fund movement (those are Stage 5 / Phase 2).

**Tech Stack:** Spring Boot 3.5.3, Java 21, Hibernate 6 (SCHEMA multitenancy), Flyway 10 (`flyway_schema_history_card`), jPOS 2.1.10, Netty 4.1, Caffeine, JUnit 5 + AssertJ + Testcontainers (PostgreSQL 16), Mockito, `MockRestServiceServer`.

**Spec:** `docs/superpowers/specs/2026-06-03-card-fep-seam-hardening-design.md`

---

## File Structure

### baas-card (create)
- `card/src/main/java/com/nubbank/baas/card/common/CurrencyMinorUnits.java` — JDK-derived ISO 4217 numeric-code → minor-unit exponent (F1)
- `card/src/main/java/com/nubbank/baas/card/authorize/AuthorizationIdempotency.java` — per-tenant dedup entity (F3)
- `card/src/main/java/com/nubbank/baas/card/authorize/AuthorizationIdempotencyRepository.java` (F3)
- `card/src/main/java/com/nubbank/baas/card/authorize/IdempotencyPurgeJob.java` — per-tenant `@Scheduled` purge (F3)
- `card/src/main/java/com/nubbank/baas/card/authorize/ReversalService.java` (F6)
- `card/src/main/java/com/nubbank/baas/card/authorize/InternalReversalController.java` (F6)
- `card/src/main/java/com/nubbank/baas/card/authorize/dto/ReversalRequest.java` (F6)
- `card/src/main/java/com/nubbank/baas/card/authorize/dto/ReversalResponse.java` (F6)
- `card/src/main/resources/db/migration/card-tenant/V2__authorization_idempotency_and_limit_currency.sql` (F2, F3)

### baas-card (modify)
- `authorize/dto/AuthorizationDecisionRequest.java` — +3 fields (F3)
- `authorize/AuthorizationDecisionService.java` — scaling (F1), currency limit (F2), idempotency (F3), env (F5)
- `limit/CardLimit.java`, `limit/CardLimitService.java`, `limit/dto/UpdateCardLimitsRequest.java`, `limit/dto/CardLimitResponse.java` — currency (F2)
- `bin/BinService.java` — `normalizeRangeEnd` (F7)
- `config/InternalServiceAuthFilter.java` — 300→60 (F4)
- `CardApplication.java` — `@EnableScheduling` (verify; add if absent)

### baas-fep (create)
- `fep/routing/ReversalDecision.java` (F6)

### baas-fep (modify)
- `routing/AuthorizationDecision.java` — +3 fields on `Request` (F3)
- `routing/CardClient.java` — `reverse(...)` (F6)
- `client/HttpCardClient.java` — `reverse(...)` (F6)
- `router/AuthorizationHandler.java` — forward DE11/41/7 (F3)
- `router/ReversalHandler.java` — DE90 + card reversal (F6)
- `iso/IsoField.java` — `ORIGINAL_DATA = 90` (F6)
- `resources/iso8583-1987-fields.xml` — DE90 (F6)
- `config/CardClientConfig.java` — `getRawPath()` (F8)
- `support/StubCardClient.java` (test) — implement `reverse` (F6)

### Docs (modify, Task 13)
- `docs/contracts/phase1c-interfaces.md`, `docs/api-reference.html`, `docs/deferred-items.md`, `CLAUDE.md`, `baas-log.md`

---

## Important conventions (read once)

- **Run all card tests:** `cd ~/nubbank-baas/baas-card && ./mvnw -B test`. Single test: `./mvnw -B test -Dtest=ClassName`. Card tests need Docker (Testcontainers).
- **Run all fep tests:** `cd ~/nubbank-baas/baas-fep && ./mvnw -B test`. No Docker needed.
- **Commit cadence:** one commit per task (after its tests pass). Branch is `feature/phase1c-seam-hardening` (already created).
- **Co-author trailer on every commit:**
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```
- **The two authorize DTOs live in separate Maven modules** with no shared dependency, so a single cross-class reflection parity test is impossible. Instead, each service gets a *shape test* asserting its record's component names/types match the canonical contract list (Tasks 5 & 10). Both referencing the same list keeps them in sync transitively.

---

## Task 1: Currency minor-unit exponent table (F1)

**Files:**
- Create: `baas-card/src/main/java/com/nubbank/baas/card/common/CurrencyMinorUnits.java`
- Test: `baas-card/src/test/java/com/nubbank/baas/card/common/CurrencyMinorUnitsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.card.common;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CurrencyMinorUnitsTest {

    private final CurrencyMinorUnits units = new CurrencyMinorUnits();

    @Test
    void ngn_isTwoDecimals() {
        assertThat(units.exponentFor("566")).contains(2);   // NGN
    }

    @Test
    void usd_isTwoDecimals() {
        assertThat(units.exponentFor("840")).contains(2);   // USD
    }

    @Test
    void jpy_isZeroDecimals() {
        assertThat(units.exponentFor("392")).contains(0);   // JPY
    }

    @Test
    void kwd_isThreeDecimals() {
        assertThat(units.exponentFor("414")).contains(3);   // KWD (Kuwaiti dinar)
    }

    @Test
    void unknownNumericCode_isEmpty() {
        assertThat(units.exponentFor("999")).isEmpty();     // not a real ISO 4217 code
    }

    @Test
    void nullOrBlank_isEmpty() {
        assertThat(units.exponentFor(null)).isEmpty();
        assertThat(units.exponentFor("")).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=CurrencyMinorUnitsTest`
Expected: FAIL — `CurrencyMinorUnits` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.nubbank.baas.card.common;

import org.springframework.stereotype.Component;

import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ISO 4217 numeric-code → minor-unit exponent, derived from the JDK at startup.
 *
 * <p>Replaces the previous hardcoded {@code movePointLeft(2)} in
 * {@code AuthorizationDecisionService}, which silently mis-scaled 0-decimal
 * (JPY) and 3-decimal (KWD/BHD/TND) currencies.
 *
 * <p>The table is built from {@link Currency#getAvailableCurrencies()} so it tracks
 * the JDK's maintained ISO 4217 data — no hand-maintained list to drift. Keys are the
 * zero-padded 3-digit numeric code (e.g. {@code "566"} for NGN); values are
 * {@link Currency#getDefaultFractionDigits()}. Pseudo-currencies (numeric code {@code <= 0}
 * or fraction digits {@code < 0}, e.g. XXX) are excluded so an unknown/invalid code
 * resolves to {@link Optional#empty()} and the caller can decline (RC 12).
 */
@Component
public class CurrencyMinorUnits {

    private final Map<String, Integer> exponentByNumeric;

    public CurrencyMinorUnits() {
        Map<String, Integer> map = new HashMap<>();
        for (Currency c : Currency.getAvailableCurrencies()) {
            int numeric = c.getNumericCode();
            int digits = c.getDefaultFractionDigits();
            if (numeric > 0 && digits >= 0) {
                map.put(String.format("%03d", numeric), digits);
            }
        }
        this.exponentByNumeric = Map.copyOf(map);
    }

    /**
     * @param numericCode ISO 4217 numeric code as it appears in DE49 (e.g. {@code "566"}).
     * @return the minor-unit exponent, or empty if the code is null/blank/unknown.
     */
    public Optional<Integer> exponentFor(String numericCode) {
        if (numericCode == null || numericCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(exponentByNumeric.get(numericCode));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=CurrencyMinorUnitsTest`
Expected: PASS (6 tests).

> Note: if `getNumericCode()` returns a code not zero-padded to 3 (it returns an int like 566 or 8), `%03d` handles it (e.g. ALL=008). DE49 in ISO 8583 is a 3-digit field, so callers pass 3-char strings.

- [ ] **Step 5: Commit**

```bash
cd ~/nubbank-baas
git add baas-card/src/main/java/com/nubbank/baas/card/common/CurrencyMinorUnits.java \
        baas-card/src/test/java/com/nubbank/baas/card/common/CurrencyMinorUnitsTest.java
git commit -m "feat(card): JDK-derived currency minor-unit exponent table (F1)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Flyway migration — idempotency table + limit currency column (F2, F3)

**Files:**
- Create: `baas-card/src/main/resources/db/migration/card-tenant/V2__authorization_idempotency_and_limit_currency.sql`

> Tenant migrations run on every partner + sandbox schema under `flyway_schema_history_card`. This is a NEW version file (V2) — unlike V1 which is appended to, schema changes after first release MUST be a new version so already-provisioned schemas get the delta.

- [ ] **Step 1: Write the migration**

```sql
-- V2 — Card/FEP seam hardening (Session 11).
-- Runs on every partner_{uuid} and sandbox_{uuid} schema (card-tenant location),
-- tracked by flyway_schema_history_card.

-- F2: per-card limits gain a currency. A limit with a non-null amount but a
-- currency that does not match the transaction currency is NOT comparable and the
-- authorization is declined (fail-safe). Nullable so existing all-null limit rows
-- (no amounts) remain valid; the service requires it when any amount is set.
ALTER TABLE card_limits ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3);

-- F3: authorization idempotency. ISO 8583 terminals retransmit on timeout; the
-- composite key (STAN | terminalId | transmissionDateTime) dedups a retransmit so it
-- returns the cached decision instead of re-deciding (critical once Phase 2 adds real
-- balance holds). TENANT table (NO partner_id; the schema is the boundary).
-- 'reversed' is flipped by the reversal endpoint (F6). Retention is enforced by a
-- daily purge job (rows older than 24h); lookup is by idem_key alone so lookup and
-- the UNIQUE constraint agree on exactly one row per key.
CREATE TABLE IF NOT EXISTS authorization_idempotency (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    idem_key      VARCHAR(120) NOT NULL UNIQUE,
    decision      VARCHAR(10)  NOT NULL,
    response_code VARCHAR(2)   NOT NULL,
    message       VARCHAR(255),
    reversed      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Purge job filters on created_at; index it.
CREATE INDEX IF NOT EXISTS idx_authz_idem_created_at
    ON authorization_idempotency (created_at);
```

- [ ] **Step 2: Verify it applies (run the existing suite, which provisions fresh schemas)**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=CardApplicationContextTest`
Expected: PASS — context boots and Flyway applies V2 to the provisioned test schemas with no error. (If `CardApplicationContextTest` does not provision a tenant schema, run `-Dtest=CardLimitTest` instead, which exercises a provisioned schema.)

- [ ] **Step 3: Commit**

```bash
cd ~/nubbank-baas
git add baas-card/src/main/resources/db/migration/card-tenant/V2__authorization_idempotency_and_limit_currency.sql
git commit -m "feat(card): V2 migration — idempotency table + card_limits.currency_code (F2,F3)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: CardLimit currency field + service plumbing (F2)

**Files:**
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/limit/CardLimit.java`
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/limit/dto/UpdateCardLimitsRequest.java`
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/limit/dto/CardLimitResponse.java`
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/limit/CardLimitService.java`
- Test: `baas-card/src/test/java/com/nubbank/baas/card/limit/CardLimitTest.java` (extend)

- [ ] **Step 1: Add `currencyCode` to the entity**

In `CardLimit.java`, add after the `monthly` field (line ~43):

```java
    @Column(name = "currency_code", length = 3)
    private String currencyCode;
```

- [ ] **Step 2: Add `currencyCode` to the request + response DTOs**

In `UpdateCardLimitsRequest.java`, add a `String currencyCode` component (keep existing fields; if it is a record, add the param). Example record shape after change:

```java
public record UpdateCardLimitsRequest(
    java.math.BigDecimal dailyPurchase,
    java.math.BigDecimal dailyWithdrawal,
    java.math.BigDecimal perTxn,
    java.math.BigDecimal monthly,
    String currencyCode
) {}
```

In `CardLimitResponse.java`, add `String currencyCode` to the record and map it in both `from(CardLimit)` and `forCard(UUID)` factories (`forCard` sets it to `null`). Example: in `from`, add `limit.getCurrencyCode()` in the corresponding position; in `forCard`, pass `null`.

- [ ] **Step 3: Write the failing test (extend `CardLimitTest`)**

Add this test method to `CardLimitTest` (uses the same authenticated-PUT helper pattern already in that class — mirror an existing `updateLimits`-style test for the request/response shape):

```java
    @Test
    void updateLimits_persistsAndReturnsCurrencyCode() {
        // (Mirror the existing CardLimitTest setup: create partner, issue a card, get jwt.)
        // Build the PUT body WITH currencyCode and assert it round-trips.
        // Replace `issueCardAndGetId(partner)` / `putLimits(...)` with this class's existing helpers.
        var partner = newPartnerWithCard();                 // existing helper in CardLimitTest
        java.util.Map<String,Object> body = new java.util.LinkedHashMap<>();
        body.put("perTxn", 25000);
        body.put("dailyPurchase", 100000);
        body.put("currencyCode", "566");

        var resp = putLimits(partner.jwt, partner.cardId, body);   // existing helper
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        java.util.Map<String,Object> data = (java.util.Map<String,Object>) resp.getBody().get("data");
        assertThat(data.get("currencyCode")).isEqualTo("566");
    }
```

> If `CardLimitTest` does not already expose `newPartnerWithCard()` / `putLimits(...)`, reuse whatever issue-card + PUT-limits helpers it has (it already tests the limits endpoint). The assertion is the new part: `currencyCode` round-trips.

- [ ] **Step 4: Run test to verify it fails**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=CardLimitTest`
Expected: FAIL — `currencyCode` not present in response / DTO compile error.

- [ ] **Step 5: Wire `currencyCode` through the service**

In `CardLimitService.updateLimits`, after `limit.setMonthly(req.monthly());` add:

```java
        limit.setCurrencyCode(req.currencyCode());
```

(REPLACE semantics already match — an absent currency becomes null.)

- [ ] **Step 6: Run test to verify it passes**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=CardLimitTest`
Expected: PASS (existing tests + the new currency test).

- [ ] **Step 7: Commit**

```bash
cd ~/nubbank-baas
git add baas-card/src/main/java/com/nubbank/baas/card/limit/
git add baas-card/src/test/java/com/nubbank/baas/card/limit/CardLimitTest.java
git commit -m "feat(card): currency_code on CardLimit + DTOs (F2 plumbing)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Authorization idempotency entity, repository, purge job (F3 storage)

**Files:**
- Create: `baas-card/src/main/java/com/nubbank/baas/card/authorize/AuthorizationIdempotency.java`
- Create: `baas-card/src/main/java/com/nubbank/baas/card/authorize/AuthorizationIdempotencyRepository.java`
- Create: `baas-card/src/main/java/com/nubbank/baas/card/authorize/IdempotencyPurgeJob.java`
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/CardApplication.java` (ensure `@EnableScheduling`)
- Test: `baas-card/src/test/java/com/nubbank/baas/card/authorize/IdempotencyPurgeJobTest.java`

- [ ] **Step 1: Create the entity**

```java
package com.nubbank.baas.card.authorize;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Authorization idempotency record (F3). TENANT entity — NO {@code @Table(schema=...)};
 * Hibernate routes it to the partner schema set by {@link com.nubbank.baas.card.tenant.PartnerContext}.
 *
 * <p>{@code idemKey = stan | terminalId | transmissionDateTime} (ISO DE11/DE41/DE7).
 * A retransmit with the same key returns the cached decision instead of re-deciding.
 * {@code reversed} is flipped by the reversal endpoint (F6). Retention: a daily purge
 * (see {@link IdempotencyPurgeJob}) deletes rows older than 24h.
 */
@Entity
@Table(name = "authorization_idempotency",
       uniqueConstraints = @UniqueConstraint(columnNames = "idem_key"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuthorizationIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idem_key", nullable = false, unique = true, length = 120)
    private String idemKey;

    @Column(nullable = false, length = 10)
    private String decision;

    @Column(name = "response_code", nullable = false, length = 2)
    private String responseCode;

    @Column(length = 255)
    private String message;

    @Column(nullable = false)
    private boolean reversed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
```

- [ ] **Step 2: Create the repository**

```java
package com.nubbank.baas.card.authorize;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthorizationIdempotencyRepository
        extends JpaRepository<AuthorizationIdempotency, UUID> {

    Optional<AuthorizationIdempotency> findByIdemKey(String idemKey);

    @Modifying
    @Transactional
    @Query("DELETE FROM AuthorizationIdempotency a WHERE a.createdAt < :cutoff")
    int deleteOlderThan(Instant cutoff);
}
```

- [ ] **Step 3: Write the failing purge-job test**

```java
package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.AbstractCardIntegrationTest;
import com.nubbank.baas.card.auth.PartnerJwtService;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.support.TestPartner;
import com.nubbank.baas.card.tenant.PartnerContext;
import com.nubbank.baas.card.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the per-tenant purge deletes rows older than the retention window while
 * keeping fresh rows, in the correct partner schema.
 */
class IdempotencyPurgeJobTest extends AbstractCardIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;
    @Autowired private AuthorizationIdempotencyRepository idemRepo;
    @Autowired private IdempotencyPurgeJob purgeJob;

    @Test
    void purge_deletesOldRows_keepsFreshRows_perTenant() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);

        // Insert one old (48h) and one fresh row in the partner's schema.
        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "INTERNAL", "PRODUCTION", "INTERNAL", null));
            idemRepo.save(AuthorizationIdempotency.builder()
                .idemKey("OLD|TERM0001|0101120000").decision("APPROVE").responseCode("00")
                .message("old").reversed(false)
                .createdAt(Instant.now().minus(48, ChronoUnit.HOURS)).build());
            idemRepo.save(AuthorizationIdempotency.builder()
                .idemKey("NEW|TERM0001|0101120000").decision("APPROVE").responseCode("00")
                .message("new").reversed(false)
                .createdAt(Instant.now()).build());
        } finally {
            PartnerContext.clear();
        }

        purgeJob.purgeAllTenants();

        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "INTERNAL", "PRODUCTION", "INTERNAL", null));
            assertThat(idemRepo.findByIdemKey("OLD|TERM0001|0101120000")).isEmpty();
            assertThat(idemRepo.findByIdemKey("NEW|TERM0001|0101120000")).isPresent();
        } finally {
            PartnerContext.clear();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=IdempotencyPurgeJobTest`
Expected: FAIL — `IdempotencyPurgeJob` does not exist.

- [ ] **Step 5: Create the purge job**

```java
package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.partner.PartnerOrganization;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Daily purge of expired {@link AuthorizationIdempotency} rows (F3 retention).
 *
 * <p>The idempotency table is per-tenant, so a context-less scheduled job cannot reach
 * it (Hibernate would route to {@code public}, where the table does not exist). This
 * job therefore ENUMERATES every provisioned schema (each partner has a
 * {@code partner_<hex>} production schema and a {@code sandbox_<hex>} sandbox schema),
 * sets {@link PartnerContext} per schema, deletes rows older than {@link #RETENTION},
 * and ALWAYS clears the context in a {@code finally}.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);
    private static final Duration RETENTION = Duration.ofHours(24);

    private final PartnerOrganizationRepository orgRepo;
    private final AuthorizationIdempotencyRepository idemRepo;

    /** Daily at 03:30. */
    @Scheduled(cron = "0 30 3 * * *")
    public void scheduledPurge() {
        purgeAllTenants();
    }

    /** Package/public for tests — purge every provisioned schema. */
    public void purgeAllTenants() {
        Instant cutoff = Instant.now().minus(RETENTION);
        List<PartnerOrganization> orgs = orgRepo.findAll();
        int totalDeleted = 0;
        for (PartnerOrganization org : orgs) {
            String prodSchema = org.getSchemaName();                       // partner_<hex>
            String sandboxSchema = "sandbox_" + prodSchema.substring("partner_".length());
            totalDeleted += purgeSchema(org, prodSchema, cutoff);
            totalDeleted += purgeSchema(org, sandboxSchema, cutoff);
        }
        if (totalDeleted > 0) {
            log.info("Idempotency purge removed {} expired rows across {} partners",
                totalDeleted, orgs.size());
        }
    }

    private int purgeSchema(PartnerOrganization org, String schema, Instant cutoff) {
        try {
            PartnerContext.set(new PartnerContext(
                org.getId().toString(), schema, "INTERNAL", "PRODUCTION", "INTERNAL", null));
            return idemRepo.deleteOlderThan(cutoff);
        } catch (RuntimeException ex) {
            // A single bad/un-provisioned schema must not abort the whole sweep.
            log.warn("Idempotency purge skipped schema {}: {}", schema, ex.getMessage());
            return 0;
        } finally {
            PartnerContext.clear();
        }
    }
}
```

- [ ] **Step 6: Ensure scheduling is enabled**

Check `CardApplication.java` for `@EnableScheduling`. If absent, add the import `org.springframework.scheduling.annotation.EnableScheduling` and the annotation on the class. (Card already runs a `@Scheduled` settlement-style job? If `@EnableScheduling` is already present, make no change.)

- [ ] **Step 7: Run test to verify it passes**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=IdempotencyPurgeJobTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
cd ~/nubbank-baas
git add baas-card/src/main/java/com/nubbank/baas/card/authorize/AuthorizationIdempotency.java \
        baas-card/src/main/java/com/nubbank/baas/card/authorize/AuthorizationIdempotencyRepository.java \
        baas-card/src/main/java/com/nubbank/baas/card/authorize/IdempotencyPurgeJob.java \
        baas-card/src/main/java/com/nubbank/baas/card/CardApplication.java \
        baas-card/src/test/java/com/nubbank/baas/card/authorize/IdempotencyPurgeJobTest.java
git commit -m "feat(card): authorization idempotency entity/repo + per-tenant purge job (F3)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Authorize contract +3 fields & shape test (F3 contract — card side)

**Files:**
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/authorize/dto/AuthorizationDecisionRequest.java`
- Test: `baas-card/src/test/java/com/nubbank/baas/card/authorize/AuthorizationContractShapeTest.java`

- [ ] **Step 1: Write the failing shape test (canonical contract list)**

```java
package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the FROZEN §2a authorize contract. The matching FEP record
 * ({@code AuthorizationDecision.Request}) lives in a separate Maven module, so a
 * cross-class reflection test is impossible — instead both services assert their
 * record against THIS canonical list (kept identical in
 * {@code docs/contracts/phase1c-interfaces.md} §2a). If you change one, change both.
 */
class AuthorizationContractShapeTest {

    @Test
    void requestComponents_matchCanonicalContract() {
        Map<String, Class<?>> expected = new LinkedHashMap<>();
        expected.put("partnerId", String.class);
        expected.put("schemaName", String.class);
        expected.put("pan", String.class);
        expected.put("amountMinor", long.class);
        expected.put("currency", String.class);
        expected.put("stan", String.class);
        expected.put("terminalId", String.class);
        expected.put("transmissionDateTime", String.class);

        Map<String, Class<?>> actual = new LinkedHashMap<>();
        for (RecordComponent rc : AuthorizationDecisionRequest.class.getRecordComponents()) {
            actual.put(rc.getName(), rc.getType());
        }
        assertThat(actual).containsExactlyEntriesOf(expected);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=AuthorizationContractShapeTest`
Expected: FAIL — record currently has 5 components, not 8.

- [ ] **Step 3: Add the three fields to the record**

Replace the record body in `AuthorizationDecisionRequest.java`:

```java
public record AuthorizationDecisionRequest(
    String partnerId,
    String schemaName,
    String pan,
    long amountMinor,
    String currency,
    String stan,                 // ISO 8583 DE11
    String terminalId,           // ISO 8583 DE41
    String transmissionDateTime  // ISO 8583 DE7 (MMDDhhmmss)
) {}
```

(Keep the existing class Javadoc; update the field list note if present.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=AuthorizationContractShapeTest`
Expected: PASS.

> The existing `AuthorizationDecisionTest` constructs this record positionally (5 args) at two spots and builds 5-field JSON bodies. Those are updated in Task 6 (same service, same compile unit). Do not run the full card suite until Task 6 is done — it will not compile until then. This is expected.

- [ ] **Step 5: Commit**

```bash
cd ~/nubbank-baas
git add baas-card/src/main/java/com/nubbank/baas/card/authorize/dto/AuthorizationDecisionRequest.java \
        baas-card/src/test/java/com/nubbank/baas/card/authorize/AuthorizationContractShapeTest.java
git commit -m "feat(card): authorize contract +stan/terminalId/transmissionDateTime (F3) + shape test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Rewrite AuthorizationDecisionService — scaling, currency limit, idempotency, env (F1, F2, F3, F5)

**Files:**
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/authorize/AuthorizationDecisionService.java`
- Modify: `baas-card/src/test/java/com/nubbank/baas/card/authorize/AuthorizationDecisionTest.java`

- [ ] **Step 1: Update the existing test for the new constructor + body shape, and add new behavior tests**

In `AuthorizationDecisionTest`:

(a) `authorizeBody(...)` must include the three new fields. Replace the helper:

```java
    private Map<String, Object> authorizeBody(TestPartner partner, String pan, long amountMinor,
                                              String currency) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("partnerId", partner.orgId.toString());
        body.put("schemaName", partner.schemaName);
        body.put("pan", pan);
        body.put("amountMinor", amountMinor);
        body.put("currency", currency);
        body.put("stan", "000001");
        body.put("terminalId", "TERM0001");
        body.put("transmissionDateTime", "0101120000");
        return body;
    }
```

(b) The two direct-construction tests build the service and the record with old arities. Update both:

- New service constructor adds `CurrencyMinorUnits` and `AuthorizationIdempotencyRepository`. Autowire them in the test class:

```java
    @Autowired private com.nubbank.baas.card.common.CurrencyMinorUnits currencyMinorUnits;
    @Autowired private AuthorizationIdempotencyRepository idempotencyRepository;
```

- In `decide_clearsPartnerContext_onNormalReturn`, replace the constructor + request:

```java
        AuthorizationDecisionService svc = new AuthorizationDecisionService(
            cardRepository, limitRepository, panHasher, currencyMinorUnits, idempotencyRepository);
        AuthorizationDecisionRequest req = new AuthorizationDecisionRequest(
            partner.orgId.toString(), partner.schemaName, "5060001234567890", 5000L, "566",
            "000001", "TERM0001", "0101120000");
```

- In `decide_clearsPartnerContext_evenWhenRepoThrows`, replace the constructor + request:

```java
        AuthorizationDecisionService svc = new AuthorizationDecisionService(
            throwingRepo, limitRepository, panHasher, currencyMinorUnits, idempotencyRepository);
        AuthorizationDecisionRequest req = new AuthorizationDecisionRequest(
            UUID.randomUUID().toString(), "partner_x", "5060001234567890", 5000L, "566",
            "000001", "TERM0001", "0101120000");
```

(c) Add new behavior tests:

```java
    // ---------- F1: decimal scaling per currency exponent ----------

    @Test
    void perTxnLimit_threeDecimalCurrency_scaledCorrectly() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-kwd");
        // KWD (414) has 3 minor digits. amountMinor 5000 → 5.000 major. Limit 4 → decline 61.
        setLimit(partner, ic.cardId, new BigDecimal("4"), "414");
        Map<String,Object> body = authorizeBody(partner, ic.pan, 5000, "414");
        ResponseEntity<Map> resp = hmacPost(body);
        assertThat(data(resp).get("responseCode")).isEqualTo("61");
    }

    @Test
    void unknownCurrency_declines12() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-badccy");
        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "999"));
        assertThat(data(resp).get("decision")).isEqualTo("DECLINE");
        assertThat(data(resp).get("responseCode")).isEqualTo("12");
    }

    // ---------- F2: currency-aware limit ----------

    @Test
    void perTxnLimit_currencyMismatch_declines57() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-ccymismatch");
        // Limit set in NGN(566); transaction in USD(840) → cannot compare → decline 57.
        setLimit(partner, ic.cardId, new BigDecimal("100000"), "566");
        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "840"));
        assertThat(data(resp).get("responseCode")).isEqualTo("57");
    }

    @Test
    void perTxnLimit_currencyMatch_enforced() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-ccymatch");
        // NGN limit 10 major; amountMinor 5000 → 50.00 NGN > 10 → decline 61.
        setLimit(partner, ic.cardId, new BigDecimal("10"), "566");
        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "566"));
        assertThat(data(resp).get("responseCode")).isEqualTo("61");
    }

    // ---------- F3: idempotency ----------

    @Test
    void retransmit_sameKey_returnsCachedDecision() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-idem");
        setLimit(partner, ic.cardId, new BigDecimal("25000"), "566");
        Map<String,Object> body = authorizeBody(partner, ic.pan, 5000, "566");

        ResponseEntity<Map> first = hmacPost(body);
        assertThat(data(first).get("responseCode")).isEqualTo("00");

        // Block the card, then replay the SAME (stan|terminal|dts). The cached APPROVE
        // must be returned — proving the decision was NOT recomputed.
        command(partner.jwt, ic.cardId, "block");
        ResponseEntity<Map> replay = hmacPost(body);
        assertThat(data(replay).get("responseCode"))
            .as("retransmit returns cached decision, not a fresh re-decide")
            .isEqualTo("00");
    }

    @Test
    void differentKey_sameCard_decidesFreshly() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-idem2");
        setLimit(partner, ic.cardId, new BigDecimal("25000"), "566");

        Map<String,Object> first = authorizeBody(partner, ic.pan, 5000, "566");
        assertThat(data(hmacPost(first)).get("responseCode")).isEqualTo("00");

        command(partner.jwt, ic.cardId, "block");
        Map<String,Object> second = authorizeBody(partner, ic.pan, 5000, "566");
        second.put("stan", "000002");   // different STAN → different key → fresh decide → 62
        assertThat(data(hmacPost(second)).get("responseCode")).isEqualTo("62");
    }
```

Add a `setLimit` helper (currency-aware variant of `setPerTxn`) and keep `setPerTxn` if other tests use it (or migrate them):

```java
    private void setLimit(TestPartner partner, UUID cardId, BigDecimal perTxn, String currency) {
        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "SANDBOX", "SANDBOX", "JWT", "test-user"));
            CardLimit limit = limitRepository.findByCardId(cardId)
                .orElseGet(() -> CardLimit.builder().cardId(cardId).build());
            limit.setPerTxn(perTxn);
            limit.setCurrencyCode(currency);
            limitRepository.save(limit);
        } finally {
            PartnerContext.clear();
        }
    }
```

Update existing `setPerTxn(...)` call sites to `setLimit(..., "566")` (NGN) so currency matches the `"566"` transactions they send (otherwise they would now decline 57).

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=AuthorizationDecisionTest`
Expected: FAIL (compile error: 5-arg constructor / record; new RC behaviors not implemented).

- [ ] **Step 3: Rewrite the service**

```java
package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionRequest;
import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionResponse;
import com.nubbank.baas.card.card.Card;
import com.nubbank.baas.card.card.CardRepository;
import com.nubbank.baas.card.card.CardStatus;
import com.nubbank.baas.card.card.PanHasher;
import com.nubbank.baas.card.common.CurrencyMinorUnits;
import com.nubbank.baas.card.limit.CardLimit;
import com.nubbank.baas.card.limit.CardLimitRepository;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Internal card-authorization-decision — FROZEN CROSS-TRACK CONTRACT §2a.
 *
 * <p>The FEP (tenant-less, over HMAC) resolved the tenant via BIN lookup and passes
 * {@code schemaName + PAN + amount + currency + ISO trace (stan/terminal/dts)}. This
 * service is the ONE place baas-card sets {@link PartnerContext} itself; the set is
 * paired with an UNCONDITIONAL {@code finally { PartnerContext.clear(); }} — a leaked
 * ThreadLocal would route the next pooled-thread request to the wrong tenant.
 *
 * <p>Hardening (Session 11):
 * <ul>
 *   <li>F5 — environment is derived from the schema prefix ({@code sandbox_…}→SANDBOX),
 *       not hardcoded.</li>
 *   <li>F3 — idempotent on {@code stan|terminalId|transmissionDateTime}: a retransmit
 *       returns the cached decision instead of re-deciding.</li>
 *   <li>F1 — the amount is scaled by the currency's real minor-unit exponent
 *       (JDK-derived); an unknown currency declines RC {@code 12}.</li>
 *   <li>F2 — the per-txn limit is enforced only when the limit currency equals the
 *       transaction currency; a mismatch (or null limit currency) declines RC {@code 57}.</li>
 * </ul>
 * Balance remains a STUB (always sufficient) — real balance via baas-engine in Phase 2.
 */
@Service
@RequiredArgsConstructor
public class AuthorizationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationDecisionService.class);

    private final CardRepository cardRepo;
    private final CardLimitRepository limitRepo;
    private final PanHasher panHasher;
    private final CurrencyMinorUnits currencyMinorUnits;
    private final AuthorizationIdempotencyRepository idempotencyRepo;

    public AuthorizationDecisionResponse decide(AuthorizationDecisionRequest req) {
        String environment =
            req.schemaName() != null && req.schemaName().startsWith("sandbox_")
                ? "SANDBOX" : "PRODUCTION";
        PartnerContext.set(new PartnerContext(
            req.partnerId(), req.schemaName(), "INTERNAL", environment, "INTERNAL", null));
        try {
            String idemKey = idemKey(req);

            // F3 — idempotent replay: a retransmit with the same key returns the cached
            // decision without re-deciding.
            if (idemKey != null) {
                Optional<AuthorizationIdempotency> cached = idempotencyRepo.findByIdemKey(idemKey);
                if (cached.isPresent()) {
                    AuthorizationIdempotency e = cached.get();
                    return new AuthorizationDecisionResponse(
                        e.getDecision(), e.getResponseCode(), e.getMessage());
                }
            }

            AuthorizationDecisionResponse decision = computeDecision(req);

            if (idemKey != null) {
                decision = persistOrReuse(idemKey, decision);
            }
            return decision;
        } finally {
            PartnerContext.clear();
        }
    }

    /** The core RC mapping (unchanged states) + F1 scaling + F2 currency-aware limit. */
    private AuthorizationDecisionResponse computeDecision(AuthorizationDecisionRequest req) {
        // F1 — resolve the currency exponent first; unknown currency fails loud (RC 12).
        Optional<Integer> exponent = currencyMinorUnits.exponentFor(req.currency());
        if (exponent.isEmpty()) {
            return decline("12", "Unknown currency");   // invalid transaction
        }

        Card card = cardRepo.findByPanHash(panHasher.hash(req.pan())).orElse(null);
        if (card == null) {
            return decline("56", "No such card");
        }
        if (card.getStatus() == CardStatus.BLOCKED || card.getStatus() == CardStatus.CANCELLED) {
            return decline("62", "Restricted");
        }
        if (card.getStatus() != CardStatus.ACTIVE) {
            return decline("54", "Card not usable");
        }

        BigDecimal amount = new BigDecimal(req.amountMinor()).movePointLeft(exponent.get());
        CardLimit lim = limitRepo.findByCardId(card.getId()).orElse(null);
        if (lim != null && lim.getPerTxn() != null) {
            // F2 — only enforce when the limit's currency matches the txn currency.
            if (!req.currency().equals(lim.getCurrencyCode())) {
                return decline("57", "Limit currency mismatch");   // txn not permitted
            }
            if (amount.compareTo(lim.getPerTxn()) > 0) {
                return decline("61", "Exceeds per-txn limit");
            }
        }

        // Phase 1C: balance is a stub (always sufficient).
        return approve();
    }

    /**
     * Persist the decision under {@code idemKey}; on the unique-constraint race
     * (concurrent first-time auth), re-read and return the winning row's decision.
     */
    private AuthorizationDecisionResponse persistOrReuse(
            String idemKey, AuthorizationDecisionResponse decision) {
        try {
            idempotencyRepo.save(AuthorizationIdempotency.builder()
                .idemKey(idemKey)
                .decision(decision.decision())
                .responseCode(decision.responseCode())
                .message(decision.message())
                .reversed(false)
                .build());
            return decision;
        } catch (DataIntegrityViolationException race) {
            return idempotencyRepo.findByIdemKey(idemKey)
                .map(e -> new AuthorizationDecisionResponse(
                    e.getDecision(), e.getResponseCode(), e.getMessage()))
                .orElse(decision);
        }
    }

    /** Compose the idempotency key, or null when any ISO trace field is absent. */
    private static String idemKey(AuthorizationDecisionRequest req) {
        if (isBlank(req.stan()) || isBlank(req.terminalId()) || isBlank(req.transmissionDateTime())) {
            return null;
        }
        return req.stan() + "|" + req.terminalId() + "|" + req.transmissionDateTime();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private AuthorizationDecisionResponse approve() {
        log.debug("Authorization decision: APPROVE rc=00");
        return new AuthorizationDecisionResponse("APPROVE", "00", "Approved");
    }

    private AuthorizationDecisionResponse decline(String responseCode, String message) {
        log.debug("Authorization decision: DECLINE rc={}", responseCode);
        return new AuthorizationDecisionResponse("DECLINE", responseCode, message);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=AuthorizationDecisionTest`
Expected: PASS (existing + new RC12/RC57/RC61/idempotency/scaling tests).

- [ ] **Step 5: Run the full card suite (catches the contract change ripples)**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test`
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 6: Commit**

```bash
cd ~/nubbank-baas
git add baas-card/src/main/java/com/nubbank/baas/card/authorize/AuthorizationDecisionService.java \
        baas-card/src/test/java/com/nubbank/baas/card/authorize/AuthorizationDecisionTest.java
git commit -m "feat(card): authorize scaling(F1)+currency-limit(F2)+idempotency(F3)+env(F5)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Reconcile internal HMAC replay window to 60s (F4)

**Files:**
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/config/InternalServiceAuthFilter.java:54`

- [ ] **Step 1: Change the constant**

Replace:

```java
    private static final long MAX_SKEW_SECONDS = 300; // 5 minutes
```

with:

```java
    private static final long MAX_SKEW_SECONDS = 60; // 1 minute — matches engine signer
```

- [ ] **Step 2: Run the internal-auth tests**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=AuthorizationDecisionTest`
Expected: PASS — the HMAC test signs with `Instant.now()`, well within 60s.

- [ ] **Step 3: Commit**

```bash
cd ~/nubbank-baas
git add baas-card/src/main/java/com/nubbank/baas/card/config/InternalServiceAuthFilter.java
git commit -m "fix(card): reconcile internal HMAC replay window 300s->60s (F4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: BIN range-end coverage (F7)

**Files:**
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/bin/BinService.java`
- Test: `baas-card/src/test/java/com/nubbank/baas/card/bin/BinLookupTest.java` (extend)

- [ ] **Step 1: Write the failing test (a single 6-digit BIN covers its full sub-range)**

Add to `BinLookupTest` (it already provisions a partner + registers ranges; mirror its existing register/lookup helpers):

```java
    @Test
    void sixDigitBin_registeredStartEqualsEnd_coversFullSubRange() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        // Register a single 6-digit BIN as start == end.
        registerBin(partner.jwt, "506000", "506000", "VERVE");

        // A real 16-digit PAN beginning 506000 → first-8 = "50600012" must resolve.
        var route = binService.lookup("5060001234567890");
        assertThat(route).isPresent();
        assertThat(route.get().getSchemaName()).isEqualTo(partner.schemaName);
    }
```

> If `BinLookupTest` accesses `BinService` directly via `@Autowired BinService binService` and a `registerBin(...)` helper that POSTs to `/baas/v1/bins`, reuse them. If it tests lookup via the internal endpoint instead, register via the POST helper and assert the `GET /internal/v1/bins/50600012` returns the route. Either form proves the same thing: a 6-digit start==end registration covers `5060001234…`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=BinLookupTest`
Expected: FAIL — `register` currently stores end as `50600000` (pad-0), so `50600012` is out of `[50600000, 50600000]`.

- [ ] **Step 3: Add `normalizeRangeEnd` and use it in `register`**

In `BinService.java`, add after `normalize(...)`:

```java
    /**
     * FROZEN lookup {@link #normalize} pads the digit head with {@code '0'}; a range
     * END must instead pad with {@code '9'} so a short BIN covers its FULL sub-range.
     * e.g. normalizeRangeEnd("506000") = "50600099" — so a single 6-digit BIN
     * registered as start==end covers every PAN beginning 506000 (first-8 in
     * [50600000, 50600099]).
     */
    static String normalizeRangeEnd(String bin) {
        String digits = bin == null ? "" : bin.replaceAll("\\D", "");
        String head = digits.length() >= 8 ? digits.substring(0, 8) : digits;
        return String.format("%-8s", head).replace(' ', '9');
    }
```

In `register(...)`, change the end normalization. Replace:

```java
        if (normalize(binStart).compareTo(normalize(binEnd)) > 0) {
            throw BaasException.badRequest("INVALID_BIN_RANGE", "bin_start must be <= bin_end");
        }
        return repo.save(CardBinRange.builder()
            .binStart(normalize(binStart))
            .binEnd(normalize(binEnd))
```

with:

```java
        String start = normalize(binStart);
        String end = normalizeRangeEnd(binEnd);
        if (start.compareTo(end) > 0) {
            throw BaasException.badRequest("INVALID_BIN_RANGE", "bin_start must be <= bin_end");
        }
        return repo.save(CardBinRange.builder()
            .binStart(start)
            .binEnd(end)
```

> NOTE: existing `BinRegistrationTest` asserts `binEnd` of `"412399"` is `"41239900"` and `"510099"` is `"51009900"`. With pad-9 these become `"41239999"` and `"51009999"`. Update those two assertions in `BinRegistrationTest` to the pad-9 values, and the inverted-range test (`"510099"` start / `"510000"` end) still throws 400 because `normalize("510099")="51009900" > normalizeRangeEnd("510000")="51000099"`.

- [ ] **Step 4: Update `BinRegistrationTest` expectations**

In `BinRegistrationTest`:
- `register_persistsRange_withContextDerivedTenant`: change `assertThat(data.get("binEnd")).isEqualTo("41239900");` → `isEqualTo("41239999");`
- `list_returnsOnlyCurrentPartnersRanges`: no binEnd assertion to change (only asserts binStart). Leave as-is.

- [ ] **Step 5: Run both BIN tests to verify they pass**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=BinLookupTest,BinRegistrationTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd ~/nubbank-baas
git add baas-card/src/main/java/com/nubbank/baas/card/bin/BinService.java \
        baas-card/src/test/java/com/nubbank/baas/card/bin/BinLookupTest.java \
        baas-card/src/test/java/com/nubbank/baas/card/bin/BinRegistrationTest.java
git commit -m "fix(card): BIN range-end pads with 9 so short BINs cover full sub-range (F7)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Card reversal endpoint (F6 — card side)

**Files:**
- Create: `baas-card/src/main/java/com/nubbank/baas/card/authorize/dto/ReversalRequest.java`
- Create: `baas-card/src/main/java/com/nubbank/baas/card/authorize/dto/ReversalResponse.java`
- Create: `baas-card/src/main/java/com/nubbank/baas/card/authorize/ReversalService.java`
- Create: `baas-card/src/main/java/com/nubbank/baas/card/authorize/InternalReversalController.java`
- Test: `baas-card/src/test/java/com/nubbank/baas/card/authorize/ReversalServiceTest.java`

- [ ] **Step 1: Create the DTOs**

`ReversalRequest.java`:

```java
package com.nubbank.baas.card.authorize.dto;

/**
 * Internal reversal lookup — FROZEN CROSS-TRACK CONTRACT (F6). The FEP composes the
 * ORIGINAL authorization's idempotency key from DE90 (original STAN + original
 * transmission date-time) and DE41 (terminal), and asks card to mark it reversed.
 */
public record ReversalRequest(
    String partnerId,
    String schemaName,
    String originalStan,
    String terminalId,
    String originalTransmissionDateTime
) {}
```

`ReversalResponse.java`:

```java
package com.nubbank.baas.card.authorize.dto;

/**
 * Result of a reversal lookup: whether the original authorization was located (and is
 * now marked reversed). The FEP maps {@code located=true} → RC 00, false → RC 25.
 */
public record ReversalResponse(boolean located) {}
```

- [ ] **Step 2: Write the failing service test**

```java
package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.AbstractCardIntegrationTest;
import com.nubbank.baas.card.auth.PartnerJwtService;
import com.nubbank.baas.card.authorize.dto.ReversalRequest;
import com.nubbank.baas.card.authorize.dto.ReversalResponse;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.support.TestPartner;
import com.nubbank.baas.card.tenant.PartnerContext;
import com.nubbank.baas.card.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalServiceTest extends AbstractCardIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;
    @Autowired private AuthorizationIdempotencyRepository idemRepo;
    @Autowired private ReversalService reversalService;

    @Test
    void reverse_locatesOriginal_marksReversed() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        seedOriginal(partner, "000001", "TERM0001", "0101120000");

        ReversalResponse resp = reversalService.reverse(new ReversalRequest(
            partner.orgId.toString(), partner.schemaName, "000001", "TERM0001", "0101120000"));

        assertThat(resp.located()).isTrue();
        // The row is now reversed.
        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "INTERNAL", "PRODUCTION", "INTERNAL", null));
            assertThat(idemRepo.findByIdemKey("000001|TERM0001|0101120000").get().isReversed()).isTrue();
        } finally {
            PartnerContext.clear();
        }
    }

    @Test
    void reverse_noOriginal_returnsNotLocated() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        ReversalResponse resp = reversalService.reverse(new ReversalRequest(
            partner.orgId.toString(), partner.schemaName, "999999", "TERM0001", "0101120000"));
        assertThat(resp.located()).isFalse();
    }

    @Test
    void reverse_alreadyReversed_isIdempotentTrue() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        seedOriginal(partner, "000002", "TERM0001", "0101120000");
        ReversalRequest req = new ReversalRequest(
            partner.orgId.toString(), partner.schemaName, "000002", "TERM0001", "0101120000");
        assertThat(reversalService.reverse(req).located()).isTrue();
        assertThat(reversalService.reverse(req).located()).isTrue();  // idempotent
    }

    @Test
    void reverse_clearsContext() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        assertThat(PartnerContext.get()).isNull();
        reversalService.reverse(new ReversalRequest(
            partner.orgId.toString(), partner.schemaName, "777", "T", "0101120000"));
        assertThat(PartnerContext.get()).isNull();
    }

    private void seedOriginal(TestPartner partner, String stan, String term, String dts) {
        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "INTERNAL", "PRODUCTION", "INTERNAL", null));
            idemRepo.save(AuthorizationIdempotency.builder()
                .idemKey(stan + "|" + term + "|" + dts)
                .decision("APPROVE").responseCode("00").message("Approved").reversed(false).build());
        } finally {
            PartnerContext.clear();
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=ReversalServiceTest`
Expected: FAIL — `ReversalService` does not exist.

- [ ] **Step 4: Create the service**

```java
package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.authorize.dto.ReversalRequest;
import com.nubbank.baas.card.authorize.dto.ReversalResponse;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Locates the ORIGINAL authorization (by its idempotency key) and marks it reversed (F6).
 *
 * <p>Phase 1C does NOT move funds — the balance check is still a stub (DEF-1C-23), so a
 * reversal only flips {@code reversed=true} on the original idempotency row and reports
 * whether the original existed. Actual fund-reversal rides Phase 2 with the balance
 * wiring. This removes the prior defect (the FEP blanket-approved reversals for
 * transactions that never happened).
 *
 * <p>Same tenant-context discipline as {@link AuthorizationDecisionService}: set from the
 * request's {@code schemaName}, ALWAYS cleared in {@code finally}.
 */
@Service
@RequiredArgsConstructor
public class ReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

    private final AuthorizationIdempotencyRepository idempotencyRepo;

    @Transactional
    public ReversalResponse reverse(ReversalRequest req) {
        String environment =
            req.schemaName() != null && req.schemaName().startsWith("sandbox_")
                ? "SANDBOX" : "PRODUCTION";
        PartnerContext.set(new PartnerContext(
            req.partnerId(), req.schemaName(), "INTERNAL", environment, "INTERNAL", null));
        try {
            String key = req.originalStan() + "|" + req.terminalId()
                + "|" + req.originalTransmissionDateTime();
            Optional<AuthorizationIdempotency> original = idempotencyRepo.findByIdemKey(key);
            if (original.isEmpty()) {
                log.debug("Reversal: original not located rc=25");
                return new ReversalResponse(false);
            }
            AuthorizationIdempotency row = original.get();
            if (!row.isReversed()) {
                row.setReversed(true);
                idempotencyRepo.save(row);
            }
            log.debug("Reversal: original located + marked reversed rc=00");
            return new ReversalResponse(true);
        } finally {
            PartnerContext.clear();
        }
    }
}
```

- [ ] **Step 5: Create the controller**

```java
package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.authorize.dto.ReversalRequest;
import com.nubbank.baas.card.authorize.dto.ReversalResponse;
import com.nubbank.baas.card.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal (service-to-service) reversal endpoint (F6). {@code POST /internal/v1/reversal}.
 *
 * <p>No auth annotation: the {@code @Order(1)} internal chain runs
 * {@code InternalServiceAuthFilter} (inbound HMAC) which 401s any unsigned call before
 * it reaches here. {@link ReversalService} sets/clears the tenant context.
 */
@RestController
@RequestMapping("/internal/v1/reversal")
@RequiredArgsConstructor
public class InternalReversalController {

    private final ReversalService reversalService;

    @PostMapping
    public ApiResponse<ReversalResponse> reverse(@RequestBody ReversalRequest request) {
        return ApiResponse.ok(reversalService.reverse(request));
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test -Dtest=ReversalServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Run the full card suite**

Run: `cd ~/nubbank-baas/baas-card && ./mvnw -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
cd ~/nubbank-baas
git add baas-card/src/main/java/com/nubbank/baas/card/authorize/
git add baas-card/src/test/java/com/nubbank/baas/card/authorize/ReversalServiceTest.java
git commit -m "feat(card): internal reversal endpoint — locate original + mark reversed (F6)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: FEP authorize contract +3 fields & forward ISO trace (F3 — fep side)

**Files:**
- Modify: `baas-fep/src/main/java/com/nubbank/baas/fep/routing/AuthorizationDecision.java`
- Modify: `baas-fep/src/main/java/com/nubbank/baas/fep/router/AuthorizationHandler.java`
- Test: `baas-fep/src/test/java/com/nubbank/baas/fep/router/AuthorizationContractShapeTest.java` (create)
- Test: `baas-fep/src/test/java/com/nubbank/baas/fep/router/AuthorizationHandlerTest.java` (extend)

- [ ] **Step 1: Write the failing shape test (same canonical list as card Task 5)**

```java
package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.routing.AuthorizationDecision;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the FROZEN §2a authorize contract on the FEP side. Must match
 * {@code baas-card}'s {@code AuthorizationContractShapeTest} (separate modules — the
 * canonical list in {@code docs/contracts/phase1c-interfaces.md} §2a keeps them in sync).
 */
class AuthorizationContractShapeTest {

    @Test
    void requestComponents_matchCanonicalContract() {
        Map<String, Class<?>> expected = new LinkedHashMap<>();
        expected.put("partnerId", String.class);
        expected.put("schemaName", String.class);
        expected.put("pan", String.class);
        expected.put("amountMinor", long.class);
        expected.put("currency", String.class);
        expected.put("stan", String.class);
        expected.put("terminalId", String.class);
        expected.put("transmissionDateTime", String.class);

        Map<String, Class<?>> actual = new LinkedHashMap<>();
        for (RecordComponent rc : AuthorizationDecision.Request.class.getRecordComponents()) {
            actual.put(rc.getName(), rc.getType());
        }
        assertThat(actual).containsExactlyEntriesOf(expected);
    }
}
```

- [ ] **Step 2: Add a forwarding assertion to `AuthorizationHandlerTest`**

Extend `knownBin_cardApproves_forwardsCorrectRequestToCard` (or add a sibling test) to assert the new fields are forwarded:

```java
        assertThat(captured.stan()).isEqualTo("000001");
        assertThat(captured.terminalId()).isEqualTo("TERM0001");
        assertThat(captured.transmissionDateTime()).isEqualTo("0101120000");
```

(The `buildRequest` helper already sets DE11=`000001`, DE41=`TERM0001`, DE7=`0101120000`.)

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd ~/nubbank-baas/baas-fep && ./mvnw -B test -Dtest=AuthorizationContractShapeTest,AuthorizationHandlerTest`
Expected: FAIL — `Request` has 5 components; `captured.stan()` does not compile.

- [ ] **Step 4: Add the three fields to `AuthorizationDecision.Request`**

In `AuthorizationDecision.java`, replace the nested `Request` record:

```java
    public record Request(
        String partnerId,
        String schemaName,
        String pan,
        long   amountMinor,
        String currency,
        String stan,                 // DE11
        String terminalId,           // DE41
        String transmissionDateTime  // DE7 (MMDDhhmmss)
    ) {
        @Override
        public String toString() {
            return "Request[partnerId=" + partnerId
                + ", schemaName=" + schemaName
                + ", pan=****" + (pan != null && pan.length() >= 4 ? pan.substring(pan.length() - 4) : "****")
                + ", amountMinor=" + amountMinor
                + ", currency=" + currency
                + ", stan=" + stan
                + ", terminalId=" + terminalId
                + ", transmissionDateTime=" + transmissionDateTime + "]";
        }
    }
```

- [ ] **Step 5: Forward DE11/DE41/DE7 in `AuthorizationHandler.handle`**

Replace the `cardClient.authorize(new AuthorizationDecision.Request(...))` construction:

```java
        AuthorizationDecision decision = cardClient.authorize(new AuthorizationDecision.Request(
            route.get().partnerId().toString(),
            route.get().schemaName(),
            pan,                          // forwarded to Card — NEVER logged here
            amount,
            field(req, IsoField.CURRENCY),
            field(req, IsoField.STAN),
            field(req, IsoField.TERMINAL_ID),
            field(req, IsoField.TRANSMISSION_DTS)
        ));
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd ~/nubbank-baas/baas-fep && ./mvnw -B test -Dtest=AuthorizationContractShapeTest,AuthorizationHandlerTest`
Expected: PASS.

> The `HttpCardClientTest` constructs `AuthorizationDecision.Request` positionally — if it does, update those constructions to 8 args (add `"000001","TERM0001","0101120000"`). Run the full fep suite in Step 7 to catch it.

- [ ] **Step 7: Run the full fep suite**

Run: `cd ~/nubbank-baas/baas-fep && ./mvnw -B test`
Expected: BUILD SUCCESS. If `HttpCardClientTest` fails to compile, fix its `Request(...)` constructions to 8 args, then re-run.

- [ ] **Step 8: Commit**

```bash
cd ~/nubbank-baas
git add baas-fep/src/main/java/com/nubbank/baas/fep/routing/AuthorizationDecision.java \
        baas-fep/src/main/java/com/nubbank/baas/fep/router/AuthorizationHandler.java \
        baas-fep/src/test/java/com/nubbank/baas/fep/router/AuthorizationContractShapeTest.java \
        baas-fep/src/test/java/com/nubbank/baas/fep/router/AuthorizationHandlerTest.java
# include HttpCardClientTest if edited:
git add baas-fep/src/test/java/com/nubbank/baas/fep/client/HttpCardClientTest.java 2>/dev/null || true
git commit -m "feat(fep): authorize contract +stan/terminalId/transmissionDateTime, forward DE11/41/7 (F3)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: FEP DE90 + reversal wiring (F6 — fep side)

**Files:**
- Modify: `baas-fep/src/main/java/com/nubbank/baas/fep/iso/IsoField.java`
- Modify: `baas-fep/src/main/resources/iso8583-1987-fields.xml`
- Create: `baas-fep/src/main/java/com/nubbank/baas/fep/routing/ReversalDecision.java`
- Modify: `baas-fep/src/main/java/com/nubbank/baas/fep/routing/CardClient.java`
- Modify: `baas-fep/src/main/java/com/nubbank/baas/fep/client/HttpCardClient.java`
- Modify: `baas-fep/src/main/java/com/nubbank/baas/fep/router/ReversalHandler.java`
- Modify: `baas-fep/src/test/java/com/nubbank/baas/fep/support/StubCardClient.java`
- Test: `baas-fep/src/test/java/com/nubbank/baas/fep/router/ReversalHandlerTest.java` (create)
- Modify: `baas-fep/src/test/java/com/nubbank/baas/fep/router/AuthorizationHandlerTest.java` (the existing `reversalHandler_stub_*` test changes — see Step 8)

- [ ] **Step 1: Add the DE90 field constant**

In `IsoField.java`, add after `NETWORK_MGMT_CODE`:

```java
    public static final int ORIGINAL_DATA      = 90;
```

- [ ] **Step 2: Add DE90 to the packager XML**

In `iso8583-1987-fields.xml`, add before `</isopackager>`:

```xml
  <isofield id="90" length="42" name="ORIGINAL DATA ELEMENTS"  class="org.jpos.iso.IFA_NUMERIC"/>
```

> DE90 layout (42 numeric): origMTI(4) + origStan(6) + origDateTime(10) + acquirerId(11) + forwarderId(11). The handler reads chars 5–10 (orig STAN) and 11–20 (orig transmission date-time).

- [ ] **Step 3: Create the `ReversalDecision` DTO**

```java
package com.nubbank.baas.fep.routing;

/**
 * Result of a reversal call to the Card service (F6).
 *
 * @param located whether the original authorization was found (and is now reversed).
 *                The handler maps {@code true} → RC 00, {@code false} → RC 25.
 *
 * <p>Request DTO sent to {@code POST /internal/v1/reversal}.
 */
public record ReversalDecision(boolean located) {

    public record Request(
        String partnerId,
        String schemaName,
        String originalStan,
        String terminalId,
        String originalTransmissionDateTime
    ) {}
}
```

- [ ] **Step 4: Add `reverse(...)` to the `CardClient` interface**

In `CardClient.java`, add:

```java
    /**
     * Asks Card to locate and reverse the original authorization (F6).
     *
     * @param req original-transaction identifiers composed from DE90 + DE41.
     * @return {@code located=true} if the original was found (and reversed); fail-closed
     *         to {@code located=false} on any transport error so the Netty thread is
     *         never interrupted.
     */
    ReversalDecision reverse(ReversalDecision.Request req);
```

- [ ] **Step 5: Implement `reverse(...)` in `HttpCardClient`**

In `HttpCardClient.java`, add a fail-closed constant and method:

```java
    private static final ReversalDecision REVERSAL_NOT_LOCATED = new ReversalDecision(false);

    @Override
    public ReversalDecision reverse(ReversalDecision.Request req) {
        log.debug("Reversing partnerId={} originalStan={} terminal={}",
            req.partnerId(), req.originalStan(), req.terminalId());
        String url = baseUrl + "/internal/v1/reversal";
        try {
            ResponseEntity<ApiResponse<ReversalDecision>> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(req),
                new ParameterizedTypeReference<ApiResponse<ReversalDecision>>() {});
            ApiResponse<ReversalDecision> body = response.getBody();
            if (body != null && body.data() != null) {
                return body.data();
            }
            log.warn("Card reversal returned 2xx but empty data for partnerId={}", req.partnerId());
            return REVERSAL_NOT_LOCATED;
        } catch (RestClientException e) {
            log.warn("Reversal failed for partnerId={} — Card service error: {}",
                req.partnerId(), e.getMessage());
            return REVERSAL_NOT_LOCATED;
        }
    }
```

Add imports if missing: `com.nubbank.baas.fep.routing.ReversalDecision`.

- [ ] **Step 6: Implement `reverse(...)` in `StubCardClient`**

In `StubCardClient.java`, add:

```java
    private ReversalDecision reversalResponse = new ReversalDecision(true);
    private com.nubbank.baas.fep.routing.ReversalDecision.Request lastReversalRequest;

    @Override
    public com.nubbank.baas.fep.routing.ReversalDecision reverse(
            com.nubbank.baas.fep.routing.ReversalDecision.Request req) {
        this.lastReversalRequest = req;
        return reversalResponse;
    }

    public StubCardClient withReversalResponse(com.nubbank.baas.fep.routing.ReversalDecision decision) {
        this.reversalResponse = decision;
        return this;
    }

    public com.nubbank.baas.fep.routing.ReversalDecision.Request lastReversalRequest() {
        return lastReversalRequest;
    }
```

(Add the import `com.nubbank.baas.fep.routing.ReversalDecision` at the top, or use the fully-qualified names as above.)

- [ ] **Step 7: Rewrite `ReversalHandler`**

```java
package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import com.nubbank.baas.fep.routing.BinResolver;
import com.nubbank.baas.fep.routing.CardClient;
import com.nubbank.baas.fep.routing.PartnerRoute;
import com.nubbank.baas.fep.routing.ReversalDecision;
import lombok.RequiredArgsConstructor;
import org.jpos.iso.ISOMsg;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Handles ISO 8583 Reversal Requests (MTI {@code 0400}) — F6.
 *
 * <p>Resolves the partner via DE2→BIN, parses DE90 (Original Data Elements) for the
 * original STAN + transmission date-time, and asks Card to locate + reverse the original
 * authorization.
 * <ul>
 *   <li>unrouteable BIN → RC {@code 91}, DE2 omitted (PAN never echoed).</li>
 *   <li>DE90 missing/short → RC {@code 30} (format error), no Card call.</li>
 *   <li>original located → RC {@code 00}.</li>
 *   <li>original not located → RC {@code 25} (unable to locate original).</li>
 *   <li>transport error → RC {@code 96} via fail-closed CardClient (located=false → 25?
 *       no — see note): the client returns located=false, mapped to RC 25.</li>
 * </ul>
 *
 * <p>Phase 1C reverses no funds (balance is stubbed); this only flips the original's
 * reversed flag and removes the prior blanket-approve defect.
 *
 * <p>PAN safety: DE2 is read only for routing and is NEVER echoed on the response.
 */
@Component
@RequiredArgsConstructor
public class ReversalHandler {

    private final BinResolver binResolver;
    private final CardClient  cardClient;
    private final IsoMessageFactory iso;

    public ISOMsg handle(ISOMsg req) {
        String pan = field(req, IsoField.PAN);
        Optional<PartnerRoute> route = binResolver.resolve(pan);
        if (route.isEmpty()) {
            return respond(req, "91");   // unrouteable — DE2 omitted
        }

        String de90 = field(req, IsoField.ORIGINAL_DATA);
        if (de90 == null || de90.length() < 20) {
            return respond(req, "30");   // format error — cannot identify the original
        }
        String originalStan = de90.substring(4, 10);
        String originalDts  = de90.substring(10, 20);
        String terminalId   = field(req, IsoField.TERMINAL_ID);

        ReversalDecision decision = cardClient.reverse(new ReversalDecision.Request(
            route.get().partnerId().toString(),
            route.get().schemaName(),
            originalStan,
            terminalId,
            originalDts));

        return respond(req, decision.located() ? "00" : "25");
    }

    private ISOMsg respond(ISOMsg req, String rc) {
        ISOMsg resp = iso.create(MessageRouter.responseMti(MessageRouter.mti(req)));
        if (req.hasField(IsoField.STAN)) {
            resp.set(IsoField.STAN, req.getString(IsoField.STAN));
        }
        if (req.hasField(IsoField.TRANSMISSION_DTS)) {
            resp.set(IsoField.TRANSMISSION_DTS, req.getString(IsoField.TRANSMISSION_DTS));
        }
        MessageRouter.set(resp, IsoField.RESPONSE_CODE, rc);
        return resp;
    }

    private static String field(ISOMsg m, int field) {
        return m.hasField(field) ? m.getString(field) : null;
    }
}
```

> NOTE: `MessageRouter` already constructs `ReversalHandler` via Spring DI; its constructor signature changes from `(IsoMessageFactory)` to `(BinResolver, CardClient, IsoMessageFactory)`. Spring injects all three automatically — no change to `MessageRouter` needed.

- [ ] **Step 8: Update the old reversal stub test, then write the new `ReversalHandlerTest`**

(a) The existing `AuthorizationHandlerTest.reversalHandler_stub_approves_echoesStanAndDts` constructs `new ReversalHandler(iso)` and expects RC 00 with no DE90. That constructor no longer exists. DELETE that test method from `AuthorizationHandlerTest` (reversal now has its own test class).

(b) Create `ReversalHandlerTest`:

```java
package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import com.nubbank.baas.fep.routing.BinResolver;
import com.nubbank.baas.fep.routing.PartnerRoute;
import com.nubbank.baas.fep.routing.ReversalDecision;
import com.nubbank.baas.fep.support.StubCardClient;
import org.jpos.iso.ISOMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalHandlerTest {

    private static final String KNOWN_PAN = "5060001234567890";
    private static final String KNOWN_BIN = "50600012";
    private static final String UNKNOWN_PAN = "9999990000000000";
    private static final UUID PARTNER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String SCHEMA = "partner_acme";

    private IsoMessageFactory iso;
    private StubCardClient stub;
    private ReversalHandler handler;

    @BeforeEach
    void setUp() {
        iso = new IsoMessageFactory();
        stub = new StubCardClient();
        stub.register(KNOWN_BIN, new PartnerRoute(PARTNER_ID, SCHEMA));
        handler = new ReversalHandler(new BinResolver(stub), stub, iso);
    }

    /** DE90 = origMTI(0100) + origStan(000001) + origDateTime(0101120000) + acquirer/forwarder. */
    private ISOMsg build0400(String pan, String de90) {
        ISOMsg req = iso.create("0400");
        req.set(IsoField.PAN, pan);
        req.set(IsoField.STAN, "000009");
        req.set(IsoField.TRANSMISSION_DTS, "0101130000");
        req.set(IsoField.TERMINAL_ID, "TERM0001");
        if (de90 != null) req.set(IsoField.ORIGINAL_DATA, de90);
        return req;
    }

    private static final String DE90 =
        "0100" + "000001" + "0101120000" + "00000000000" + "00000000000"; // 42 digits

    @Test
    void originalLocated_returns0410_rc00() throws Exception {
        stub.withReversalResponse(new ReversalDecision(true));
        ISOMsg resp = handler.handle(build0400(KNOWN_PAN, DE90));
        assertThat(resp.getMTI()).isEqualTo("0410");
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("00");
        assertThat(resp.hasField(IsoField.PAN)).isFalse();
        // forwarded the original STAN + DTS parsed from DE90
        assertThat(stub.lastReversalRequest().originalStan()).isEqualTo("000001");
        assertThat(stub.lastReversalRequest().originalTransmissionDateTime()).isEqualTo("0101120000");
        assertThat(stub.lastReversalRequest().terminalId()).isEqualTo("TERM0001");
    }

    @Test
    void originalNotLocated_returns0410_rc25() throws Exception {
        stub.withReversalResponse(new ReversalDecision(false));
        ISOMsg resp = handler.handle(build0400(KNOWN_PAN, DE90));
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("25");
    }

    @Test
    void unknownBin_returns0410_rc91_noPan() throws Exception {
        ISOMsg resp = handler.handle(build0400(UNKNOWN_PAN, DE90));
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("91");
        assertThat(resp.hasField(IsoField.PAN)).isFalse();
        assertThat(stub.lastReversalRequest()).isNull();   // no Card call
    }

    @Test
    void missingDe90_returns0410_rc30_noCardCall() throws Exception {
        ISOMsg resp = handler.handle(build0400(KNOWN_PAN, null));
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("30");
        assertThat(stub.lastReversalRequest()).isNull();
    }
}
```

- [ ] **Step 9: Run the reversal + handler tests**

Run: `cd ~/nubbank-baas/baas-fep && ./mvnw -B test -Dtest=ReversalHandlerTest,AuthorizationHandlerTest`
Expected: PASS.

- [ ] **Step 10: Run the full fep suite (catches packager + DI changes)**

Run: `cd ~/nubbank-baas/baas-fep && ./mvnw -B test`
Expected: BUILD SUCCESS. (If `FepTcpServerLoopbackTest` or `IsoMessageFactoryTest` reference the packager, they should still pass — DE90 is additive.)

- [ ] **Step 11: Commit**

```bash
cd ~/nubbank-baas
git add baas-fep/src/main/java/com/nubbank/baas/fep/iso/IsoField.java \
        baas-fep/src/main/resources/iso8583-1987-fields.xml \
        baas-fep/src/main/java/com/nubbank/baas/fep/routing/ReversalDecision.java \
        baas-fep/src/main/java/com/nubbank/baas/fep/routing/CardClient.java \
        baas-fep/src/main/java/com/nubbank/baas/fep/client/HttpCardClient.java \
        baas-fep/src/main/java/com/nubbank/baas/fep/router/ReversalHandler.java \
        baas-fep/src/test/java/com/nubbank/baas/fep/support/StubCardClient.java \
        baas-fep/src/test/java/com/nubbank/baas/fep/router/ReversalHandlerTest.java \
        baas-fep/src/test/java/com/nubbank/baas/fep/router/AuthorizationHandlerTest.java
git commit -m "feat(fep): DE90 + reversal wiring to card — RC00 located / RC25 not (F6)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: FEP HMAC raw-path signing (F8)

**Files:**
- Modify: `baas-fep/src/main/java/com/nubbank/baas/fep/config/CardClientConfig.java:62`
- Test: `baas-fep/src/test/java/com/nubbank/baas/fep/config/SigningInterceptorPathTest.java` (create)

- [ ] **Step 1: Write the failing test (raw path is signed)**

```java
package com.nubbank.baas.fep.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F8: the signer must sign the RAW (un-decoded) path so it matches the card validator's
 * {@code getRequestURI()}. This test signs a request whose path has an encoded segment
 * and asserts the signature is computed over the raw path, not the decoded one.
 */
class SigningInterceptorPathTest {

    private static final String SECRET = "test-shared-secret-min-32-chars-long-okay";

    @Test
    void signsRawPath_notDecodedPath() throws Exception {
        var interceptor = new CardClientConfig.SigningInterceptor(SECRET);
        // A path segment with a percent-encoded space (%20). Raw path keeps %20;
        // decoded path would turn it into a literal space.
        URI uri = URI.create("http://card:8081/internal/v1/bins/50%20600");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, uri);
        byte[] body = new byte[0];

        ClientHttpRequestExecution noop = (req, b) -> (ClientHttpResponse) null;
        interceptor.intercept(request, body, noop);

        String ts = request.getHeaders().getFirst("X-Internal-Timestamp");
        String rawPath = uri.getRawPath();   // "/internal/v1/bins/50%20600"
        String emptyHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        String signed = "GET|" + rawPath + "|" + ts + "|" + emptyHash;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "Internal " + HexFormat.of().formatHex(
            mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));

        assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo(expected);
    }
}
```

> `SigningInterceptor` is currently package-private (`static class`). For the test to construct it, make it `public static` (it is an inner class of `CardClientConfig`; widening visibility is harmless). Adjust in Step 3 if needed.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/nubbank-baas/baas-fep && ./mvnw -B test -Dtest=SigningInterceptorPathTest`
Expected: FAIL — signer currently uses `getURI().getPath()` (decoded), so the signature is over `/internal/v1/bins/50 600`, not the raw `%20` form.

- [ ] **Step 3: Switch to the raw path (and widen visibility if needed)**

In `CardClientConfig.java`:
- Change `static class SigningInterceptor` → `public static class SigningInterceptor` (and its constructor to `public SigningInterceptor(String secret)`).
- Replace line 62:

```java
                + request.getURI().getPath() + "|"
```

with:

```java
                + request.getURI().getRawPath() + "|"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ~/nubbank-baas/baas-fep && ./mvnw -B test -Dtest=SigningInterceptorPathTest`
Expected: PASS.

- [ ] **Step 5: Run the full fep suite**

Run: `cd ~/nubbank-baas/baas-fep && ./mvnw -B test`
Expected: BUILD SUCCESS (HttpCardClientTest still green — its paths are ASCII, raw == decoded).

- [ ] **Step 6: Commit**

```bash
cd ~/nubbank-baas
git add baas-fep/src/main/java/com/nubbank/baas/fep/config/CardClientConfig.java \
        baas-fep/src/test/java/com/nubbank/baas/fep/config/SigningInterceptorPathTest.java
git commit -m "fix(fep): sign raw path (getRawPath) for HMAC parity with card validator (F8)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Docs + session gate

**Files:**
- Modify: `docs/contracts/phase1c-interfaces.md` (§2a contract — 3 new authorize fields + reversal contract)
- Modify: `docs/api-reference.html` (authorize fields; new `POST /internal/v1/reversal`)
- Modify: `docs/deferred-items.md` (DEF-1C-25 reversal: now detects/marks; fund-reversal still Phase 2)
- Modify: `CLAUDE.md` (Confirmed Platform Versions SHAs; Known Gotchas; module notes)
- Modify: `baas-log.md` (new Session entry at top)

- [ ] **Step 1: Run BOTH full suites and capture counts**

```bash
cd ~/nubbank-baas/baas-card && ./mvnw -B test 2>&1 | grep -E "Tests run:|BUILD" | tail -2
cd ~/nubbank-baas/baas-fep  && ./mvnw -B test 2>&1 | grep -E "Tests run:|BUILD" | tail -2
```
Expected: both BUILD SUCCESS. Record the new totals (card was 56, now +~10; fep was 46, now +~8).

- [ ] **Step 2: Update `docs/contracts/phase1c-interfaces.md` §2a**

Add `stan`, `terminalId`, `transmissionDateTime` to the authorize request shape, and add a §2b for the reversal contract (`POST /internal/v1/reversal` request/response shapes). Note both authorize records are guarded by per-service shape tests.

- [ ] **Step 3: Update `docs/api-reference.html`**

Add the three authorize fields and a new row/section for `POST /internal/v1/reversal` (internal, HMAC; request: partnerId/schemaName/originalStan/terminalId/originalTransmissionDateTime; response `{ located }`; RC mapping 00/25).

- [ ] **Step 4: Update `docs/deferred-items.md`**

DEF-1C-25 (reversal): mark that 1C now LOCATES the original and marks it reversed (RC 00/25); the actual fund-reversal remains deferred to Phase 2 with the balance check (DEF-1C-23). DEF-1C-23 stays open.

- [ ] **Step 5: Update `CLAUDE.md`**

- Confirmed Platform Versions: update `baas-card` + `baas-fep` "Last git commit" SHAs (from `git log --oneline -1 -- baas-card/` and `-- baas-fep/`) and test counts.
- Known Gotchas: add rows —
  - "Per-tenant `@Scheduled` job (e.g. idempotency purge) routes to `public` with no context — enumerate schemas via `PartnerOrganizationRepository`, set `PartnerContext` per schema, clear in `finally`."
  - "Authorize idempotency key = `stan|terminalId|transmissionDateTime`; lookup is by `idem_key` alone (retention via daily purge), so lookup and the UNIQUE constraint never disagree."
  - "BIN range END pads with `9` (`normalizeRangeEnd`), START pads with `0` (frozen `normalize`); a short BIN registered start==end covers its full sub-range."
  - "Cross-service authorize DTO parity is guarded by a per-module shape test (separate Maven modules ⇒ no shared reflection)."

- [ ] **Step 6: Add the `baas-log.md` session entry (top of Change History)**

Use the skill's session template: summary + final SHA, New/Updated Files table, Key Decisions (F1–F8 + the design choices ratified), Build Verification (both counts), Confirmed Platform Versions blocks for card + fep.

- [ ] **Step 7: Commit the docs + gate**

```bash
cd ~/nubbank-baas
git add CLAUDE.md baas-log.md docs/contracts/phase1c-interfaces.md docs/api-reference.html docs/deferred-items.md
git commit -m "docs(baas-log+claude): Session N — card/FEP seam hardening (F1-F8)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 8: Final verification before any push**

```bash
cd ~/nubbank-baas/baas-card && ./mvnw -B test 2>&1 | tail -3
cd ~/nubbank-baas/baas-fep  && ./mvnw -B test 2>&1 | tail -3
git -C ~/nubbank-baas log --oneline feature/phase1c-seam-hardening ^main
```
Expected: both BUILD SUCCESS; the log shows 13 task commits + the spec commit. Do not push until the user asks (per session conventions).

---

## Self-Review (completed by plan author)

**1. Spec coverage:**
- F1 → Task 1 (util) + Task 6 (scaling + RC12). ✅
- F2 → Task 2 (column) + Task 3 (plumbing) + Task 6 (RC57). ✅
- F3 → Task 2 (table) + Task 4 (entity/repo/purge) + Task 5 (contract card) + Task 6 (dedup) + Task 10 (contract fep + forward). ✅
- F4 → Task 7. ✅
- F5 → Task 6 (env from schema). ✅
- F6 → Task 9 (card endpoint) + Task 11 (DE90 + fep wiring). ✅
- F7 → Task 8. ✅
- F8 → Task 12. ✅
- Non-goals (no balance/fund-reversal) honored — reversal only marks `reversed`. ✅
- Contract parity → per-module shape tests (Task 5 + Task 10), with rationale for not using cross-class reflection. ✅

**2. Placeholder scan:** No TBD/TODO. Tests note where they must reuse an existing class helper (CardLimitTest/BinLookupTest) — these are explicit reuse instructions with the exact assertion to add, not placeholders.

**3. Type consistency:**
- `AuthorizationDecisionService` constructor (Task 6) = `(CardRepository, CardLimitRepository, PanHasher, CurrencyMinorUnits, AuthorizationIdempotencyRepository)` — matches the test updates in Task 6 Step 1(b). ✅
- `AuthorizationDecisionRequest` / `AuthorizationDecision.Request` 8 components identical and match the shape tests (Tasks 5, 10). ✅
- `ReversalDecision.Request` (fep) fields = `partnerId, schemaName, originalStan, terminalId, originalTransmissionDateTime` match `ReversalRequest` (card) fields. ✅
- `ReversalService.reverse` composes key `originalStan|terminalId|originalTransmissionDateTime` — matches the authorize `idemKey` order `stan|terminalId|transmissionDateTime`. ✅ (critical: same field order both sides)
- `normalizeRangeEnd` (Task 8) used in `register`; frozen `normalize` untouched. ✅

**Known follow-the-codebase points for the implementer:** exact helper names in `CardLimitTest` / `BinLookupTest` and whether `HttpCardClientTest` constructs `AuthorizationDecision.Request` positionally — both are flagged in-task with what to do.
