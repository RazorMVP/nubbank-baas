# Stage 5 — Card↔Engine Money Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire a real, idempotent, fail-closed card money path across `baas-fep → baas-card → baas-engine`: an approved authorization debits the cardholder's engine account, a reversal credits it back, card schemas are auto-provisioned when the engine onboards a partner, and the FEP keeps a best-effort authorization audit trail.

**Architecture:** The engine gains an inbound HMAC seam (`/internal/v1/**`) and is the **money-dedupe authority** — debit/credit are each one atomic engine-schema transaction, idempotent on a card-supplied `authKey = stan|terminalId|transmissionDateTime`, recorded in a new `card_auth_debit` table. The card gains an outbound HMAC signer, a `linkedAccountId` binding (validated at issuance), and rewired authorize/reversal that call the engine. The card is the single owner of currency translation (minor→major units **and** DE49 numeric → ISO alphabetic). Provisioning is a synchronous engine→card call. The FEP gets a `JdbcTemplate`-backed append-only audit table in a non-tenant `fep` schema.

**Tech Stack:** Java 21, Spring Boot 3.5.3, Hibernate SCHEMA multi-tenancy, Flyway, jPOS ISO 8583:1987, Nimbus HMAC inter-service auth, JUnit 5 + Testcontainers (PostgreSQL), Mockito.

**Spec:** `docs/superpowers/specs/2026-06-04-card-engine-stage5-money-wiring-design.md`

---

## Conventions used throughout

- **HMAC scheme (frozen):** `HmacSHA256(secret, METHOD | rawPath | epochSeconds | sha256Hex(body))`, headers `Authorization: Internal <hex>` + `X-Internal-Timestamp`, 60 s skew, secret `≥32` chars validated at boot. Outbound signer = `getURI().getRawPath()`; inbound validator = `getRequestURI()`.
- **Envelope:** internal endpoints return `ApiResponse.ok(data)`; callers read `.data`.
- **`authKey`** = `stan + "|" + terminalId + "|" + transmissionDateTime` (and on reversal `originalStan + "|" + terminalId + "|" + originalTransmissionDateTime`).
- **Currency on the wire:** FEP→card = ISO 4217 **numeric** (`"566"`, frozen). card→engine = ISO 4217 **alphabetic** (`"NGN"`, translated by the card). Engine `accounts.currency_code` is alphabetic.
- **PartnerContext discipline:** the controller (or a non-transactional wrapper) sets `PartnerContext` from the request body, calls the `@Transactional` service inside the set, clears in `finally`. NEVER make the entry method `@Transactional` (opens the Hibernate session before context is set → routes to `public`).
- **Run a single test (engine/card/fep):** `cd baas-<svc> && ./mvnw test -Dtest=ClassName#method -q`. Full module suite: `cd baas-<svc> && ./mvnw test -q`.
- **Commit co-author trailer (every commit):**
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```
- **Branch:** all work on `feature/stage5-card-engine-money-wiring` (already created; the spec commit is its first commit).

## File-structure map

**baas-engine (new):**
- `config/InternalServiceAuthFilter.java` — inbound HMAC gate for `/internal/v1/**` (mirror of card's).
- `config/SecurityConfig.java` *(modify)* — add `@Order(0) internalFilterChain`.
- `account/CardAuthDebit.java`, `account/CardAuthDebitRepository.java`, `account/CardAuthOutcome.java` — dedupe record + repo + outcome enum.
- `account/AccountService.java` *(modify)* — `cardAuthorizationDebit(...)`, `cardAuthorizationCredit(...)`, `lookupAccount(...)`.
- `account/dto/CardDebitRequest.java`, `CardDebitResult.java`, `CardCreditRequest.java`, `CardCreditResult.java`, `AccountLookupRequest.java`, `AccountLookupResult.java`.
- `account/InternalCardMoneyController.java` — `POST /internal/v1/{card-debit,card-credit,account-lookup}`.
- `tenant/CardProvisioningClient.java` — outbound engine→card provision call.
- `tenant/TenantProvisioningService.java` *(modify)* — call card after engine migrations.
- `db/migration/tenant/V4__card_auth_debit.sql`.

**baas-card (new):**
- `config/InternalServiceClient.java` — outbound signer (`internalServiceRestTemplate`).
- `engine/EngineClient.java`, `engine/dto/*` — card→engine debit/credit/account-lookup wrapper + DTOs.
- `common/CurrencyMinorUnits.java` *(modify)* — add `alphaFor(numericCode)`.
- `card/Card.java` *(modify)*, `card/dto/IssueCardRequest.java` *(modify)*, `card/CardService.java` *(modify)* — `linkedAccountId` + issuance validation.
- `authorize/AuthorizationDecisionService.java` *(modify)*, `authorize/ReversalService.java` *(modify)* — engine calls.
- `tenant/InternalProvisioningController.java` — `POST /internal/v1/provision`.
- `tenant/dto/ProvisionRequest.java`.
- `db/migration/card-tenant/V3__linked_account_id.sql`.

**baas-fep (new):**
- `pom.xml` *(modify)*, `application.yml` *(modify)* — datasource + flyway + jdbc.
- `audit/AuthorizationAuditService.java`, `audit/FepAuthorizationLog.java`.
- `router/AuthorizationHandler.java` *(modify)*, `router/ReversalHandler.java` *(modify)* — record audit.
- `db/migration/fep/V1__authorization_log.sql`.

---

# PHASE A — Engine inbound seam + money operations

## Task 1: Engine inbound HMAC seam

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/config/InternalServiceAuthFilter.java`
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/config/SecurityConfig.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/config/EngineInternalServiceAuthFilterTest.java`

- [ ] **Step 1: Create the filter** by copying `baas-card/src/main/java/com/nubbank/baas/card/config/InternalServiceAuthFilter.java` **verbatim**, changing only the package declaration to `package com.nubbank.baas.engine.config;`. It is self-contained (no card-specific imports) — `MAX_SKEW_SECONDS = 60`, `shouldNotFilter` guards `/internal/v1/`, constructor validates `≥32`-char `app.internal-service.shared-secret`, recomputes `METHOD|getRequestURI()|ts|sha256Hex(body)`, constant-time compare, caches the body via `CachedBodyHttpServletRequest`.

- [ ] **Step 2: Write the failing test**

```java
package com.nubbank.baas.engine.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EngineInternalServiceAuthFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 chars
    private final InternalServiceAuthFilter filter = new InternalServiceAuthFilter(SECRET);

    private String sign(String method, String path, long ts, byte[] body) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body);
        String signed = method + "|" + path + "|" + ts + "|" + HexFormat.of().formatHex(hash);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validSignaturePasses() throws Exception {
        byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        long ts = Instant.now().getEpochSecond();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/v1/card-debit");
        req.setContent(body);
        req.addHeader("Authorization", "Internal " + sign("POST", "/internal/v1/card-debit", ts, body));
        req.addHeader("X-Internal-Timestamp", String.valueOf(ts));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void badSignatureIs401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/v1/card-debit");
        req.setContent("{}".getBytes(StandardCharsets.UTF_8));
        req.addHeader("Authorization", "Internal deadbeef");
        req.addHeader("X-Internal-Timestamp", String.valueOf(Instant.now().getEpochSecond()));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void staleTimestampIs401() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long ts = Instant.now().getEpochSecond() - 120; // beyond 60s skew
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/v1/card-debit");
        req.setContent(body);
        req.addHeader("Authorization", "Internal " + sign("POST", "/internal/v1/card-debit", ts, body));
        req.addHeader("X-Internal-Timestamp", String.valueOf(ts));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void nonInternalPathSkipped() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/baas/v1/accounts/1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain); // shouldNotFilter → passes through untouched

        verify(chain).doFilter(req, resp);
        assertThat(resp.getStatus()).isEqualTo(200);
    }
}
```

- [ ] **Step 3: Run the test, expect FAIL** (compilation until Step 1 done, then green): `cd baas-engine && ./mvnw test -Dtest=EngineInternalServiceAuthFilterTest -q`. Expected: PASS once the filter exists.

- [ ] **Step 4: Add the internal security chain.** In `SecurityConfig.java`, inject the filter and add an `@Order(0)` chain. Apply this diff:

Add field + chain (mirror of card's `internalFilterChain`):
```java
    private final InternalServiceAuthFilter internalServiceAuthFilter;

    @Bean
    @Order(0)
    public SecurityFilterChain internalFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/internal/v1/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(internalServiceAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
```
(The engine's `AuthEnforcementFilter.requiresAuth` already returns false for non-`/baas/v1/` paths, and `RateLimitFilter`/`PartnerContextFilter` are no-ops when `PartnerContext` is null — which it is for internal calls at filter time — so no `shouldNotFilter` edits are needed on them. The `@Order(0)` chain makes the permit explicit.)

- [ ] **Step 5: Run the full engine suite:** `cd baas-engine && ./mvnw test -q`. Expected: BUILD SUCCESS (existing tests unaffected).

- [ ] **Step 6: Commit**
```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/config/InternalServiceAuthFilter.java \
        baas-engine/src/main/java/com/nubbank/baas/engine/config/SecurityConfig.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/config/EngineInternalServiceAuthFilterTest.java
git commit -m "feat(engine): inbound HMAC seam for /internal/v1/** (Stage 5 Task 1)"
```

---

## Task 2: Engine `card_auth_debit` dedupe table + entity

**Files:**
- Create: `baas-engine/src/main/resources/db/migration/tenant/V4__card_auth_debit.sql`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/CardAuthOutcome.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/CardAuthDebit.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/CardAuthDebitRepository.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/CardAuthDebitRepositoryTest.java`

- [ ] **Step 1: Write the migration** `V4__card_auth_debit.sql` (snake_case columns; tenant schema — no schema qualifier):
```sql
CREATE TABLE card_auth_debit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_key        VARCHAR(120) NOT NULL UNIQUE,
    account_id      UUID NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    currency_code   VARCHAR(3) NOT NULL,
    outcome         VARCHAR(20) NOT NULL,
    transaction_id  UUID,
    reversed        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_card_auth_debit_created_at ON card_auth_debit (created_at);
```

- [ ] **Step 2: Create the outcome enum** `CardAuthOutcome.java`:
```java
package com.nubbank.baas.engine.account;

/** Result of an internal card-authorization debit attempt. Maps to ISO 8583 DE39 on the card side. */
public enum CardAuthOutcome { DEBITED, INSUFFICIENT, ACCOUNT_INVALID, CURRENCY_MISMATCH }
```

- [ ] **Step 3: Create the entity** `CardAuthDebit.java` (TENANT entity — no `@Table(schema=...)`):
```java
package com.nubbank.baas.engine.account;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Dedupe + reversal-locator row for a card authorization. TENANT entity — Hibernate
 * routes it to the partner schema. {@code auth_key} (UNIQUE) is the cross-service
 * idempotency key {@code stan|terminalId|transmissionDateTime}; a repeat debit with the
 * same key returns the stored {@link #outcome} and moves no money.
 */
@Entity
@Table(name = "card_auth_debit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CardAuthDebit {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "auth_key", nullable = false, unique = true, length = 120)
    private String authKey;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardAuthOutcome outcome;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(nullable = false)
    private boolean reversed;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
}
```

- [ ] **Step 4: Create the repository** `CardAuthDebitRepository.java`:
```java
package com.nubbank.baas.engine.account;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CardAuthDebitRepository extends JpaRepository<CardAuthDebit, UUID> {
    Optional<CardAuthDebit> findByAuthKey(String authKey);
}
```

- [ ] **Step 5: Write the failing integration test** `CardAuthDebitRepositoryTest.java`. Use the engine's existing Testcontainers base class. Find it: `grep -rl "Testcontainers\|@SpringBootTest" baas-engine/src/test/java | head`. If a base class like `AbstractIntegrationTest` exists, extend it and **set a `PartnerContext` to a provisioned test schema in `@BeforeEach`** (mirror how an existing tenant-repository test sets it up — `grep -rl "PartnerContext.set" baas-engine/src/test/java`). The test asserts persistence + unique key:
```java
// Skeleton — adapt the @BeforeEach tenant setup to the engine's existing integration-test base.
@Test
void persistsAndFindsByAuthKey() {
    CardAuthDebit row = CardAuthDebit.builder()
        .authKey("000001|TERM0001|0604120000").accountId(UUID.randomUUID())
        .amount(new BigDecimal("100.0000")).currencyCode("NGN")
        .outcome(CardAuthOutcome.DEBITED).reversed(false).build();
    repo.save(row);
    assertThat(repo.findByAuthKey("000001|TERM0001|0604120000")).isPresent();
    assertThat(repo.findByAuthKey("nope")).isEmpty();
}
```

- [ ] **Step 6: Run, expect FAIL then PASS:** `cd baas-engine && ./mvnw test -Dtest=CardAuthDebitRepositoryTest -q`.

- [ ] **Step 7: Commit**
```bash
git add baas-engine/src/main/resources/db/migration/tenant/V4__card_auth_debit.sql \
        baas-engine/src/main/java/com/nubbank/baas/engine/account/CardAuth*.java \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/CardAuthDebitRepositoryTest.java
git commit -m "feat(engine): card_auth_debit dedupe table + entity (Stage 5 Task 2)"
```

---

## Task 3: Engine `cardAuthorizationDebit` (atomic, idempotent debit)

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/CardDebitRequest.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/CardDebitResult.java`
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/CardAuthorizationDebitTest.java`

- [ ] **Step 1: Create the DTOs.** `CardDebitRequest.java`:
```java
package com.nubbank.baas.engine.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Internal card-debit request body. {@code currency} is ISO 4217 ALPHABETIC (card-translated). */
public record CardDebitRequest(
    String partnerId, String schemaName, UUID accountId,
    String authKey, BigDecimal amount, String currency) {}
```
`CardDebitResult.java`:
```java
package com.nubbank.baas.engine.account.dto;

import com.nubbank.baas.engine.account.CardAuthOutcome;

/** Internal card-debit result. The card maps {@code outcome} to ISO 8583 DE39. */
public record CardDebitResult(CardAuthOutcome outcome) {}
```

- [ ] **Step 2: Write the failing test** `CardAuthorizationDebitTest.java` (Testcontainers; `@BeforeEach` sets `PartnerContext` to a provisioned schema and opens an ACTIVE NGN account with a known balance via the repositories — mirror the engine's existing account integration test setup). Cover every branch:
```java
// DEBITED: account NGN balance 1000.0000, debit 100.0000
@Test void debitsActiveAccount() {
    CardDebitResult r = accountService.cardAuthorizationDebit(req(acctId, "100.0000", "NGN", "k1"));
    assertThat(r.outcome()).isEqualTo(CardAuthOutcome.DEBITED);
    assertThat(accountRepo.findById(acctId).get().getBalance()).isEqualByComparingTo("900.0000");
    assertThat(cardAuthDebitRepo.findByAuthKey("k1")).get()
        .extracting(CardAuthDebit::getTransactionId).isNotNull();
}
// Idempotent retransmit: same authKey → no second debit, same outcome
@Test void idempotentOnAuthKey() {
    accountService.cardAuthorizationDebit(req(acctId, "100.0000", "NGN", "k2"));
    CardDebitResult again = accountService.cardAuthorizationDebit(req(acctId, "100.0000", "NGN", "k2"));
    assertThat(again.outcome()).isEqualTo(CardAuthOutcome.DEBITED);
    assertThat(accountRepo.findById(acctId).get().getBalance()).isEqualByComparingTo("900.0000"); // only once
}
// INSUFFICIENT against minimum-balance floor (no overdraft)
@Test void insufficientBelowMinimumBalance() {
    CardDebitResult r = accountService.cardAuthorizationDebit(req(acctId, "5000.0000", "NGN", "k3"));
    assertThat(r.outcome()).isEqualTo(CardAuthOutcome.INSUFFICIENT);
    assertThat(accountRepo.findById(acctId).get().getBalance()).isEqualByComparingTo("1000.0000"); // unchanged
}
// INSUFFICIENT honoring overdraft limit (allowOverdraft=true, overdraftLimit=200 → floor -200)
@Test void overdraftFloorAllowsThenBlocks() { /* debit 1150 ok (→ -150), debit beyond -200 → INSUFFICIENT */ }
// ACCOUNT_INVALID: unknown account id
@Test void accountInvalidWhenMissing() {
    assertThat(accountService.cardAuthorizationDebit(req(UUID.randomUUID(), "10.0000", "NGN", "k4")).outcome())
        .isEqualTo(CardAuthOutcome.ACCOUNT_INVALID);
}
// ACCOUNT_INVALID: account not ACTIVE (FROZEN)
@Test void accountInvalidWhenFrozen() { /* freeze acct, debit → ACCOUNT_INVALID */ }
// CURRENCY_MISMATCH: account NGN, request USD
@Test void currencyMismatchDeclines() {
    assertThat(accountService.cardAuthorizationDebit(req(acctId, "10.0000", "USD", "k5")).outcome())
        .isEqualTo(CardAuthOutcome.CURRENCY_MISMATCH);
}
```
Provide a `req(...)` helper building `CardDebitRequest` with the test partnerId/schemaName.

- [ ] **Step 3: Run, expect FAIL** (method missing): `cd baas-engine && ./mvnw test -Dtest=CardAuthorizationDebitTest -q`.

- [ ] **Step 4: Implement.** Add `CardAuthDebitRepository cardAuthDebitRepo` to `AccountService`'s constructor deps (it's `@RequiredArgsConstructor`, so just add the `private final` field). Add the method:
```java
@Transactional
public CardDebitResult cardAuthorizationDebit(CardDebitRequest req) {
    requireContext();
    var existing = cardAuthDebitRepo.findByAuthKey(req.authKey());
    if (existing.isPresent()) {
        return new CardDebitResult(existing.get().getOutcome());   // idempotent: no second debit
    }
    Account account = accountRepo.findByIdForUpdate(req.accountId()).orElse(null);
    if (account == null || account.getStatus() != AccountStatus.ACTIVE) {
        return record(req, CardAuthOutcome.ACCOUNT_INVALID, null);
    }
    if (!account.getCurrencyCode().equals(req.currency())) {
        return record(req, CardAuthOutcome.CURRENCY_MISMATCH, null);
    }
    BigDecimal floor = account.isAllowOverdraft() && account.getOverdraftLimit() != null
        ? account.getOverdraftLimit().negate()
        : account.getMinimumBalance();
    if (account.getBalance().subtract(req.amount()).compareTo(floor) < 0) {
        return record(req, CardAuthOutcome.INSUFFICIENT, null);
    }
    account.setBalance(account.getBalance().subtract(req.amount()));
    account.setAvailableBalance(account.getAvailableBalance().subtract(req.amount()));
    accountRepo.save(account);
    Transaction txn = txRepo.save(Transaction.builder()
        .account(account).transactionType(TransactionType.DEBIT)
        .amount(req.amount()).runningBalance(account.getBalance())
        .currencyCode(account.getCurrencyCode())
        .reference("CARD_AUTH").description("Card authorization " + req.authKey())
        .build());
    return record(req, CardAuthOutcome.DEBITED, txn.getId());
}

private CardDebitResult record(CardDebitRequest req, CardAuthOutcome outcome, UUID txnId) {
    cardAuthDebitRepo.save(CardAuthDebit.builder()
        .authKey(req.authKey()).accountId(req.accountId()).amount(req.amount())
        .currencyCode(req.currency()).outcome(outcome).transactionId(txnId).reversed(false).build());
    return new CardDebitResult(outcome);
}
```
Add imports: `com.nubbank.baas.engine.account.dto.CardDebitRequest`, `CardDebitResult`.

- [ ] **Step 5: Run, expect PASS:** `cd baas-engine && ./mvnw test -Dtest=CardAuthorizationDebitTest -q`.

- [ ] **Step 6: Commit**
```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/
git add baas-engine/src/test/java/com/nubbank/baas/engine/account/CardAuthorizationDebitTest.java
git commit -m "feat(engine): atomic idempotent cardAuthorizationDebit (Stage 5 Task 3)"
```

---

## Task 4: Engine `cardAuthorizationCredit` (reversal)

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/CardCreditResult.java`
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/CardAuthorizationCreditTest.java`

- [ ] **Step 1: Create `CardCreditResult.java`:**
```java
package com.nubbank.baas.engine.account.dto;

/** Internal card-credit (reversal) result. Card maps {@code located} → DE39 00 / 25. */
public record CardCreditResult(boolean located) {}
```

- [ ] **Step 2: Write the failing test** `CardAuthorizationCreditTest.java` (Testcontainers; reuse Task 3 setup — first debit, then credit):
```java
@Test void creditsAndMarksReversed() {
    accountService.cardAuthorizationDebit(req(acctId, "100.0000", "NGN", "c1"));   // balance 900
    CardCreditResult r = accountService.cardAuthorizationCredit("c1");
    assertThat(r.located()).isTrue();
    assertThat(accountRepo.findById(acctId).get().getBalance()).isEqualByComparingTo("1000.0000");
    assertThat(cardAuthDebitRepo.findByAuthKey("c1").get().isReversed()).isTrue();
}
@Test void doubleCreditIsNoOp() {
    accountService.cardAuthorizationDebit(req(acctId, "100.0000", "NGN", "c2"));
    accountService.cardAuthorizationCredit("c2");
    CardCreditResult second = accountService.cardAuthorizationCredit("c2");
    assertThat(second.located()).isTrue();
    assertThat(accountRepo.findById(acctId).get().getBalance()).isEqualByComparingTo("1000.0000"); // credited once
}
@Test void notFoundReturnsNotLocated() {
    assertThat(accountService.cardAuthorizationCredit("nope").located()).isFalse();
}
@Test void creditOfDeclinedAuthIsNotLocated() {
    accountService.cardAuthorizationDebit(req(acctId, "5000.0000", "NGN", "c3")); // INSUFFICIENT, no debit
    assertThat(accountService.cardAuthorizationCredit("c3").located()).isFalse();
}
```

- [ ] **Step 3: Run, expect FAIL.**

- [ ] **Step 4: Implement** in `AccountService`:
```java
@Transactional
public CardCreditResult cardAuthorizationCredit(String authKey) {
    requireContext();
    CardAuthDebit row = cardAuthDebitRepo.findByAuthKey(authKey).orElse(null);
    if (row == null || row.getOutcome() != CardAuthOutcome.DEBITED) {
        return new CardCreditResult(false);
    }
    if (row.isReversed()) {
        return new CardCreditResult(true);   // idempotent: already credited
    }
    Account account = accountRepo.findByIdForUpdate(row.getAccountId())
        .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
    account.setBalance(account.getBalance().add(row.getAmount()));
    account.setAvailableBalance(account.getAvailableBalance().add(row.getAmount()));
    accountRepo.save(account);
    txRepo.save(Transaction.builder()
        .account(account).transactionType(TransactionType.CREDIT)
        .amount(row.getAmount()).runningBalance(account.getBalance())
        .currencyCode(account.getCurrencyCode())
        .reference("CARD_REVERSAL").description("Card reversal " + authKey).build());
    row.setReversed(true);
    cardAuthDebitRepo.save(row);
    return new CardCreditResult(true);
}
```
Add import `com.nubbank.baas.engine.account.dto.CardCreditResult`.

- [ ] **Step 5: Run, expect PASS.**

- [ ] **Step 6: Commit**
```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/
git add baas-engine/src/test/java/com/nubbank/baas/engine/account/CardAuthorizationCreditTest.java
git commit -m "feat(engine): idempotent cardAuthorizationCredit reversal (Stage 5 Task 4)"
```

---

## Task 5: Engine internal money controller + account-lookup

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/CardCreditRequest.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountLookupRequest.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/AccountLookupResult.java`
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java` (add `lookupAccount`)
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/InternalCardMoneyController.java`
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/account/InternalCardMoneyControllerTest.java`

- [ ] **Step 1: Create DTOs.**
```java
// CardCreditRequest.java
package com.nubbank.baas.engine.account.dto;
public record CardCreditRequest(String partnerId, String schemaName, String authKey) {}
```
```java
// AccountLookupRequest.java
package com.nubbank.baas.engine.account.dto;
import java.util.UUID;
public record AccountLookupRequest(String partnerId, String schemaName, UUID accountId) {}
```
```java
// AccountLookupResult.java
package com.nubbank.baas.engine.account.dto;
public record AccountLookupResult(boolean exists, boolean active, String currencyCode) {}
```

- [ ] **Step 2: Add `lookupAccount` to `AccountService`** (read-only; used by card issuance validation):
```java
@Transactional(readOnly = true)
public AccountLookupResult lookupAccount(UUID accountId) {
    requireContext();
    return accountRepo.findById(accountId)
        .map(a -> new AccountLookupResult(true, a.getStatus() == AccountStatus.ACTIVE, a.getCurrencyCode()))
        .orElse(new AccountLookupResult(false, false, null));
}
```
Add import `com.nubbank.baas.engine.account.dto.AccountLookupResult`.

- [ ] **Step 3: Create the controller** (sets `PartnerContext` from body, clears in `finally` — the documented pitfall; service methods stay `@Transactional`):
```java
package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.account.dto.*;
import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Internal (service-to-service) card money operations — Stage 5. Guarded by the
 * {@code @Order(0)} internal chain ({@code InternalServiceAuthFilter}, inbound HMAC).
 *
 * <p>{@code PartnerContext} is set HERE from the request body and cleared in
 * {@code finally}. It MUST be set before the {@code @Transactional} service call opens
 * the Hibernate session, or queries route to {@code public}.
 */
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalCardMoneyController {

    private final AccountService accountService;

    @PostMapping("/card-debit")
    public ApiResponse<CardDebitResult> debit(@RequestBody CardDebitRequest req) {
        return inContext(req.partnerId(), req.schemaName(),
            () -> ApiResponse.ok(accountService.cardAuthorizationDebit(req)));
    }

    @PostMapping("/card-credit")
    public ApiResponse<CardCreditResult> credit(@RequestBody CardCreditRequest req) {
        return inContext(req.partnerId(), req.schemaName(),
            () -> ApiResponse.ok(accountService.cardAuthorizationCredit(req.authKey())));
    }

    @PostMapping("/account-lookup")
    public ApiResponse<AccountLookupResult> lookup(@RequestBody AccountLookupRequest req) {
        return inContext(req.partnerId(), req.schemaName(),
            () -> ApiResponse.ok(accountService.lookupAccount(req.accountId())));
    }

    private <T> T inContext(String partnerId, String schemaName, java.util.function.Supplier<T> body) {
        String env = schemaName != null && schemaName.startsWith("sandbox_") ? "SANDBOX" : "PRODUCTION";
        PartnerContext.set(new PartnerContext(partnerId, schemaName, "INTERNAL", env, "INTERNAL", null));
        try {
            return body.get();
        } finally {
            PartnerContext.clear();
        }
    }
}
```

- [ ] **Step 4: Write the failing test** `InternalCardMoneyControllerTest.java` — a `@SpringBootTest(webEnvironment = RANDOM_PORT)` Testcontainers test that calls the endpoints with a **valid HMAC-signed** `TestRestTemplate` (build the signature like Task 1's `sign(...)`), against a provisioned schema with a seeded account. Assert: `card-debit` returns `data.outcome == "DEBITED"` and the balance dropped; `account-lookup` returns `exists=true, active=true, currencyCode="NGN"`; an **unsigned** call returns 401. (If wiring a signed `TestRestTemplate` is heavy, split: keep the 401-without-signature assertion here, and rely on Task 3/4 service tests + Task 16 shape tests for the body mapping.)

- [ ] **Step 5: Run, expect FAIL then PASS:** `cd baas-engine && ./mvnw test -Dtest=InternalCardMoneyControllerTest -q`.

- [ ] **Step 6: Run full engine suite + commit**
```bash
cd baas-engine && ./mvnw test -q && cd ..
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/ \
        baas-engine/src/test/java/com/nubbank/baas/engine/account/InternalCardMoneyControllerTest.java
git commit -m "feat(engine): internal card-debit/card-credit/account-lookup endpoints (Stage 5 Task 5)"
```

---

# PHASE B — Card outbound signer, binding, rewire

## Task 6: Card outbound HMAC signer + `EngineClient`

**Files:**
- Create: `baas-card/src/main/java/com/nubbank/baas/card/config/InternalServiceClient.java`
- Create: `baas-card/src/main/java/com/nubbank/baas/card/engine/EngineClient.java`
- Create: `baas-card/src/main/java/com/nubbank/baas/card/engine/dto/{CardDebitRequest,CardDebitResult,CardCreditRequest,CardCreditResult,AccountLookupRequest,AccountLookupResult}.java`
- Test: `baas-card/src/test/java/com/nubbank/baas/card/engine/EngineClientTest.java`

- [ ] **Step 1: Create the signer** by copying `baas-engine/src/main/java/com/nubbank/baas/engine/config/InternalServiceClient.java` **verbatim**, changing the package to `com.nubbank.baas.card.config`. It produces `@Bean("internalServiceRestTemplate")` with the `SigningInterceptor` (raw-path scheme) and `≥32`-char secret check. (Identical signing logic the engine's inbound filter validates.)

- [ ] **Step 2: Create the card-side DTOs** mirroring the engine's wire shapes (the card-side `CardAuthOutcome` is represented as a `String` to avoid sharing the engine enum across modules):
```java
// engine/dto/CardDebitRequest.java
package com.nubbank.baas.card.engine.dto;
import java.math.BigDecimal; import java.util.UUID;
public record CardDebitRequest(String partnerId, String schemaName, UUID accountId,
                               String authKey, BigDecimal amount, String currency) {}
```
```java
// engine/dto/CardDebitResult.java  — outcome is the engine enum name as a String
package com.nubbank.baas.card.engine.dto;
public record CardDebitResult(String outcome) {}
```
```java
// engine/dto/CardCreditRequest.java
package com.nubbank.baas.card.engine.dto;
public record CardCreditRequest(String partnerId, String schemaName, String authKey) {}
```
```java
// engine/dto/CardCreditResult.java
package com.nubbank.baas.card.engine.dto;
public record CardCreditResult(boolean located) {}
```
```java
// engine/dto/AccountLookupRequest.java
package com.nubbank.baas.card.engine.dto;
import java.util.UUID;
public record AccountLookupRequest(String partnerId, String schemaName, UUID accountId) {}
```
```java
// engine/dto/AccountLookupResult.java
package com.nubbank.baas.card.engine.dto;
public record AccountLookupResult(boolean exists, boolean active, String currencyCode) {}
```

- [ ] **Step 3: Create `EngineClient`** — fail-closed wrapper. Sentinels: debit unreachable → `CardDebitResult("UNREACHABLE")`; credit unreachable → `CardCreditResult(false)`; lookup unreachable → `AccountLookupResult(false,false,null)`. It reads the `ApiResponse` envelope's `data`:
```java
package com.nubbank.baas.card.engine;

import com.nubbank.baas.card.engine.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Outbound client to baas-engine over the HMAC seam. FAIL-CLOSED: any transport error
 * maps to a safe sentinel (debit → "UNREACHABLE" → RC 91; credit/lookup → false), so the
 * authorize/reversal path never throws.
 */
@Component
public class EngineClient {

    private static final Logger log = LoggerFactory.getLogger(EngineClient.class);

    private final RestTemplate http;
    private final String baseUrl;

    public EngineClient(@Qualifier("internalServiceRestTemplate") RestTemplate http,
                        @Value("${app.internal-service.engine-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    public CardDebitResult cardDebit(CardDebitRequest req) {
        return post("/internal/v1/card-debit", req, CardDebitResult.class, new CardDebitResult("UNREACHABLE"));
    }

    public CardCreditResult cardCredit(CardCreditRequest req) {
        return post("/internal/v1/card-credit", req, CardCreditResult.class, new CardCreditResult(false));
    }

    public AccountLookupResult accountLookup(AccountLookupRequest req) {
        return post("/internal/v1/account-lookup", req, AccountLookupResult.class,
            new AccountLookupResult(false, false, null));
    }

    private <T> T post(String path, Object body, Class<T> type, T failClosed) {
        try {
            ResponseEntity<Map<String, Object>> resp = http.exchange(
                baseUrl + path, HttpMethod.POST, new HttpEntity<>(body),
                new ParameterizedTypeReference<>() {});
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                return failClosed;
            }
            Object data = resp.getBody().get("data");
            if (data == null) return failClosed;
            // Re-bind the envelope's data map into the target record via Jackson.
            return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(data, type);
        } catch (RestClientException ex) {
            log.warn("Engine call {} failed, failing closed: {}", path, ex.getMessage());
            return failClosed;
        }
    }
}
```

- [ ] **Step 4: Write the failing test** `EngineClientTest.java` — point the client at an unroutable base URL and assert each method returns its fail-closed sentinel:
```java
@Test void debitFailsClosedWhenEngineUnreachable() {
    EngineClient client = new EngineClient(
        new org.springframework.web.client.RestTemplate(), "http://localhost:1");   // refused
    assertThat(client.cardDebit(new CardDebitRequest("p","partner_x",
        java.util.UUID.randomUUID(),"k",new java.math.BigDecimal("1.0"),"NGN")).outcome())
        .isEqualTo("UNREACHABLE");
    assertThat(client.cardCredit(new CardCreditRequest("p","partner_x","k")).located()).isFalse();
    assertThat(client.accountLookup(new AccountLookupRequest("p","partner_x",
        java.util.UUID.randomUUID())).exists()).isFalse();
}
```

- [ ] **Step 5: Run, expect FAIL then PASS:** `cd baas-card && ./mvnw test -Dtest=EngineClientTest -q`.

- [ ] **Step 6: Commit**
```bash
git add baas-card/src/main/java/com/nubbank/baas/card/config/InternalServiceClient.java \
        baas-card/src/main/java/com/nubbank/baas/card/engine/ \
        baas-card/src/test/java/com/nubbank/baas/card/engine/EngineClientTest.java
git commit -m "feat(card): outbound HMAC signer + fail-closed EngineClient (Stage 5 Task 6)"
```

---

## Task 7: Card `CurrencyMinorUnits.alphaFor`

**Files:**
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/common/CurrencyMinorUnits.java`
- Test: `baas-card/src/test/java/com/nubbank/baas/card/common/CurrencyMinorUnitsTest.java` (extend existing)

- [ ] **Step 1: Write the failing test** (add to the existing `CurrencyMinorUnitsTest`):
```java
@Test void alphaForKnownNumericReturnsIsoAlpha() {
    assertThat(new CurrencyMinorUnits().alphaFor("566")).contains("NGN");
    assertThat(new CurrencyMinorUnits().alphaFor("840")).contains("USD");
}
@Test void alphaForUnknownIsEmpty() {
    assertThat(new CurrencyMinorUnits().alphaFor("000")).isEmpty();
    assertThat(new CurrencyMinorUnits().alphaFor(null)).isEmpty();
}
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement.** Add a second map built in the constructor and the accessor:
```java
    private final Map<String, String> alphaByNumeric;
```
In the constructor loop, alongside the exponent put, add (using the same `Currency c`):
```java
            if (numeric > 0 && digits >= 0) {
                String key = String.format("%03d", numeric);
                map.put(key, digits);
                alpha.put(key, c.getCurrencyCode());   // ISO alphabetic, e.g. "NGN"
            }
```
(declare `Map<String,String> alpha = new HashMap<>();` next to the existing `map`, and `this.alphaByNumeric = Map.copyOf(alpha);` next to the existing copy). Add:
```java
    /** @return the ISO 4217 alphabetic code for a DE49 numeric code, or empty if unknown. */
    public Optional<String> alphaFor(String numericCode) {
        if (numericCode == null || numericCode.isBlank()) return Optional.empty();
        return Optional.ofNullable(alphaByNumeric.get(numericCode));
    }
```

- [ ] **Step 4: Run, expect PASS:** `cd baas-card && ./mvnw test -Dtest=CurrencyMinorUnitsTest -q`.

- [ ] **Step 5: Commit**
```bash
git add baas-card/src/main/java/com/nubbank/baas/card/common/CurrencyMinorUnits.java \
        baas-card/src/test/java/com/nubbank/baas/card/common/CurrencyMinorUnitsTest.java
git commit -m "feat(card): CurrencyMinorUnits.alphaFor numeric→ISO-alpha (Stage 5 Task 7)"
```

---

## Task 8: Card `linkedAccountId` binding + issuance validation

**Files:**
- Create: `baas-card/src/main/resources/db/migration/card-tenant/V3__linked_account_id.sql`
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/card/Card.java`
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/card/dto/IssueCardRequest.java`
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/card/CardService.java`
- Test: `baas-card/src/test/java/com/nubbank/baas/card/card/CardIssuanceLinkedAccountTest.java`

- [ ] **Step 1: Migration** `V3__linked_account_id.sql`:
```sql
ALTER TABLE cards ADD COLUMN IF NOT EXISTS linked_account_id UUID;
```

- [ ] **Step 2: Add the field to `Card`** (after `customerRef`):
```java
    /** The engine account this card draws funds from (bound at issuance). */
    @Column(name = "linked_account_id")
    private UUID linkedAccountId;
```

- [ ] **Step 3: Add `@NotNull linkedAccountId` to `IssueCardRequest`:**
```java
import java.util.UUID;
// add component:
    @NotNull(message = "linkedAccountId is required") UUID linkedAccountId
```
(Place it after `productId`; keep `customerRef` and `virtual`.)

- [ ] **Step 4: Write the failing test** `CardIssuanceLinkedAccountTest.java`. Mock `EngineClient`. Two cases:
```java
@Test void rejectsUnknownAccount() {
    when(engineClient.accountLookup(any())).thenReturn(new AccountLookupResult(false, false, null));
    assertThatThrownBy(() -> cardService.issue(new IssueCardRequest(productId, "cust1", false, acctId)))
        .isInstanceOf(BaasException.class);   // account does not exist
}
@Test void bindsLinkedAccountWhenValid() {
    when(engineClient.accountLookup(any())).thenReturn(new AccountLookupResult(true, true, "NGN"));
    CardResponse r = cardService.issue(new IssueCardRequest(productId, "cust1", false, acctId));
    assertThat(cardRepo.findById(r.id()).get().getLinkedAccountId()).isEqualTo(acctId);
}
```
(Use the card module's existing issuance integration-test scaffolding for `PartnerContext` + product setup; `grep -rl "cardService.issue\|IssueCardRequest" baas-card/src/test/java`.)

- [ ] **Step 5: Run, expect FAIL.**

- [ ] **Step 6: Implement in `CardService.issue`.** Add `EngineClient engineClient` to the constructor deps (`@RequiredArgsConstructor` — add the `private final` field). Before building the `Card`, validate the account and set the link. `PartnerContext.get()` provides `partnerId`/`schemaName`:
```java
        var ctx = com.nubbank.baas.card.tenant.PartnerContext.get();
        AccountLookupResult lookup = engineClient.accountLookup(new AccountLookupRequest(
            ctx.partnerId(), ctx.schemaName(), req.linkedAccountId()));
        if (!lookup.exists()) {
            throw BaasException.badRequest("LINKED_ACCOUNT_NOT_FOUND",
                "linkedAccountId does not exist in the engine");
        }
```
Then add `.linkedAccountId(req.linkedAccountId())` to the `Card.builder()` chain. (`com.nubbank.baas.card.common.BaasException` is the card module's exception factory — already used elsewhere in `CardService`. Add imports for `BaasException`, `EngineClient`, `AccountLookupRequest`, `AccountLookupResult`.)

- [ ] **Step 7: Run, expect PASS:** `cd baas-card && ./mvnw test -Dtest=CardIssuanceLinkedAccountTest -q`.

- [ ] **Step 8: Commit**
```bash
git add baas-card/src/main/resources/db/migration/card-tenant/V3__linked_account_id.sql \
        baas-card/src/main/java/com/nubbank/baas/card/card/ \
        baas-card/src/test/java/com/nubbank/baas/card/card/CardIssuanceLinkedAccountTest.java
git commit -m "feat(card): linkedAccountId binding + issuance validation (Stage 5 Task 8)"
```

---

## Task 9: Card rewire authorize → engine debit

**Files:**
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/authorize/AuthorizationDecisionService.java`
- Test: `baas-card/src/test/java/com/nubbank/baas/card/authorize/AuthorizationDecisionTest.java` (extend)

- [ ] **Step 1: Write the failing tests** (extend the existing `AuthorizationDecisionTest`; mock `EngineClient`, `CurrencyMinorUnits` is real). The card is found, ACTIVE, has a `linkedAccountId`, no blocking limit:
```java
@Test void approvesWhenEngineDebits() {
    when(engineClient.cardDebit(any())).thenReturn(new CardDebitResult("DEBITED"));
    var r = service.decide(req("566", 10000));   // NGN 100.00
    assertThat(r.responseCode()).isEqualTo("00");
    verify(engineClient).cardDebit(argThat(d -> d.currency().equals("NGN")
        && d.amount().compareTo(new java.math.BigDecimal("100.00")) == 0));
}
@Test void insufficientMapsTo51() {
    when(engineClient.cardDebit(any())).thenReturn(new CardDebitResult("INSUFFICIENT"));
    assertThat(service.decide(req("566", 10000)).responseCode()).isEqualTo("51");
}
@Test void accountInvalidMapsTo78() {
    when(engineClient.cardDebit(any())).thenReturn(new CardDebitResult("ACCOUNT_INVALID"));
    assertThat(service.decide(req("566", 10000)).responseCode()).isEqualTo("78");
}
@Test void engineCurrencyMismatchMapsTo57() {
    when(engineClient.cardDebit(any())).thenReturn(new CardDebitResult("CURRENCY_MISMATCH"));
    assertThat(service.decide(req("566", 10000)).responseCode()).isEqualTo("57");
}
@Test void engineUnreachableMapsTo91() {
    when(engineClient.cardDebit(any())).thenReturn(new CardDebitResult("UNREACHABLE"));
    assertThat(service.decide(req("566", 10000)).responseCode()).isEqualTo("91");
}
@Test void nullLinkedAccountMapsTo78WithoutEngineCall() {
    // card with linkedAccountId == null
    assertThat(service.decide(req("566", 10000)).responseCode()).isEqualTo("78");
    verifyNoInteractions(engineClient);
}
@Test void localDeclineMakesNoEngineCall() {
    // e.g. unknown currency "000" → 12, or blocked card → 62: assert engineClient never called
    assertThat(service.decide(req("000", 10000)).responseCode()).isEqualTo("12");
    verifyNoInteractions(engineClient);
}
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement.** Add `EngineClient engineClient` and `CurrencyMinorUnits currencyMinorUnits` (already present) to the service. Replace the final `return approve();` branch of `computeDecision` with the engine call. The new tail (after the limit checks pass):
```java
        // would-approve → resolve funding account and debit the engine
        if (card.getLinkedAccountId() == null) {
            return decline("78", "No linked account");
        }
        String alpha = currencyMinorUnits.alphaFor(req.currency()).orElse(null);
        if (alpha == null) {
            return decline("12", "Unknown currency");   // defensive; exponent check already guards
        }
        BigDecimal majorAmount = new BigDecimal(req.amountMinor()).movePointLeft(exponent.get());
        var ctx = PartnerContext.get();
        CardDebitResult result = engineClient.cardDebit(new CardDebitRequest(
            ctx.partnerId(), ctx.schemaName(), card.getLinkedAccountId(),
            idemKey(req), majorAmount, alpha));
        return switch (result.outcome()) {
            case "DEBITED"          -> approve();
            case "INSUFFICIENT"     -> decline("51", "Insufficient funds");
            case "ACCOUNT_INVALID"  -> decline("78", "No linked account");
            case "CURRENCY_MISMATCH"-> decline("57", "Currency not permitted");
            default                 -> decline("91", "Issuer unavailable");   // UNREACHABLE / unknown
        };
```
Notes: `exponent` is the `Optional<Integer>` already computed at the top of `computeDecision` (keep that check → RC 12). `idemKey(req)` is the existing private method. Keep the fast-path idempotency cache and the local-decline paths untouched — only the terminal approve branch changes. Add imports for `CardDebitRequest`, `CardDebitResult`, `EngineClient`.

- [ ] **Step 4: Run, expect PASS:** `cd baas-card && ./mvnw test -Dtest=AuthorizationDecisionTest -q`.

- [ ] **Step 5: Commit**
```bash
git add baas-card/src/main/java/com/nubbank/baas/card/authorize/AuthorizationDecisionService.java \
        baas-card/src/test/java/com/nubbank/baas/card/authorize/AuthorizationDecisionTest.java
git commit -m "feat(card): authorize calls engine debit, maps outcomes to RC (Stage 5 Task 9)"
```

---

## Task 10: Card rewire reversal → engine credit

**Files:**
- Modify: `baas-card/src/main/java/com/nubbank/baas/card/authorize/ReversalService.java`
- Test: `baas-card/src/test/java/com/nubbank/baas/card/authorize/ReversalServiceTest.java` (extend)

- [ ] **Step 1: Write the failing tests** (extend `ReversalServiceTest`; mock `EngineClient`; seed the idempotency row as today):
```java
@Test void approveOriginalCreditsAndReturnsLocated() {
    seedRow("k|TERM|dts", "APPROVE", false);
    when(engineClient.cardCredit(any())).thenReturn(new CardCreditResult(true));
    assertThat(service.reverse(rev("k","TERM","dts")).located()).isTrue();
    assertThat(idempotencyRepo.findByIdemKey("k|TERM|dts").get().isReversed()).isTrue();
}
@Test void declineOriginalReturnsLocatedNoCredit() {
    seedRow("k|TERM|dts", "DECLINE", false);
    assertThat(service.reverse(rev("k","TERM","dts")).located()).isTrue();
    verifyNoInteractions(engineClient);
}
@Test void notFoundReturnsNotLocated() {
    assertThat(service.reverse(rev("missing","TERM","dts")).located()).isFalse();
}
@Test void engineUnreachableOnApproveReturnsNotLocatedAndDoesNotFlip() {
    seedRow("k|TERM|dts", "APPROVE", false);
    when(engineClient.cardCredit(any())).thenReturn(new CardCreditResult(false));   // fail-closed
    assertThat(service.reverse(rev("k","TERM","dts")).located()).isFalse();
    assertThat(idempotencyRepo.findByIdemKey("k|TERM|dts").get().isReversed()).isFalse();
}
@Test void alreadyReversedIsIdempotentNoEngineCall() {
    seedRow("k|TERM|dts", "APPROVE", true);
    assertThat(service.reverse(rev("k","TERM","dts")).located()).isTrue();
    verifyNoInteractions(engineClient);
}
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement.** Add `EngineClient engineClient` to `ReversalService`. Replace the body after locating `row`:
```java
            AuthorizationIdempotency row = original.get();
            if (row.isReversed()) {
                return new ReversalResponse(true);   // idempotent
            }
            if (!"APPROVE".equals(row.getDecision())) {
                row.setReversed(true);               // declined original: nothing to credit
                idempotencyRepo.save(row);
                return new ReversalResponse(true);
            }
            var ctx = PartnerContext.get();
            CardCreditResult credit = engineClient.cardCredit(new CardCreditRequest(
                ctx.partnerId(), ctx.schemaName(), key));
            if (!credit.located()) {
                return new ReversalResponse(false);  // engine unreachable / not found → RC 25, do NOT flip
            }
            row.setReversed(true);
            idempotencyRepo.save(row);
            return new ReversalResponse(true);
```
(`key` is the existing composed `originalStan|terminalId|originalTransmissionDateTime`.) Add imports `CardCreditRequest`, `CardCreditResult`, `EngineClient`.

- [ ] **Step 4: Run, expect PASS:** `cd baas-card && ./mvnw test -Dtest=ReversalServiceTest -q`.

- [ ] **Step 5: Run full card suite + commit**
```bash
cd baas-card && ./mvnw test -q && cd ..
git add baas-card/src/main/java/com/nubbank/baas/card/authorize/ReversalService.java \
        baas-card/src/test/java/com/nubbank/baas/card/authorize/ReversalServiceTest.java
git commit -m "feat(card): reversal calls engine credit, fail-closed RC 25 (Stage 5 Task 10)"
```

---

# PHASE C — Provisioning trigger (DEF-1C-22)

## Task 11: Card provisioning endpoint

**Files:**
- Create: `baas-card/src/main/java/com/nubbank/baas/card/tenant/dto/ProvisionRequest.java`
- Create: `baas-card/src/main/java/com/nubbank/baas/card/tenant/InternalProvisioningController.java`
- Test: `baas-card/src/test/java/com/nubbank/baas/card/tenant/InternalProvisioningControllerTest.java`

- [ ] **Step 1: Create the DTO:**
```java
package com.nubbank.baas.card.tenant.dto;
import java.util.UUID;
public record ProvisionRequest(UUID partnerId, String schemaName) {}
```

- [ ] **Step 2: Create the controller** (guarded by the existing `@Order(1)` internal chain):
```java
package com.nubbank.baas.card.tenant;

import com.nubbank.baas.card.common.ApiResponse;
import com.nubbank.baas.card.tenant.dto.ProvisionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Internal provisioning trigger (DEF-1C-22). The engine calls this after its own
 * tenant migrations so the card schema objects exist in the partner schema. Idempotent
 * (CREATE SCHEMA IF NOT EXISTS + Flyway). Guarded by InternalServiceAuthFilter.
 */
@RestController
@RequestMapping("/internal/v1/provision")
@RequiredArgsConstructor
public class InternalProvisioningController {

    private final TenantProvisioningService provisioningService;

    @PostMapping
    public ApiResponse<Void> provision(@RequestBody ProvisionRequest req) {
        provisioningService.provision(req.partnerId(), req.schemaName());
        return ApiResponse.ok(null);
    }
}
```

- [ ] **Step 3: Write the failing test** `InternalProvisioningControllerTest.java` (Testcontainers): call `provisioningService.provision(UUID.randomUUID(), "partner_test_prov")` (directly, or via the controller) and assert the card-tenant tables exist in that schema (e.g. query `information_schema.tables` for `cards` in `partner_test_prov`), and that a second call does not throw (idempotent).

- [ ] **Step 4: Run, expect FAIL then PASS:** `cd baas-card && ./mvnw test -Dtest=InternalProvisioningControllerTest -q`.

- [ ] **Step 5: Commit**
```bash
git add baas-card/src/main/java/com/nubbank/baas/card/tenant/ \
        baas-card/src/test/java/com/nubbank/baas/card/tenant/InternalProvisioningControllerTest.java
git commit -m "feat(card): internal provisioning endpoint (Stage 5 Task 11)"
```

---

## Task 12: Engine → card provisioning call

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/tenant/CardProvisioningClient.java`
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/tenant/TenantProvisioningService.java`
- Modify: `baas-engine/src/main/resources/application.yml` (add `app.internal-service.card-base-url`)
- Test: `baas-engine/src/test/java/com/nubbank/baas/engine/tenant/TenantProvisioningCardCallTest.java`

- [ ] **Step 1: Add config** to engine `application.yml` under `app.internal-service`:
```yaml
  internal-service:
    shared-secret: ${INTERNAL_SERVICE_SECRET}   # MUST match baas-ncube's value; ≥32 chars
    card-base-url: ${CARD_BASE_URL:http://baas-card:8081}
```

- [ ] **Step 2: Create `CardProvisioningClient`:**
```java
package com.nubbank.baas.engine.tenant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Engine → card provisioning trigger (DEF-1C-22). Calls card's
 * {@code POST /internal/v1/provision} over the HMAC seam. A failure propagates so
 * {@code TenantProvisioningService} can mark the whole provisioning FAILED.
 */
@Component
public class CardProvisioningClient {

    private final RestTemplate http;
    private final String cardBaseUrl;

    public CardProvisioningClient(@Qualifier("internalServiceRestTemplate") RestTemplate http,
                                  @Value("${app.internal-service.card-base-url}") String cardBaseUrl) {
        this.http = http;
        this.cardBaseUrl = cardBaseUrl;
    }

    public void provision(UUID partnerId, String schemaName) {
        http.postForEntity(cardBaseUrl + "/internal/v1/provision",
            new HttpEntity<>(Map.of("partnerId", partnerId.toString(), "schemaName", schemaName)),
            Void.class);
    }
}
```

- [ ] **Step 3: Wire into `TenantProvisioningService`.** Add the dependency and call it after engine migrations, before the SUCCESS log. Constructor currently uses `@RequiredArgsConstructor` — add `private final CardProvisioningClient cardProvisioningClient;`. In `provision(...)`, after `runTenantMigrations(sandboxSchema);` and before the `INSERT ... SUCCESS`:
```java
            runTenantMigrations(schemaName);
            runTenantMigrations(sandboxSchema);
            cardProvisioningClient.provision(partnerId, schemaName);   // DEF-1C-22 — card schema objects
```
(The existing `catch` already logs FAILED and rethrows, so a card-call failure fails the whole provisioning — exactly the intended no-half-state behavior.)

- [ ] **Step 4: Write the failing test** `TenantProvisioningCardCallTest.java` — mock `CardProvisioningClient`:
```java
@Test void provisionCallsCardAfterMigrations() {
    service.provision(partnerId, "partner_test_cardcall");
    verify(cardProvisioningClient).provision(eq(partnerId), eq("partner_test_cardcall"));
}
@Test void cardFailureFailsProvisioning() {
    doThrow(new RuntimeException("card down")).when(cardProvisioningClient).provision(any(), any());
    assertThatThrownBy(() -> service.provision(partnerId, "partner_test_fail"))
        .isInstanceOf(RuntimeException.class);
    // and schema_provision_log has a FAILED row for this schema
}
```
(This is a Testcontainers test because `provision` runs real Flyway; inject a Mockito mock for `CardProvisioningClient` via `@MockBean` if using `@SpringBootTest`, or construct the service manually with a mock + real `DataSource`/`JdbcTemplate`.)

- [ ] **Step 5: Run, expect FAIL then PASS:** `cd baas-engine && ./mvnw test -Dtest=TenantProvisioningCardCallTest -q`.

- [ ] **Step 6: Run full engine suite + commit**
```bash
cd baas-engine && ./mvnw test -q && cd ..
git add baas-engine/src/main/java/com/nubbank/baas/engine/tenant/ \
        baas-engine/src/main/resources/application.yml \
        baas-engine/src/test/java/com/nubbank/baas/engine/tenant/TenantProvisioningCardCallTest.java
git commit -m "feat(engine): trigger card provisioning after partner onboard (Stage 5 Task 12)"
```

---

# PHASE D — FEP authorization audit log (DEF-1C-24)

## Task 13: FEP datastore (deps + config + migration)

**Files:**
- Modify: `baas-fep/pom.xml`
- Modify: `baas-fep/src/main/resources/application.yml`
- Create: `baas-fep/src/main/resources/db/migration/fep/V1__authorization_log.sql`
- Test: `baas-fep/src/test/java/com/nubbank/baas/fep/audit/FepFlywayContextTest.java`

- [ ] **Step 1: Add pom dependencies** (after the existing starters):
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```
(Confirm the Testcontainers BOM/version is managed by the Spring Boot parent or another service's pom; if `baas-engine`/`baas-card` pin a `testcontainers.version` property, copy it.)

- [ ] **Step 2: Add datasource + flyway to `application.yml`** under `spring:`:
```yaml
spring:
  application:
    name: baas-fep
  datasource:
    url: ${DATASOURCE_URL}
    username: ${DATASOURCE_USERNAME}
    password: ${DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver
  flyway:
    enabled: true
    locations: classpath:db/migration/fep
    schemas: fep
    default-schema: fep
    create-schemas: true
    baseline-on-migrate: true
```

- [ ] **Step 3: Write the migration** `V1__authorization_log.sql`:
```sql
CREATE TABLE authorization_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    received_at     TIMESTAMPTZ NOT NULL,
    mti             VARCHAR(4)  NOT NULL,
    stan            VARCHAR(6),
    terminal_id     VARCHAR(8),
    bin             VARCHAR(8),
    pan_last4       VARCHAR(4),
    partner_id      UUID,
    schema_name     VARCHAR(120),
    amount_minor    BIGINT,
    currency        VARCHAR(3),
    decision        VARCHAR(10),
    response_code   VARCHAR(2),
    reversal        BOOLEAN NOT NULL,
    latency_ms      INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fep_auth_log_received_at ON authorization_log (received_at);
```

- [ ] **Step 4: Write the context test** `FepFlywayContextTest.java` — `@SpringBootTest` + Testcontainers PostgreSQL, asserting the context loads and `fep.authorization_log` exists (query `information_schema.tables`). This proves the deps + flyway + schema wire up.

- [ ] **Step 5: Run, expect PASS:** `cd baas-fep && ./mvnw test -Dtest=FepFlywayContextTest -q`.

- [ ] **Step 6: Commit**
```bash
git add baas-fep/pom.xml baas-fep/src/main/resources/application.yml \
        baas-fep/src/main/resources/db/migration/fep/V1__authorization_log.sql \
        baas-fep/src/test/java/com/nubbank/baas/fep/audit/FepFlywayContextTest.java
git commit -m "feat(fep): datastore + authorization_log migration (Stage 5 Task 13)"
```

---

## Task 14: FEP audit service

**Files:**
- Create: `baas-fep/src/main/java/com/nubbank/baas/fep/audit/FepAuthorizationLog.java`
- Create: `baas-fep/src/main/java/com/nubbank/baas/fep/audit/AuthorizationAuditService.java`
- Test: `baas-fep/src/test/java/com/nubbank/baas/fep/audit/AuthorizationAuditServiceTest.java`

- [ ] **Step 1: Create the row record** `FepAuthorizationLog.java`:
```java
package com.nubbank.baas.fep.audit;

import java.time.Instant;

/** One authorization/reversal decision as recorded by the FEP. Carries ONLY BIN + last4 (no full PAN). */
public record FepAuthorizationLog(
    Instant receivedAt, String mti, String stan, String terminalId,
    String bin, String panLast4, String partnerId, String schemaName,
    Long amountMinor, String currency, String decision, String responseCode,
    boolean reversal, Integer latencyMs) {}
```

- [ ] **Step 2: Create the service** (JdbcTemplate insert; best-effort — swallows all errors):
```java
package com.nubbank.baas.fep.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

/**
 * Best-effort FEP authorization audit (DEF-1C-24). A write failure is logged and
 * swallowed — it NEVER alters the ISO 8583 response. Stores only BIN + last4.
 */
@Service
public class AuthorizationAuditService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationAuditService.class);
    private final JdbcTemplate jdbc;

    public AuthorizationAuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(FepAuthorizationLog e) {
        try {
            jdbc.update(
                "INSERT INTO fep.authorization_log " +
                "(received_at, mti, stan, terminal_id, bin, pan_last4, partner_id, schema_name, " +
                " amount_minor, currency, decision, response_code, reversal, latency_ms) " +
                "VALUES (?,?,?,?,?,?,?::uuid,?,?,?,?,?,?,?)",
                Timestamp.from(e.receivedAt()), e.mti(), e.stan(), e.terminalId(), e.bin(), e.panLast4(),
                e.partnerId(), e.schemaName(), e.amountMinor(), e.currency(), e.decision(),
                e.responseCode(), e.reversal(), e.latencyMs());
        } catch (Exception ex) {
            log.warn("FEP audit write failed (swallowed): {}", ex.getMessage());
        }
    }
}
```

- [ ] **Step 3: Write the failing test** `AuthorizationAuditServiceTest.java` (Testcontainers): record a row, query it back, assert `bin`/`pan_last4` present and **no column holds a full PAN** (assert the table has no full-PAN column by construction; assert `pan_last4` length ≤ 4). Add a best-effort case: construct the service with a `JdbcTemplate` pointed at a closed datasource (or mock that throws) and assert `record(...)` does **not** throw.

- [ ] **Step 4: Run, expect FAIL then PASS:** `cd baas-fep && ./mvnw test -Dtest=AuthorizationAuditServiceTest -q`.

- [ ] **Step 5: Commit**
```bash
git add baas-fep/src/main/java/com/nubbank/baas/fep/audit/ \
        baas-fep/src/test/java/com/nubbank/baas/fep/audit/AuthorizationAuditServiceTest.java
git commit -m "feat(fep): best-effort authorization audit service (Stage 5 Task 14)"
```

---

## Task 15: Wire audit into FEP handlers

**Files:**
- Modify: `baas-fep/src/main/java/com/nubbank/baas/fep/router/AuthorizationHandler.java`
- Modify: `baas-fep/src/main/java/com/nubbank/baas/fep/router/ReversalHandler.java`
- Test: `baas-fep/src/test/java/com/nubbank/baas/fep/router/AuthorizationHandlerAuditTest.java`

- [ ] **Step 1: Write the failing test** `AuthorizationHandlerAuditTest.java` — construct `AuthorizationHandler` with a mocked `AuthorizationAuditService` + the existing `StubCardClient`/`BinResolver`/`IsoMessageFactory` test doubles. Send a routed `0100`, assert `auditService.record(...)` is called once with the decision's `responseCode`, `bin` = first 8 of the PAN, `panLast4` = last 4. Then make the mock `record(...)` throw and assert `handle(...)` still returns the normal `0110` (best-effort never breaks the response).

- [ ] **Step 2: Run, expect FAIL** (constructor arity changed).

- [ ] **Step 3: Implement.** Add `private final AuthorizationAuditService auditService;` to `AuthorizationHandler` (it's `@RequiredArgsConstructor`). Capture `Instant start = Instant.now();` at the top of `handle`, derive `bin`/`last4` from the PAN locally (never log the PAN), and call `auditService.record(...)` at each return point with the chosen RC. Helper to keep it DRY:
```java
private void audit(ISOMsg req, String pan, var route, AuthorizationDecision decision,
                   String rc, Instant start) {
    String bin = pan == null ? null : pan.substring(0, Math.min(8, pan.length()));
    String last4 = pan == null || pan.length() < 4 ? null : pan.substring(pan.length() - 4);
    auditService.record(new FepAuthorizationLog(
        start, MessageRouter.mti(req), field(req, IsoField.STAN), field(req, IsoField.TERMINAL_ID),
        bin, last4,
        route == null ? null : route.partnerId().toString(),
        route == null ? null : route.schemaName(),
        parseAmountOrNull(req), field(req, IsoField.CURRENCY),
        decision == null ? null : decision.decision(), rc, false,
        (int) java.time.Duration.between(start, Instant.now()).toMillis()));
}
```
Call `audit(...)` before each `return` (unrouteable → rc "91", route null, decision null; formatError → rc "30"; main path → rc `decision.responseCode()`, route present, decision present). `ReversalHandler` mirrors this with `reversal=true`, decision null, rc the `"00"/"25"/"91"/"30"` it returns. (Keep the PAN-safety invariants: the PAN is only used to compute bin/last4 here, never logged.)

- [ ] **Step 4: Run, expect PASS:** `cd baas-fep && ./mvnw test -Dtest=AuthorizationHandlerAuditTest -q`.

- [ ] **Step 5: Run full FEP suite + commit**
```bash
cd baas-fep && ./mvnw test -q && cd ..
git add baas-fep/src/main/java/com/nubbank/baas/fep/router/ \
        baas-fep/src/test/java/com/nubbank/baas/fep/router/AuthorizationHandlerAuditTest.java
git commit -m "feat(fep): record best-effort authorization audit in handlers (Stage 5 Task 15)"
```

---

# PHASE E — Cross-service parity + docs

## Task 16: Contract shape tests + signer parity

**Files:**
- Create: `baas-engine/src/test/java/com/nubbank/baas/engine/account/CardMoneyContractShapeTest.java`
- Create: `baas-card/src/test/java/com/nubbank/baas/card/engine/CardMoneyContractShapeTest.java`
- Create: `baas-card/src/test/java/com/nubbank/baas/card/engine/SignerParityTest.java`

- [ ] **Step 1: Write the shape tests** (mirror the existing `AuthorizationContractShapeTest` reflection pattern). Each asserts the record components (names + types, in order) of the card-debit / card-credit / account-lookup request+result records match the canonical list on **both** sides. Canonical lists:
  - `card-debit` request: `partnerId:String, schemaName:String, accountId:UUID, authKey:String, amount:BigDecimal, currency:String`
  - `card-debit` result: `outcome` (engine: enum `CardAuthOutcome`; card: `String`) — assert the engine enum's names are exactly `{DEBITED, INSUFFICIENT, ACCOUNT_INVALID, CURRENCY_MISMATCH}` and that the card maps each.
  - `card-credit` request: `partnerId:String, schemaName:String, authKey:String`; result: `located:boolean`.
  - `account-lookup` request: `partnerId:String, schemaName:String, accountId:UUID`; result: `exists:boolean, active:boolean, currencyCode:String`.

- [ ] **Step 2: Write `SignerParityTest`** — assert the card's outbound `SigningInterceptor` produces a signature the engine's `InternalServiceAuthFilter` accepts for the same `METHOD|rawPath|ts|body`. Construct both with the same 32-char secret; sign a sample request via the interceptor; feed the headers + body into the engine filter (as in Task 1's test) and assert it passes. This pins the `getRawPath()` (signer) vs `getRequestURI()` (validator) equivalence for a representative `/internal/v1/card-debit` path.

- [ ] **Step 3: Run both module suites:** `cd baas-engine && ./mvnw test -q && cd ../baas-card && ./mvnw test -q && cd ..`. Expected: all PASS.

- [ ] **Step 4: Commit**
```bash
git add baas-engine/src/test/java/com/nubbank/baas/engine/account/CardMoneyContractShapeTest.java \
        baas-card/src/test/java/com/nubbank/baas/card/engine/CardMoneyContractShapeTest.java \
        baas-card/src/test/java/com/nubbank/baas/card/engine/SignerParityTest.java
git commit -m "test(stage5): cross-service shape + signer parity tests (Stage 5 Task 16)"
```

---

## Task 17: Documentation + session gate

**Files:**
- Modify: `CLAUDE.md`, `baas-log.md`, `docs/api-reference.html`, `docs/contracts/phase1c-interfaces.md`
- (`docs/deferred-items.md` already updated: DEF-1C-22/23/24/25 closed, DEF-1C-27 added.)

- [ ] **Step 1: Run all three module suites** (the session gate's build verification):
```bash
cd baas-engine && ./mvnw test -q && cd ../baas-card && ./mvnw test -q && cd ../baas-fep && ./mvnw test -q && cd ..
```
Expected: all BUILD SUCCESS. Record the totals.

- [ ] **Step 2: Update `docs/contracts/phase1c-interfaces.md`** — add §2c (card→engine card-debit/card-credit/account-lookup) and §2d (engine→card provision) describing the frozen shapes, the `authKey`, the numeric→alpha currency translation boundary, and the fail-closed RC mapping. Mark DEF-1C-23/25 fund half as closed.

- [ ] **Step 3: Update `docs/api-reference.html`** — add the engine `/internal/v1/{card-debit,card-credit,account-lookup}` and card `/internal/v1/provision` internal endpoints (note: internal/HMAC-only, not partner-facing).

- [ ] **Step 4: Update `CLAUDE.md`** — Confirmed Platform Versions SHA (final Stage 5 commit), Module Catalogue (Stage 5 ✅), and add Known Gotchas: (a) numeric-vs-alpha currency seam (card translates DE49→ISO alpha; engine compares alpha); (b) engine money-dedupe authority via `card_auth_debit.auth_key`; (c) the PartnerContext-before-`@Transactional` discipline in `InternalCardMoneyController`.

- [ ] **Step 5: Update `baas-log.md`** — new Session entry at the top: summary + final SHA, New/Updated Files table, Key Decisions (Approach A, single-message debit, numeric→alpha), Build Verification (the Step 1 totals), and the **Confirmed Platform Versions** block (engine SHA from `git log --oneline -1 -- baas-engine/`).

- [ ] **Step 6: Commit**
```bash
git add CLAUDE.md baas-log.md docs/api-reference.html docs/contracts/phase1c-interfaces.md docs/deferred-items.md
git commit -m "docs(stage5): close DEF-1C-22/23/24/25, contracts + api-reference + log (Stage 5 Task 17)"
```

- [ ] **Step 7: Finish the branch** using `superpowers:finishing-a-development-branch` (verify all suites green → present merge/PR options).

---

## Plan self-review checklist (run before execution)

- **Spec coverage:** §3 flows → Tasks 3/4/9/10 (debit/credit/authorize/reversal); §4 engine → 1/2/3/4/5; §5 card → 6/7/8/9/10; §4.1 provisioning trigger → 11/12; §6 FEP audit → 13/14/15; §7 testing → embedded + 16; §8 non-goals → respected (no settlement, no holds, no FX beyond RC 57, single-Transaction path). ✅
- **RC mapping consistent:** 00/12/51/54/56/57/61/62/78/91 — Task 9 switch matches §5.6. ✅
- **`authKey` identical** on debit (Task 3/9) and reversal (Task 4/10) — `stan|terminalId|transmissionDateTime`. ✅
- **Currency:** card→engine alpha everywhere (Tasks 3/6/9 + shape test 16). ✅
- **Config keys:** card `app.internal-service.engine-base-url` (exists); engine `app.internal-service.card-base-url` (Task 12). ✅
