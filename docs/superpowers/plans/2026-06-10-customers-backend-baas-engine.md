# Customers Track — Backend (baas-engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `baas-engine` REST surface the Customers backoffice needs — customer edit, KYC lifecycle transitions with reason + history, a widened (masked-identity) detail response, and server-side list filter + name search via a blind index.

**Architecture:** Extends the existing `com.nubbank.baas.engine.customer` package. Tenant-scoped (Hibernate routes every query to the partner schema via `PartnerContext`). KYC transitions are path-segment commands writing an audit row to a new `customer_kyc_events` table in one transaction. Encrypted names are made searchable with a deterministic HMAC blind index (`name_search_tokens TEXT[]`, GIN-indexed), mirroring `baas-card`'s `PanHasher`.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, Flyway (tenant schema migrations), PostgreSQL 16, Testcontainers + JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-10-customers-track-design.md`

**Branch:** `feat/baas-engine-customer-lifecycle` (off `main`). Ships as its own PR (zero overlap with the frontend PR).

---

## Pre-flight

- [ ] **Create branch**

```bash
cd ~/nubbank-baas
git checkout main && git pull --ff-only origin main
git checkout -b feat/baas-engine-customer-lifecycle
```

## Shared test harness (referenced by every integration task below)

All integration tests extend `AbstractIntegrationTest` (Testcontainers Postgres, `TestRestTemplate restTemplate`, `PartnerContext.clear()` in `@AfterEach`). Every test class uses this setup to get an authenticated partner JWT + a provisioned tenant schema. **Reproduce this `@BeforeEach` in each new test class** (adjust the partner name/email per class):

```java
@Autowired private PartnerJwtService jwtService;
@Autowired private PartnerOrganizationRepository orgRepo;
@Autowired private TenantProvisioningService provisioningService;

private String jwt;
private String schemaName;
private UUID orgId;

@BeforeEach
void setup() {
    schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
    PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
        .name("Cust Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
        .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
        .contactEmail("ct@partner.com").build());
    orgId = org.getId();
    provisioningService.provision(org.getId(), schemaName);
    jwt = jwtService.issue(UUID.randomUUID().toString(), "ct@partner.com", "PARTNER_ADMIN",
        org.getId().toString(), "Cust Test", schemaName, "SANDBOX", "SANDBOX");
}

private HttpHeaders auth() {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(jwt);
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
}

/** Create a customer via the API and return its id. */
private UUID createCustomer(Map<String, Object> body) {
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers", HttpMethod.POST,
        new HttpEntity<>(body, auth()), Map.class);
    return UUID.fromString((String) ((Map<?, ?>) r.getBody().get("data")).get("id"));
}
```

A first-party partner JWT carries full tenant authority (all permission codes), so `@PreAuthorize` checks pass.

---

## Task 1: V5 migration — `customer_kyc_events` table + `name_search_tokens` column

**Files:**
- Create: `src/main/resources/db/migration/tenant/V5__customer_kyc_events.sql`
- Test: `src/test/java/com/nubbank/baas/engine/customer/CustomerSchemaV5Test.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerSchemaV5Test extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void v5_addsKycEventsTableAndNameSearchTokensColumn() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("V5").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("v5@test.com").build());
        provisioningService.provision(org.getId(), schema);

        assertThat(jdbc.queryForObject("SELECT to_regclass(?)", String.class,
            schema + ".customer_kyc_events")).isNotNull();
        Integer col = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns "
            + "WHERE table_schema = ? AND table_name = 'customers' AND column_name = 'name_search_tokens'",
            Integer.class, schema);
        assertThat(col).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -Dtest=CustomerSchemaV5Test test`
Expected: FAIL — `to_regclass` returns null (table missing) / column count 0.

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/tenant/V5__customer_kyc_events.sql`:

```sql
-- Customers track: KYC lifecycle history + searchable-name blind index.

ALTER TABLE customers ADD COLUMN name_search_tokens TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX idx_customers_name_search_tokens ON customers USING GIN (name_search_tokens);

CREATE TABLE customer_kyc_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customers(id),
    from_status VARCHAR(50) NOT NULL,
    to_status   VARCHAR(50) NOT NULL,
    reason      TEXT NOT NULL,
    changed_by  VARCHAR(255),
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_kyc_events_customer
    ON customer_kyc_events (customer_id, changed_at DESC);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -Dtest=CustomerSchemaV5Test test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/tenant/V5__customer_kyc_events.sql \
        src/test/java/com/nubbank/baas/engine/customer/CustomerSchemaV5Test.java
git commit -m "feat(engine): V5 tenant migration — customer_kyc_events + name_search_tokens"
```

---

## Task 2: `CustomerKycEvent` entity + repository

**Files:**
- Create: `src/main/java/com/nubbank/baas/engine/customer/CustomerKycEvent.java`
- Create: `src/main/java/com/nubbank/baas/engine/customer/CustomerKycEventRepository.java`
- Test: `src/test/java/com/nubbank/baas/engine/customer/CustomerKycEventRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerKycEventRepositoryTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerKycEventRepository eventRepo;

    @Test
    void findByCustomerId_returnsNewestFirst() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Ev").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("ev@test.com").build());
        provisioningService.provision(org.getId(), schema);
        UUID customerId = UUID.randomUUID();

        try {
            PartnerContext.set(new PartnerContext(org.getId().toString(), schema,
                "SANDBOX", "SANDBOX", "TEST", null));
            eventRepo.save(CustomerKycEvent.builder().customerId(customerId)
                .fromStatus("PENDING_KYC").toStatus("ACTIVE").reason("first").changedBy("op").build());
            eventRepo.save(CustomerKycEvent.builder().customerId(customerId)
                .fromStatus("ACTIVE").toStatus("SUSPENDED").reason("second").changedBy("op").build());

            List<CustomerKycEvent> events = eventRepo.findByCustomerIdOrderByChangedAtDesc(customerId);
            assertThat(events).hasSize(2);
            assertThat(events.get(0).getReason()).isEqualTo("second");
        } finally {
            PartnerContext.clear();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -Dtest=CustomerKycEventRepositoryTest test`
Expected: FAIL — `CustomerKycEvent` / `CustomerKycEventRepository` do not exist (compile error).

- [ ] **Step 3: Write the entity + repository**

`CustomerKycEvent.java`:

```java
package com.nubbank.baas.engine.customer;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_kyc_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CustomerKycEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

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

`CustomerKycEventRepository.java`:

```java
package com.nubbank.baas.engine.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CustomerKycEventRepository extends JpaRepository<CustomerKycEvent, UUID> {
    List<CustomerKycEvent> findByCustomerIdOrderByChangedAtDesc(UUID customerId);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -Dtest=CustomerKycEventRepositoryTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/nubbank/baas/engine/customer/CustomerKycEvent.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerKycEventRepository.java \
        src/test/java/com/nubbank/baas/engine/customer/CustomerKycEventRepositoryTest.java
git commit -m "feat(engine): CustomerKycEvent entity + repository"
```

---

## Task 3: `NameTokenizer` + wire token computation into `create()`

**Files:**
- Create: `src/main/java/com/nubbank/baas/engine/customer/NameTokenizer.java`
- Modify: `src/main/java/com/nubbank/baas/engine/customer/Customer.java` (add `nameSearchTokens` field)
- Modify: `src/main/java/com/nubbank/baas/engine/customer/CustomerService.java` (`create` sets tokens)
- Test: `src/test/java/com/nubbank/baas/engine/customer/NameTokenizerTest.java`
- Test: `src/test/java/com/nubbank/baas/engine/customer/CustomerCreateTokensTest.java`

- [ ] **Step 1: Write the failing unit test**

```java
package com.nubbank.baas.engine.customer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class NameTokenizerTest {

    private final NameTokenizer tokenizer = new NameTokenizer("test-encryption-key-exactly-32c!");

    @Test
    void tokensForName_includePrefixesOfEachWord() {
        List<String> tokens = tokenizer.tokensForName("John", "Doe");
        // "jo","joh","john","do","doe" → 5 distinct hashes
        assertThat(tokens).hasSize(5);
        assertThat(tokens).contains(tokenizer.queryToken("john"));
        assertThat(tokens).contains(tokenizer.queryToken("jo"));
        assertThat(tokens).contains(tokenizer.queryToken("doe"));
    }

    @Test
    void queryToken_matchesAStoredPrefix() {
        List<String> stored = tokenizer.tokensForName("Johnathan", "Smith");
        assertThat(stored).contains(tokenizer.queryToken("joh"));   // prefix search
        assertThat(stored).doesNotContain(tokenizer.queryToken("zz"));
    }

    @Test
    void tokensForName_isCaseAndAccentInsensitive() {
        assertThat(tokenizer.tokensForName("JOHN", null))
            .contains(tokenizer.queryToken("john"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -Dtest=NameTokenizerTest test`
Expected: FAIL — `NameTokenizer` does not exist (compile error).

- [ ] **Step 3: Write `NameTokenizer`**

```java
package com.nubbank.baas.engine.customer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * Deterministic blind index over encrypted customer names (DEF mirrors baas-card PanHasher).
 * For each name word, emits HMAC-SHA256 of every prefix (len 2..12) so a prefix query token
 * matches a stored token. Names stay AES-encrypted; only these one-way hashes are stored.
 */
@Component
public class NameTokenizer {

    private static final String HMAC = "HmacSHA256";
    private static final int MIN_PREFIX = 2;
    private static final int MAX_PREFIX = 12;
    private static final int MAX_WORDS = 6;

    private final byte[] key;

    public NameTokenizer(@Value("${app.encryption.key}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException("app.encryption.key must be configured for name tokenization");
        }
        this.key = configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    /** All stored prefix tokens for a customer's name. */
    public List<String> tokensForName(String firstName, String lastName) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String word : words(firstName, lastName)) {
            int max = Math.min(word.length(), MAX_PREFIX);
            for (int len = MIN_PREFIX; len <= max; len++) {
                tokens.add(hmac(word.substring(0, len)));
            }
        }
        return new ArrayList<>(tokens);
    }

    /** Token for a single search word (matched against the stored set). */
    public String queryToken(String word) {
        return hmac(normalize(word));
    }

    private List<String> words(String firstName, String lastName) {
        List<String> out = new ArrayList<>();
        for (String part : new String[]{firstName, lastName}) {
            if (part == null) continue;
            for (String w : normalize(part).split("\\s+")) {
                if (w.length() >= MIN_PREFIX && out.size() < MAX_WORDS) out.add(w);
            }
        }
        return out;
    }

    private static String normalize(String s) {
        String lower = s.toLowerCase(Locale.ROOT).trim();
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private String hmac(String message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            byte[] out = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Name tokenization failed", e);
        }
    }
}
```

- [ ] **Step 4: Run unit test to verify it passes**

Run: `./mvnw -Dtest=NameTokenizerTest test`
Expected: PASS.

- [ ] **Step 5: Add `nameSearchTokens` to the entity + wire into `create()`**

Add to `Customer.java` (after the `ninEncrypted` field; `import java.util.List;` + `import org.hibernate.annotations.JdbcTypeCode;` + `import org.hibernate.type.SqlTypes;`):

```java
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "name_search_tokens", columnDefinition = "text[]")
    @Builder.Default
    private List<String> nameSearchTokens = new java.util.ArrayList<>();
```

In `CustomerService.java`: inject the tokenizer (add to the `@RequiredArgsConstructor` fields) and set tokens before save in `create`:

```java
    private final NameTokenizer nameTokenizer;
```

In `create(...)`, change the builder to include:

```java
        Customer customer = Customer.builder()
            .externalReference(req.externalReference())
            .firstNameEncrypted(req.firstName())
            .lastNameEncrypted(req.lastName())
            .emailEncrypted(req.email())
            .phoneEncrypted(req.phone())
            .bvnEncrypted(req.bvn())
            .ninEncrypted(req.nin())
            .nameSearchTokens(nameTokenizer.tokensForName(req.firstName(), req.lastName()))
            .build();
```

- [ ] **Step 6: Write the create-tokens integration test**

`CustomerCreateTokensTest.java` (uses the shared harness):

```java
package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerCreateTokensTest extends AbstractIntegrationTest {
    // --- shared harness (jwtService, orgRepo, provisioningService, setup, auth, createCustomer) ---
    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private JdbcTemplate jdbc;
    private String jwt; private String schemaName; private UUID orgId;
    @BeforeEach void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Tok").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("tok@partner.com").build());
        orgId = org.getId(); provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "tok@partner.com", "PARTNER_ADMIN",
            org.getId().toString(), "Tok", schemaName, "SANDBOX", "SANDBOX");
    }
    private HttpHeaders auth() { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON); return h; }
    // --- end shared harness ---

    @Test
    void create_populatesNameSearchTokens() {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(Map.of("firstName", "John", "lastName", "Doe"), auth()), Map.class);
        UUID id = UUID.fromString((String) ((Map<?,?>) r.getBody().get("data")).get("id"));

        @SuppressWarnings("unchecked")
        java.sql.Array arr = jdbc.queryForObject(
            "SELECT name_search_tokens FROM " + schemaName + ".customers WHERE id = ?",
            java.sql.Array.class, id);
        // 5 prefix tokens for "john"+"doe"
        Object[] tokens = (Object[]) arr.getArray();
        assertThat(tokens.length).isEqualTo(5);
    }
}
```

- [ ] **Step 7: Run both tests to verify they pass**

Run: `./mvnw -Dtest=NameTokenizerTest,CustomerCreateTokensTest test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/nubbank/baas/engine/customer/NameTokenizer.java \
        src/main/java/com/nubbank/baas/engine/customer/Customer.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerService.java \
        src/test/java/com/nubbank/baas/engine/customer/NameTokenizerTest.java \
        src/test/java/com/nubbank/baas/engine/customer/CustomerCreateTokensTest.java
git commit -m "feat(engine): NameTokenizer blind index + populate tokens on customer create"
```

---

## Task 4: Widened detail — `CustomerDetailResponse` (masked BVN/NIN)

**Files:**
- Create: `src/main/java/com/nubbank/baas/engine/customer/dto/CustomerDetailResponse.java`
- Modify: `src/main/java/com/nubbank/baas/engine/customer/CustomerService.java` (`getById` → returns detail; add `mask`)
- Modify: `src/main/java/com/nubbank/baas/engine/customer/CustomerController.java` (`getById` return type)
- Test: `src/test/java/com/nubbank/baas/engine/customer/CustomerDetailTest.java`

- [ ] **Step 1: Write the failing test** (shared harness + `createCustomer`)

```java
@Test
void detail_returnsMaskedIdentityAndExtraFields() {
    UUID id = createCustomer(Map.of(
        "firstName", "Ada", "lastName", "Lovelace", "phone", "08030001234",
        "dateOfBirth", "1990-01-01", "gender", "F",
        "bvn", "22233344455", "nin", "99988877766"));

    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id, HttpMethod.GET,
        new HttpEntity<>(auth()), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");

    assertThat(data.get("firstName")).isEqualTo("Ada");
    assertThat(data.get("phone")).isEqualTo("08030001234");
    assertThat(data.get("gender")).isEqualTo("F");
    assertThat(data.get("bvnMasked")).isEqualTo("•••••••1234");
    assertThat(data.get("ninMasked")).isEqualTo("•••••••7766");
    assertThat(data).doesNotContainKey("bvn");          // full value never returned
}
```

(Include the shared-harness fields + `setup` + `auth` + `createCustomer` from the preamble.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -Dtest=CustomerDetailTest test`
Expected: FAIL — response has no `phone`/`bvnMasked` keys (current `CustomerResponse` is lean).

- [ ] **Step 3: Implement**

`CustomerDetailResponse.java`:

```java
package com.nubbank.baas.engine.customer.dto;

import com.nubbank.baas.engine.customer.KycLevel;
import com.nubbank.baas.engine.customer.KycStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CustomerDetailResponse(
    UUID id,
    String externalReference,
    String firstName,
    String lastName,
    String email,
    String phone,
    LocalDate dateOfBirth,
    String gender,
    String bvnMasked,
    String ninMasked,
    KycStatus kycStatus,
    KycLevel kycLevel,
    Instant createdAt,
    Instant updatedAt
) {}
```

In `CustomerService.java` replace `getById` and add a masking helper:

```java
    @Transactional(readOnly = true)
    public CustomerDetailResponse getById(UUID id) {
        requireContext();
        Customer c = customerRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND",
                "Customer " + id + " not found"));
        return new CustomerDetailResponse(c.getId(), c.getExternalReference(),
            c.getFirstNameEncrypted(), c.getLastNameEncrypted(), c.getEmailEncrypted(),
            c.getPhoneEncrypted(), c.getDateOfBirth(), c.getGender(),
            mask(c.getBvnEncrypted()), mask(c.getNinEncrypted()),
            c.getKycStatus(), c.getKycLevel(), c.getCreatedAt(), c.getUpdatedAt());
    }

    /** Show only the last 4 digits, never the full identity value. */
    private static String mask(String value) {
        if (value == null || value.isBlank()) return null;
        String last4 = value.length() <= 4 ? value : value.substring(value.length() - 4);
        return "•••••••" + last4;
    }
```

In `CustomerController.java` change the `getById` signature:

```java
    @PreAuthorize("hasAuthority('READ_CUSTOMER')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(customerService.getById(id)));
    }
```

(Confirm `Customer` exposes `getGender()`, `getDateOfBirth()`, `getUpdatedAt()`, `getPhoneEncrypted()` — it does, per the entity. `gender`/`dateOfBirth` are set from `CreateCustomerRequest` in `create`; if `create` does not currently map them, add `.gender(req.gender())` and `.dateOfBirth(req.dateOfBirth() == null ? null : LocalDate.parse(req.dateOfBirth()))` to the builder in `create`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -Dtest=CustomerDetailTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/nubbank/baas/engine/customer/dto/CustomerDetailResponse.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerService.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerController.java \
        src/test/java/com/nubbank/baas/engine/customer/CustomerDetailTest.java
git commit -m "feat(engine): widen customer detail with phone/DOB/gender + masked BVN/NIN"
```

---

## Task 5: KYC state machine — transitions + history

**Files:**
- Create: `src/main/java/com/nubbank/baas/engine/customer/dto/KycTransitionRequest.java`
- Modify: `src/main/java/com/nubbank/baas/engine/customer/CustomerService.java` (`transition`)
- Modify: `src/main/java/com/nubbank/baas/engine/customer/CustomerController.java` (4 endpoints)
- Test: `src/test/java/com/nubbank/baas/engine/customer/CustomerKycTransitionTest.java`

- [ ] **Step 1: Write the failing test** (shared harness + `createCustomer`)

```java
@Test
void activate_movesPendingToActive_andRecordsEvent() {
    UUID id = createCustomer(Map.of("firstName", "A", "lastName", "B"));
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id + "/activate",
        HttpMethod.POST, new HttpEntity<>(Map.of("reason", "docs verified"), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Map<?,?>) r.getBody().get("data")).get("kycStatus")).isEqualTo("ACTIVE");

    ResponseEntity<Map> ev = restTemplate.exchange("/baas/v1/customers/" + id + "/kyc-events",
        HttpMethod.GET, new HttpEntity<>(auth()), Map.class);  // events endpoint built in Task 6
}

@Test
void suspendFromPending_isIllegal_409() {
    UUID id = createCustomer(Map.of("firstName", "A", "lastName", "B"));
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id + "/suspend",
        HttpMethod.POST, new HttpEntity<>(Map.of("reason", "x"), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
    assertThat(errors.get(0).get("code")).isEqualTo("INVALID_KYC_TRANSITION");
}

@Test
void transition_blankReason_400() {
    UUID id = createCustomer(Map.of("firstName", "A", "lastName", "B"));
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id + "/activate",
        HttpMethod.POST, new HttpEntity<>(Map.of("reason", ""), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
}
```

(Drop the `kyc-events` GET line if running Task 5 before Task 6; the status assertion is the core check.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -Dtest=CustomerKycTransitionTest test`
Expected: FAIL — no `/activate` handler (500/404), transitions not implemented.

- [ ] **Step 3: Implement**

`KycTransitionRequest.java`:

```java
package com.nubbank.baas.engine.customer.dto;

import jakarta.validation.constraints.NotBlank;

public record KycTransitionRequest(
    @NotBlank(message = "reason is required") String reason
) {}
```

In `CustomerService.java` add the command enum + transition method (inject `CustomerKycEventRepository eventRepo` via the constructor fields):

```java
    private final CustomerKycEventRepository eventRepo;

    public enum KycCommand { ACTIVATE, SUSPEND, REACTIVATE, CLOSE }

    @Transactional
    public CustomerDetailResponse transition(UUID id, KycCommand command, String reason) {
        requireContext();
        Customer c = customerRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND",
                "Customer " + id + " not found"));
        KycStatus from = c.getKycStatus();
        KycStatus to = target(from, command);
        if (to == null) {
            throw BaasException.conflict("INVALID_KYC_TRANSITION",
                "Cannot " + command + " a customer in status " + from);
        }
        c.setKycStatus(to);
        customerRepo.save(c);
        eventRepo.save(CustomerKycEvent.builder()
            .customerId(id).fromStatus(from.name()).toStatus(to.name())
            .reason(reason).changedBy(currentPrincipal()).build());
        return getByIdInternal(c);
    }

    private static KycStatus target(KycStatus from, KycCommand command) {
        return switch (command) {
            case ACTIVATE   -> from == KycStatus.PENDING_KYC ? KycStatus.ACTIVE : null;
            case SUSPEND    -> from == KycStatus.ACTIVE ? KycStatus.SUSPENDED : null;
            case REACTIVATE -> from == KycStatus.SUSPENDED ? KycStatus.ACTIVE : null;
            case CLOSE      -> (from == KycStatus.ACTIVE || from == KycStatus.SUSPENDED)
                                   ? KycStatus.CLOSED : null;
        };
    }

    private String currentPrincipal() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth == null ? null : String.valueOf(auth.getPrincipal());
    }
```

Refactor the detail mapping into a private helper reused by `getById` and `transition`:

```java
    private CustomerDetailResponse getByIdInternal(Customer c) {
        return new CustomerDetailResponse(c.getId(), c.getExternalReference(),
            c.getFirstNameEncrypted(), c.getLastNameEncrypted(), c.getEmailEncrypted(),
            c.getPhoneEncrypted(), c.getDateOfBirth(), c.getGender(),
            mask(c.getBvnEncrypted()), mask(c.getNinEncrypted()),
            c.getKycStatus(), c.getKycLevel(), c.getCreatedAt(), c.getUpdatedAt());
    }
```

(Have `getById` call `getByIdInternal(c)` after the `findById`/`orElseThrow`.)

In `CustomerController.java` add the four endpoints:

```java
    @PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")
    @PostMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> activate(
            @PathVariable UUID id, @Valid @RequestBody KycTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            customerService.transition(id, CustomerService.KycCommand.ACTIVATE, req.reason())));
    }

    @PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")
    @PostMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> suspend(
            @PathVariable UUID id, @Valid @RequestBody KycTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            customerService.transition(id, CustomerService.KycCommand.SUSPEND, req.reason())));
    }

    @PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> reactivate(
            @PathVariable UUID id, @Valid @RequestBody KycTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            customerService.transition(id, CustomerService.KycCommand.REACTIVATE, req.reason())));
    }

    @PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")
    @PostMapping("/{id}/close")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> close(
            @PathVariable UUID id, @Valid @RequestBody KycTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            customerService.transition(id, CustomerService.KycCommand.CLOSE, req.reason())));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -Dtest=CustomerKycTransitionTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/nubbank/baas/engine/customer/dto/KycTransitionRequest.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerService.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerController.java \
        src/test/java/com/nubbank/baas/engine/customer/CustomerKycTransitionTest.java
git commit -m "feat(engine): KYC lifecycle transitions (activate/suspend/reactivate/close) + history"
```

---

## Task 6: KYC events endpoint

**Files:**
- Create: `src/main/java/com/nubbank/baas/engine/customer/dto/CustomerKycEventResponse.java`
- Modify: `CustomerService.java` (`kycEvents`)
- Modify: `CustomerController.java` (`GET /{id}/kyc-events`)
- Test: `src/test/java/com/nubbank/baas/engine/customer/CustomerKycEventsApiTest.java`

- [ ] **Step 1: Write the failing test** (shared harness)

```java
@Test
void kycEvents_returnsHistoryNewestFirst() {
    UUID id = createCustomer(Map.of("firstName", "A", "lastName", "B"));
    restTemplate.exchange("/baas/v1/customers/" + id + "/activate", HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "verified"), auth()), Map.class);
    restTemplate.exchange("/baas/v1/customers/" + id + "/suspend", HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "fraud flag"), auth()), Map.class);

    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id + "/kyc-events",
        HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> events = (List<Map<String,Object>>) r.getBody().get("data");
    assertThat(events).hasSize(2);
    assertThat(events.get(0).get("toStatus")).isEqualTo("SUSPENDED");
    assertThat(events.get(0).get("reason")).isEqualTo("fraud flag");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -Dtest=CustomerKycEventsApiTest test`
Expected: FAIL — no `/kyc-events` handler.

- [ ] **Step 3: Implement**

`CustomerKycEventResponse.java`:

```java
package com.nubbank.baas.engine.customer.dto;

import java.time.Instant;
import java.util.UUID;

public record CustomerKycEventResponse(
    UUID id, String fromStatus, String toStatus, String reason,
    String changedBy, Instant changedAt
) {}
```

`CustomerService.java`:

```java
    @Transactional(readOnly = true)
    public List<CustomerKycEventResponse> kycEvents(UUID id) {
        requireContext();
        return eventRepo.findByCustomerIdOrderByChangedAtDesc(id).stream()
            .map(e -> new CustomerKycEventResponse(e.getId(), e.getFromStatus(), e.getToStatus(),
                e.getReason(), e.getChangedBy(), e.getChangedAt()))
            .toList();
    }
```

`CustomerController.java`:

```java
    @PreAuthorize("hasAuthority('READ_CUSTOMER')")
    @GetMapping("/{id}/kyc-events")
    public ResponseEntity<ApiResponse<List<CustomerKycEventResponse>>> kycEvents(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(customerService.kycEvents(id)));
    }
```

(Add `import java.util.List;` where needed.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -Dtest=CustomerKycEventsApiTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/nubbank/baas/engine/customer/dto/CustomerKycEventResponse.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerService.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerController.java \
        src/test/java/com/nubbank/baas/engine/customer/CustomerKycEventsApiTest.java
git commit -m "feat(engine): GET /customers/{id}/kyc-events history endpoint"
```

---

## Task 7: Edit — `PUT /customers/{id}`

**Files:**
- Create: `src/main/java/com/nubbank/baas/engine/customer/dto/UpdateCustomerRequest.java`
- Modify: `CustomerService.java` (`update`)
- Modify: `CustomerController.java` (`PUT /{id}`)
- Test: `src/test/java/com/nubbank/baas/engine/customer/CustomerUpdateTest.java`

- [ ] **Step 1: Write the failing test** (shared harness)

```java
@Test
void update_mutatesFields_andRetokenizesName() {
    UUID id = createCustomer(Map.of("firstName", "John", "lastName", "Doe"));
    Map<String,Object> body = new java.util.HashMap<>();
    body.put("firstName", "Jonathan"); body.put("lastName", "Doe");
    body.put("email", "jon@x.com"); body.put("phone", "0805"); body.put("gender", "M");

    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id, HttpMethod.PUT,
        new HttpEntity<>(body, auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Map<?,?>) r.getBody().get("data")).get("firstName")).isEqualTo("Jonathan");

    // name search now finds the new name (search endpoint built in Task 8)
}

@Test
void update_unknownId_404() {
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + UUID.randomUUID(),
        HttpMethod.PUT, new HttpEntity<>(Map.of("firstName","X","lastName","Y"), auth()), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -Dtest=CustomerUpdateTest test`
Expected: FAIL — no `PUT` handler.

- [ ] **Step 3: Implement**

`UpdateCustomerRequest.java`:

```java
package com.nubbank.baas.engine.customer.dto;

import jakarta.validation.constraints.*;

public record UpdateCustomerRequest(
    @NotBlank(message = "firstName is required") String firstName,
    @NotBlank(message = "lastName is required") String lastName,
    @Email(message = "email must be valid") String email,
    String phone,
    String dateOfBirth,
    String gender
) {}
```

`CustomerService.java`:

```java
    @Transactional
    public CustomerDetailResponse update(UUID id, UpdateCustomerRequest req) {
        requireContext();
        Customer c = customerRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND",
                "Customer " + id + " not found"));
        c.setFirstNameEncrypted(req.firstName());
        c.setLastNameEncrypted(req.lastName());
        c.setEmailEncrypted(req.email());
        c.setPhoneEncrypted(req.phone());
        c.setGender(req.gender());
        if (req.dateOfBirth() != null && !req.dateOfBirth().isBlank()) {
            c.setDateOfBirth(java.time.LocalDate.parse(req.dateOfBirth()));
        }
        c.setNameSearchTokens(nameTokenizer.tokensForName(req.firstName(), req.lastName()));
        customerRepo.save(c);
        return getByIdInternal(c);
    }
```

`CustomerController.java`:

```java
    @PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(customerService.update(id, req)));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -Dtest=CustomerUpdateTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/nubbank/baas/engine/customer/dto/UpdateCustomerRequest.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerService.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerController.java \
        src/test/java/com/nubbank/baas/engine/customer/CustomerUpdateTest.java
git commit -m "feat(engine): PUT /customers/{id} edit (re-tokenizes name)"
```

---

## Task 8: List filter + name search

**Files:**
- Modify: `src/main/java/com/nubbank/baas/engine/customer/CustomerRepository.java` (search query)
- Modify: `CustomerService.java` (`list` overload with filter/search)
- Modify: `CustomerController.java` (`list` params)
- Test: `src/test/java/com/nubbank/baas/engine/customer/CustomerSearchTest.java`

- [ ] **Step 1: Write the failing test** (shared harness)

```java
@Test
void list_filtersByStatus_andSearchesByNamePrefix_andExternalRef() {
    UUID john = createCustomer(Map.of("firstName","John","lastName","Doe","externalReference","ext-100"));
    createCustomer(Map.of("firstName","Mary","lastName","Jane","externalReference","ext-200"));
    restTemplate.exchange("/baas/v1/customers/" + john + "/activate", HttpMethod.POST,
        new HttpEntity<>(Map.of("reason","ok"), auth()), Map.class);

    // name prefix "joh" → only John
    assertThat(count("?search=joh")).isEqualTo(1);
    // external ref substring → only ext-200 owner
    assertThat(count("?search=ext-200")).isEqualTo(1);
    // status filter ACTIVE → only John
    assertThat(count("?kycStatus=ACTIVE")).isEqualTo(1);
    // status filter PENDING_KYC → only Mary
    assertThat(count("?kycStatus=PENDING_KYC")).isEqualTo(1);
    // combined: ACTIVE + "mary" → none
    assertThat(count("?kycStatus=ACTIVE&search=mary")).isEqualTo(0);
}

@SuppressWarnings("unchecked")
private int count(String query) {
    ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers" + query, HttpMethod.GET,
        new HttpEntity<>(auth()), Map.class);
    Map<String,Object> page = (Map<String,Object>) r.getBody().get("data");
    return ((List<?>) page.get("content")).size();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -Dtest=CustomerSearchTest test`
Expected: FAIL — `kycStatus`/`search` params ignored; counts wrong.

- [ ] **Step 3: Implement**

`CustomerRepository.java` — add a search query. The token array match uses the PostgreSQL `@>` (contains) operator via a native query (JPQL has no array-contains); the kycStatus + externalRef are folded in:

```java
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    boolean existsByExternalReference(String externalReference);
    Optional<Customer> findByExternalReference(String externalReference);
    Page<Customer> findByKycStatus(KycStatus status, Pageable pageable);
    long countByKycStatus(KycStatus status);

    @Query(value = """
        SELECT * FROM customers c
        WHERE (:status IS NULL OR c.kyc_status = :status)
          AND (
                :hasSearch = FALSE
                OR c.name_search_tokens @> CAST(:tokens AS text[])
                OR (:extRef IS NOT NULL AND c.external_reference ILIKE :extRef)
              )
        ORDER BY c.created_at DESC
        """,
        countQuery = """
        SELECT count(*) FROM customers c
        WHERE (:status IS NULL OR c.kyc_status = :status)
          AND (
                :hasSearch = FALSE
                OR c.name_search_tokens @> CAST(:tokens AS text[])
                OR (:extRef IS NOT NULL AND c.external_reference ILIKE :extRef)
              )
        """,
        nativeQuery = true)
    Page<Customer> search(@Param("status") String status,
                          @Param("hasSearch") boolean hasSearch,
                          @Param("tokens") String tokens,
                          @Param("extRef") String extRef,
                          Pageable pageable);
}
```

> Note: `:tokens` is a Postgres array literal string like `{hash1,hash2}`. `@>` requires ALL listed
> tokens present → multi-word AND. Build it in the service.

`CustomerService.java` — replace `list` with the filtered version (keep the old 2-arg signature delegating, or update the controller). Build the token literal from the search words:

```java
    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(int page, int size, String kycStatus, String search) {
        requireContext();
        boolean hasSearch = search != null && !search.isBlank();
        String tokensLiteral = "{}";
        String extRef = null;
        if (hasSearch) {
            List<String> hashes = new ArrayList<>();
            for (String w : search.trim().split("\\s+")) {
                if (w.length() >= 2) hashes.add(nameTokenizer.queryToken(w));
            }
            tokensLiteral = "{" + String.join(",", hashes) + "}";
            extRef = "%" + search.trim() + "%";
        }
        return customerRepo.search(kycStatus, hasSearch, tokensLiteral, extRef,
                PageRequest.of(page, size)).map(this::toResponse);
    }
```

(Keep the private `toResponse(Customer)` → lean `CustomerResponse` exactly as it is today.)

`CustomerController.java` — extend `list`:

```java
    @PreAuthorize("hasAuthority('READ_CUSTOMER')")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<CustomerResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String kycStatus,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(ApiResponse.ok(customerService.list(page, size, kycStatus, search)));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -Dtest=CustomerSearchTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/nubbank/baas/engine/customer/CustomerRepository.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerService.java \
        src/main/java/com/nubbank/baas/engine/customer/CustomerController.java \
        src/test/java/com/nubbank/baas/engine/customer/CustomerSearchTest.java
git commit -m "feat(engine): customer list filter by KYC status + blind-index name/extRef search"
```

---

## Task 9: API docs

**Files:**
- Modify: `docs/api-reference.html` (Customers section)

- [ ] **Step 1: Update the Customers section** of `docs/api-reference.html` with the new/changed endpoints: `GET /customers` (now with `kycStatus`/`search` params), `GET /customers/{id}` (widened response, masked BVN/NIN), `PUT /customers/{id}`, `POST /customers/{id}/{activate|suspend|reactivate|close}` (body `{reason}`, `409 INVALID_KYC_TRANSITION`), `GET /customers/{id}/kyc-events`. Match the existing table/markup style in that file.

- [ ] **Step 2: Commit**

```bash
git add docs/api-reference.html
git commit -m "docs(api-reference): customer lifecycle + search endpoints"
```

---

## Task 10: Full-suite verification

- [ ] **Step 1: Run the whole engine suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures (existing + ~12 new tests green).

- [ ] **Step 2: Push + open PR** (handled by `finishing-a-development-branch`)

```bash
git push -u origin feat/baas-engine-customer-lifecycle
```

The SESSION COMPLETION GATE (baas-log.md, CLAUDE.md Confirmed Platform Versions, `docs/api-reference.html` already done in Task 9) is completed before merge.

---

## Notes for the implementer

- **`@PreAuthorize` works in tests** because a first-party partner JWT gets full tenant authority — no role seeding needed.
- **Tenant routing is automatic**: every repository call runs against the partner schema resolved from `PartnerContext`. Do not add a `partnerId` filter.
- **Native query array param**: pass the token set as a Postgres array literal string (`{a,b}`) and `CAST(:tokens AS text[])`; `@>` is "contains all".
- **`mask()` never returns the full BVN/NIN** — only the `•••••••` + last 4. There is a test asserting the `bvn` key is absent from the response.
- The stale `// Phase 2: encrypt with Jasypt` comment in `create()` is wrong (encryption is already applied via the entity `@Convert`); leave the encryption alone — only add the `nameSearchTokens` line.
