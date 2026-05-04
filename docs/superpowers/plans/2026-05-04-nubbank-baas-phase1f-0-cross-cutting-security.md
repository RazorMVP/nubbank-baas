# Phase 1F-0 — Cross-Cutting Security Baseline

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the most urgent security exposures across both services in one focused PR — auth enforcement on `baas-ncube`, inter-service authentication, PII masking in logs, and stub-mode guards. After Plan 0 merges, the platform's network surface is no longer trivially exploitable, and Plans 1F-A and 1F-B can build on a clean security baseline.

**Architecture:** Mirror the proven `AuthEnforcementFilter` pattern from `baas-engine` onto `baas-ncube`. Add an HMAC-signed `Authorization: Internal <token>` mechanism for `baas-engine` ↔ `baas-ncube` calls. Add a Logback custom converter (`PiiMaskingConverter`) that pattern-masks BVN/NIN/account numbers anywhere they appear in log lines, wired into both services. Add a `@PostConstruct` guard in `baas-ncube` that fails startup when stub mode collides with the `prod` profile, plus a response header and replaced stub data so stubbed responses are unambiguous.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Security, Logback, JUnit 5 + AssertJ + Spring `@WebMvcTest` / `@SpringBootTest`.

**Branch:** `feature/phase1f-0-cross-cutting`, branched from `main` at `17c2e3e`.

**Findings addressed:** 1B C1, 1B C2, 1B C5, 1B I1, 1B I3, 1B I7. (4 critical + 2 important.)

---

## File Structure

### New files

| File | Purpose |
|------|---------|
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/AuthEnforcementFilter.java` | Mirror of engine's filter — rejects `/baas/v1/**` requests with 401 if no auth resolved |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/NcubeRequestContext.java` | Tiny ThreadLocal carrying the validated internal-service identity |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/InternalServiceAuthFilter.java` | Validates `Authorization: Internal <token>` HMAC; populates `NcubeRequestContext` |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/StubModeGuard.java` | `@PostConstruct` — refuses to boot when stub mode + `prod` profile collide |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/StubResponseHeaderInterceptor.java` | Adds `X-NubBank-Stubbed: true` to every response when `baas.nps.live=false` |
| `baas-engine/src/main/java/com/nubbank/baas/engine/config/InternalServiceClient.java` | RestTemplate interceptor that signs outbound requests with HMAC for ncube calls |
| `baas-engine/src/main/java/com/nubbank/baas/engine/common/PiiMaskingConverter.java` | Logback converter that masks BVN/NIN/NUBAN/PAN in log output |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/common/PiiMaskingConverter.java` | Same converter, copied into ncube (services can't share modules without a shared lib) |
| `baas-engine/src/main/resources/logback-spring.xml` | Wires `PiiMaskingConverter` for engine logs |
| `baas-ncube/src/main/resources/logback-spring.xml` | Wires `PiiMaskingConverter` for ncube logs |
| `baas-ncube/src/test/java/com/nubbank/baas/ncube/config/AuthEnforcementFilterTest.java` | Asserts 401 on missing auth, 200 on valid path |
| `baas-ncube/src/test/java/com/nubbank/baas/ncube/config/InternalServiceAuthFilterTest.java` | Asserts HMAC validation accepts good signatures, rejects bad |
| `baas-ncube/src/test/java/com/nubbank/baas/ncube/config/StubModeGuardTest.java` | Asserts startup fails with prod profile + stub mode |
| `baas-ncube/src/test/java/com/nubbank/baas/ncube/identity/NcubeIdentityControllerTest.java` | Asserts stubbed response uses `00000000000`, has `X-NubBank-Stubbed` header, requires CBN media type |
| `baas-engine/src/test/java/com/nubbank/baas/engine/common/PiiMaskingConverterTest.java` | Asserts BVN/NIN/account/PAN are masked across various log line shapes |

### Modified files

| File | Change |
|------|--------|
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/SecurityConfig.java` | Replace `permitAll()` with proper filter chain — add both filters; restrict `/actuator/info` |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/identity/NcubeIdentityController.java` | Replace stubbed BVN/NIN echo with `00000000000`; add CBN media type on `consumes`/`produces` |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/account/NcubeAccountController.java` | Add CBN media type on `consumes`/`produces` |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/consent/NcubeConsentController.java` | Add CBN media type on `consumes`/`produces` |
| `baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/NcubePaymentController.java` | Add CBN media type on `consumes`/`produces` |
| `baas-ncube/src/main/resources/application.yml` | Add `app.internal-service.shared-secret` env-var (no default), `management.endpoints.web.exposure.include: health` (drop `info`) |
| `baas-engine/src/main/resources/application.yml` | Add `app.internal-service.shared-secret` env-var (no default — fails fast) |
| `baas-engine/src/main/java/com/nubbank/baas/engine/openbanking/NcubeAccountClient.java` *(if exists)* | Replace plain RestTemplate with `InternalServiceClient` |

---

## Task 1 — Add `AuthEnforcementFilter` to `baas-ncube`

Mirrors the proven engine filter. Rejects unauthenticated `/baas/v1/**` requests with a JSON 401 envelope.

**Files:**
- Create: `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/AuthEnforcementFilter.java`
- Test: `baas-ncube/src/test/java/com/nubbank/baas/ncube/config/AuthEnforcementFilterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.ncube.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AuthEnforcementFilterTest {

    @Autowired private TestRestTemplate rest;

    @Test
    void missingAuth_to_protected_path_returns_401() {
        ResponseEntity<Map> resp = rest.exchange(
            "/baas/v1/ncube/identity/verify-bvn",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("bvn", "12345678901"), new HttpHeaders()),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).extracting("errors")
            .asList().first().asInstanceOf(MAP).extractingByKey("code").isEqualTo("MISSING_AUTH");
    }

    @Test
    void public_path_actuator_health_returns_200_without_auth() {
        ResponseEntity<Map> resp = rest.exchange(
            "/actuator/health", HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(resp.getStatusCode().value()).isNotEqualTo(401);
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```
cd baas-ncube && ./mvnw -q -Dtest=AuthEnforcementFilterTest test
```
Expected: first test FAILS with status 200 (current `permitAll()` lets the request through).

- [ ] **Step 3: Create the filter**

```java
package com.nubbank.baas.ncube.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class AuthEnforcementFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (requiresAuth(path) && NcubeRequestContext.get() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                "{\"data\":null,\"errors\":[{\"code\":\"MISSING_AUTH\","
                + "\"message\":\"Authorization header required — Internal HMAC token from baas-engine\"}]}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean requiresAuth(String path) {
        if (path == null) return false;
        if (path.startsWith("/actuator/health")) return false;
        if (path.startsWith("/v3/api-docs")) return false;
        if (path.startsWith("/swagger-ui")) return false;
        return path.startsWith("/baas/v1/");
    }
}
```

- [ ] **Step 4: Wire it via SecurityConfig (covered in Task 4)**. Skip until Task 4 — the filter is registered as a `@Component` but Spring Security needs explicit ordering done after `InternalServiceAuthFilter` is also created.

- [ ] **Step 5: Commit**

```bash
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/config/AuthEnforcementFilter.java \
         baas-ncube/src/test/java/com/nubbank/baas/ncube/config/AuthEnforcementFilterTest.java
git commit -m "feat(baas-ncube): add AuthEnforcementFilter — 401 on /baas/v1/** without auth (1B C2)"
```

---

## Task 2 — Add `NcubeRequestContext` ThreadLocal

Holds the validated internal-service identity so `AuthEnforcementFilter` and downstream code can read it. Mirrors the shape of engine's `PartnerContext` but minimal for ncube's needs.

**Files:**
- Create: `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/NcubeRequestContext.java`

- [ ] **Step 1: Write the file**

```java
package com.nubbank.baas.ncube.config;

/**
 * Thread-local request context for baas-ncube.
 * Set by {@link InternalServiceAuthFilter} after HMAC validation.
 * Cleared in finally block. Read by {@link AuthEnforcementFilter} to gate /baas/v1/**.
 */
public final class NcubeRequestContext {
    private static final ThreadLocal<String> CALLER = new ThreadLocal<>();
    private NcubeRequestContext() {}
    public static void set(String caller) { CALLER.set(caller); }
    public static String get() { return CALLER.get(); }
    public static void clear() { CALLER.remove(); }
}
```

- [ ] **Step 2: Commit**

```bash
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/config/NcubeRequestContext.java
git commit -m "feat(baas-ncube): add NcubeRequestContext ThreadLocal for inter-service identity (1B I7)"
```

---

## Task 3 — Add `InternalServiceAuthFilter` (HMAC validation on inbound, body-signed)

Validates `Authorization: Internal <hex-hmac>` where the HMAC is `HmacSHA256(sharedSecret, METHOD + "|" + PATH + "|" + TIMESTAMP + "|" + sha256Hex(body))` and the timestamp is in `X-Internal-Timestamp` header. Rejects requests with timestamps older than 60 seconds (replay protection). Including the body's SHA-256 in the signed string defends against payload tampering between `baas-engine` and `baas-ncube`.

For GET/DELETE requests with no body, `sha256Hex(body)` is the well-known empty-string SHA-256: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

The filter must read the request body without consuming it for the controller — wrap with Spring's `ContentCachingRequestWrapper` (or equivalent that buffers bytes for downstream readers).

**Files:**
- Create: `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/InternalServiceAuthFilter.java`
- Test: `baas-ncube/src/test/java/com/nubbank/baas/ncube/config/InternalServiceAuthFilterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubbank.baas.ncube.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InternalServiceAuthFilterTest {

    private static final String SECRET = "test-shared-secret-min-32-chars-long-okay";
    private InternalServiceAuthFilter filter;

    @BeforeEach
    void setup() { filter = new InternalServiceAuthFilter(SECRET); }

    @AfterEach
    void cleanup() { NcubeRequestContext.clear(); }

    @Test
    void valid_hmac_with_correct_body_populates_context() throws Exception {
        String body = "{\"bvn\":\"12345678901\"}";
        long ts = Instant.now().getEpochSecond();
        String bodyHash = sha256Hex(body);
        String sig = hmac("POST|/baas/v1/ncube/identity/verify-bvn|" + ts + "|" + bodyHash);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/baas/v1/ncube/identity/verify-bvn");
        req.setContent(body.getBytes());
        req.addHeader("Authorization", "Internal " + sig);
        req.addHeader("X-Internal-Timestamp", String.valueOf(ts));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> { assertThat(NcubeRequestContext.get()).isEqualTo("baas-engine"); return null; })
            .when(chain).doFilter(any(), any());
        filter.doFilter(req, resp, chain);
    }

    @Test
    void tampered_body_does_not_populate_context() throws Exception {
        // Sign for body A, send body B — must reject
        String signedBody = "{\"bvn\":\"12345678901\"}";
        String tamperedBody = "{\"bvn\":\"99999999999\"}";
        long ts = Instant.now().getEpochSecond();
        String sig = hmac("POST|/baas/v1/ncube/identity/verify-bvn|" + ts + "|" + sha256Hex(signedBody));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/baas/v1/ncube/identity/verify-bvn");
        req.setContent(tamperedBody.getBytes());
        req.addHeader("Authorization", "Internal " + sig);
        req.addHeader("X-Internal-Timestamp", String.valueOf(ts));
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(NcubeRequestContext.get()).isNull();
    }

    @Test
    void invalid_hmac_does_not_populate_context() throws Exception {
        long ts = Instant.now().getEpochSecond();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/baas/v1/ncube/identity/verify-bvn");
        req.setContent("{\"bvn\":\"12345678901\"}".getBytes());
        req.addHeader("Authorization", "Internal deadbeef");
        req.addHeader("X-Internal-Timestamp", String.valueOf(ts));
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(NcubeRequestContext.get()).isNull();
    }

    @Test
    void stale_timestamp_does_not_populate_context() throws Exception {
        String body = "";
        long ts = Instant.now().getEpochSecond() - 120; // 2 minutes old
        String sig = hmac("GET|/baas/v1/ncube/health|" + ts + "|" + sha256Hex(body));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/baas/v1/ncube/health");
        req.addHeader("Authorization", "Internal " + sig);
        req.addHeader("X-Internal-Timestamp", String.valueOf(ts));
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(NcubeRequestContext.get()).isNull();
    }

    @Test
    void empty_body_uses_well_known_empty_sha256() throws Exception {
        // GET with no body — body hash is SHA-256 of empty string
        long ts = Instant.now().getEpochSecond();
        String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assertThat(sha256Hex("")).isEqualTo(emptyHash);
        String sig = hmac("GET|/baas/v1/ncube/health|" + ts + "|" + emptyHash);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/baas/v1/ncube/health");
        req.addHeader("Authorization", "Internal " + sig);
        req.addHeader("X-Internal-Timestamp", String.valueOf(ts));
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> { assertThat(NcubeRequestContext.get()).isEqualTo("baas-engine"); return null; })
            .when(chain).doFilter(any(), any());
        filter.doFilter(req, new MockHttpServletResponse(), chain);
    }

    private String hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
    }

    private String sha256Hex(String data) throws Exception {
        return HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(data.getBytes()));
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```
cd baas-ncube && ./mvnw -q -Dtest=InternalServiceAuthFilterTest test
```
Expected: compilation error — `InternalServiceAuthFilter` not yet defined.

- [ ] **Step 3: Implement the filter**

```java
package com.nubbank.baas.ncube.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Validates inbound HMAC-signed internal-service requests from baas-engine.
 * Header format:
 *   Authorization: Internal <hex-hmac>
 *   X-Internal-Timestamp: <epoch-seconds>
 * HMAC content: METHOD + "|" + PATH + "|" + TIMESTAMP + "|" + sha256Hex(body)
 * Replay window: 60 seconds.
 *
 * Body bytes are read via ContentCachingRequestWrapper so the controller can still
 * read them downstream (raw HttpServletRequest's input stream is single-use).
 *
 * On valid signature, populates NcubeRequestContext with caller identity.
 * On invalid signature (or tampered body), leaves context null —
 * AuthEnforcementFilter then rejects with 401.
 */
@Component
@Slf4j
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    private static final long REPLAY_WINDOW_SECONDS = 60;
    private final String sharedSecret;

    public InternalServiceAuthFilter(@Value("${app.internal-service.shared-secret}") String sharedSecret) {
        if (sharedSecret == null || sharedSecret.length() < 32) {
            throw new IllegalStateException("app.internal-service.shared-secret must be set and ≥32 chars");
        }
        this.sharedSecret = sharedSecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        // Wrap to allow body to be read here and downstream
        ContentCachingRequestWrapper wrapped = (req instanceof ContentCachingRequestWrapper w)
            ? w : new ContentCachingRequestWrapper(req);
        try {
            // Force the wrapper to read the body once so the cache is populated.
            // (Downstream controller will read from the cache.)
            byte[] body = readAllBytes(wrapped);
            String authHeader = wrapped.getHeader("Authorization");
            String tsHeader = wrapped.getHeader("X-Internal-Timestamp");
            if (authHeader != null && authHeader.startsWith("Internal ") && tsHeader != null) {
                long ts = Long.parseLong(tsHeader);
                long now = Instant.now().getEpochSecond();
                if (Math.abs(now - ts) <= REPLAY_WINDOW_SECONDS) {
                    String provided = authHeader.substring("Internal ".length());
                    String bodyHash = sha256Hex(body);
                    String signedString = wrapped.getMethod() + "|"
                        + wrapped.getRequestURI() + "|"
                        + ts + "|"
                        + bodyHash;
                    String expected = computeHmac(signedString);
                    if (constantTimeEquals(provided, expected)) {
                        NcubeRequestContext.set("baas-engine");
                    } else {
                        log.warn("Internal-service HMAC mismatch for {} {} (body={}b)",
                            wrapped.getMethod(), wrapped.getRequestURI(), body.length);
                    }
                } else {
                    log.warn("Internal-service timestamp out of window: ts={} now={}", ts, now);
                }
            }
            chain.doFilter(wrapped, resp);
        } finally {
            NcubeRequestContext.clear();
        }
    }

    private byte[] readAllBytes(ContentCachingRequestWrapper wrapped) throws IOException {
        // Force the inputStream to be consumed so getContentAsByteArray returns full body.
        // Spring's wrapper buffers as it's read.
        wrapped.getInputStream().readAllBytes();
        return wrapped.getContentAsByteArray();
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC computation failed", ex);
        }
    }

    private String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 computation failed", ex);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
```

> **Note on `ContentCachingRequestWrapper` body re-reading:** Spring MVC's controllers use `HttpMessageConverter` to deserialize the body. After `ContentCachingRequestWrapper` buffers the bytes, the controller reads from the cache via the wrapper's overridden `getInputStream()`. This is the standard Spring pattern for body-inspecting filters and works with `@RequestBody`-annotated methods.

- [ ] **Step 4: Add config property to `baas-ncube/src/main/resources/application.yml`**

Replace the entire file's `baas:` block (or append):

```yaml
app:
  internal-service:
    shared-secret: ${INTERNAL_SERVICE_SECRET}   # MUST be set; ≥32 chars; matches baas-engine's value
```

- [ ] **Step 5: Run tests; expect PASS**

```
cd baas-ncube && ./mvnw -q -Dtest=InternalServiceAuthFilterTest test
```

- [ ] **Step 6: Commit**

```bash
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/config/InternalServiceAuthFilter.java \
         baas-ncube/src/test/java/com/nubbank/baas/ncube/config/InternalServiceAuthFilterTest.java \
         baas-ncube/src/main/resources/application.yml
git commit -m "feat(baas-ncube): InternalServiceAuthFilter — HMAC-SHA256 inter-service auth (1B I7)"
```

---

## Task 4 — Replace `permitAll()` in `SecurityConfig` and wire both filters

Replace the existing 22-line `SecurityConfig` with one that mirrors `baas-engine`'s structure: filter ordering `InternalServiceAuthFilter → AuthEnforcementFilter`, restrict `/actuator/info`, leave `permitAll()` only for the documented public paths.

**Files:**
- Modify: `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/SecurityConfig.java`
- Modify: `baas-ncube/src/main/resources/application.yml` (drop `info` from actuator exposure)

- [ ] **Step 1: Update `SecurityConfig.java`** — replace the file with:

```java
package com.nubbank.baas.ncube.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final InternalServiceAuthFilter internalServiceAuthFilter;
    private final AuthEnforcementFilter authEnforcementFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 1. InternalServiceAuthFilter validates HMAC signature, sets NcubeRequestContext
            // 2. AuthEnforcementFilter rejects with 401 if context is null on /baas/v1/**
            .addFilterBefore(internalServiceAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(authEnforcementFilter, InternalServiceAuthFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().permitAll()  // AuthEnforcementFilter handles 401 envelope
            );
        return http.build();
    }
}
```

- [ ] **Step 2: Drop `info` from actuator exposure in `application.yml`**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health   # was: health,info — info leaks git.commit.id and active profile
```

- [ ] **Step 3: Run the full test suite**

```
cd baas-ncube && ./mvnw -q test
```
Expected: all tests in `AuthEnforcementFilterTest` and `InternalServiceAuthFilterTest` pass.

- [ ] **Step 4: Commit**

```bash
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/config/SecurityConfig.java \
         baas-ncube/src/main/resources/application.yml
git commit -m "feat(baas-ncube): wire AuthEnforcementFilter + InternalServiceAuthFilter; drop /actuator/info (1B C2, I1)"
```

---

## Task 5 — Add `InternalServiceClient` to `baas-engine` (outbound HMAC signing)

When `baas-engine` calls `baas-ncube`, it must sign each request with the same HMAC scheme `InternalServiceAuthFilter` validates. This task adds the signing interceptor.

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/config/InternalServiceClient.java`
- Create: `baas-engine/src/test/java/com/nubbank/baas/engine/config/InternalServiceClientTest.java`
- Modify: `baas-engine/src/main/resources/application.yml` (add `app.internal-service.shared-secret` and `app.ncube.base-url`)
- Modify (if exists): `baas-engine/src/main/java/com/nubbank/baas/engine/openbanking/NcubeAccountClient.java` (and any other class that calls ncube via RestTemplate) — inject `RestTemplate` provided by `InternalServiceClient` instead of constructing their own.

- [ ] **Step 1: Write the test**

```java
package com.nubbank.baas.engine.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.assertThat;

class InternalServiceClientTest {

    private static final String SECRET = "test-shared-secret-min-32-chars-long-okay";

    @Test
    void interceptor_adds_authorization_and_timestamp_headers() throws Exception {
        InternalServiceClient.SigningInterceptor sut = new InternalServiceClient.SigningInterceptor(SECRET);
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.POST,
            URI.create("http://baas-ncube:8082/baas/v1/ncube/identity/verify-bvn"));
        ClientHttpRequestExecution exec = (request, body) ->
            new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        sut.intercept(req, new byte[0], exec);

        assertThat(req.getHeaders().getFirst("Authorization")).startsWith("Internal ");
        assertThat(req.getHeaders().getFirst("X-Internal-Timestamp")).isNotBlank();
    }

    @Test
    void signature_includes_body_hash() throws Exception {
        InternalServiceClient.SigningInterceptor sut = new InternalServiceClient.SigningInterceptor(SECRET);
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.POST,
            URI.create("http://baas-ncube:8082/baas/v1/ncube/identity/verify-bvn"));
        byte[] body = "{\"bvn\":\"12345678901\"}".getBytes();
        ClientHttpRequestExecution exec = (request, b) ->
            new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        sut.intercept(req, body, exec);

        String sig = req.getHeaders().getFirst("Authorization").substring("Internal ".length());
        String ts = req.getHeaders().getFirst("X-Internal-Timestamp");
        // Reconstruct expected signature with body hash to confirm correctness
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String signedString = "POST|/baas/v1/ncube/identity/verify-bvn|" + ts + "|" + bodyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(signedString.getBytes()));
        assertThat(sig).isEqualTo(expected);
    }

    @Test
    void empty_body_uses_empty_string_sha256() throws Exception {
        InternalServiceClient.SigningInterceptor sut = new InternalServiceClient.SigningInterceptor(SECRET);
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET,
            URI.create("http://baas-ncube:8082/baas/v1/ncube/health"));
        ClientHttpRequestExecution exec = (request, body) ->
            new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        sut.intercept(req, new byte[0], exec);

        String sig = req.getHeaders().getFirst("Authorization").substring("Internal ".length());
        String ts = req.getHeaders().getFirst("X-Internal-Timestamp");
        String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String signedString = "GET|/baas/v1/ncube/health|" + ts + "|" + emptyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(signedString.getBytes()));
        assertThat(sig).isEqualTo(expected);
    }
}
```

- [ ] **Step 2: Implement `InternalServiceClient`**

```java
package com.nubbank.baas.engine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Configuration
public class InternalServiceClient {

    @Bean(name = "internalServiceRestTemplate")
    public RestTemplate internalServiceRestTemplate(
            RestTemplateBuilder builder,
            @Value("${app.internal-service.shared-secret}") String sharedSecret) {
        if (sharedSecret == null || sharedSecret.length() < 32) {
            throw new IllegalStateException("app.internal-service.shared-secret must be set and ≥32 chars");
        }
        return builder.additionalInterceptors(new SigningInterceptor(sharedSecret)).build();
    }

    /**
     * Signs every outbound call with: HmacSHA256(secret, METHOD|PATH|TS|sha256Hex(body)).
     * Empty body uses the well-known empty-string SHA-256 (e3b0c44...). The signature MUST
     * match what InternalServiceAuthFilter recomputes on the receiving side.
     */
    static class SigningInterceptor implements ClientHttpRequestInterceptor {
        private final String secret;
        SigningInterceptor(String secret) { this.secret = secret; }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            long ts = Instant.now().getEpochSecond();
            String sig;
            try {
                String bodyHash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
                String signedString = request.getMethod().name() + "|"
                    + request.getURI().getPath() + "|"
                    + ts + "|"
                    + bodyHash;
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                sig = HexFormat.of().formatHex(mac.doFinal(signedString.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                throw new IOException("HMAC computation failed", ex);
            }
            request.getHeaders().set("Authorization", "Internal " + sig);
            request.getHeaders().set("X-Internal-Timestamp", String.valueOf(ts));
            return execution.execute(request, body);
        }
    }
}
```

- [ ] **Step 3: Update `application.yml`**

```yaml
app:
  internal-service:
    shared-secret: ${INTERNAL_SERVICE_SECRET}   # MUST match baas-ncube's value
  ncube:
    base-url: ${NCUBE_BASE_URL:http://baas-ncube:8082}
```

- [ ] **Step 4: No existing callers — bean is forward-prep for Phase 2**

A grep before plan-writing confirmed no class in `baas-engine` currently calls `baas-ncube` over HTTP (`grep -rn 'ncube\|baas-ncube' baas-engine/src/main/java --include='*.java'` returns empty for HTTP-call patterns; the only RestTemplate sites — `RestTemplateConfig.java` and `BatchApiController.java` — are unrelated internal patterns).

Skip the "wire existing callers" sub-step. The `InternalServiceClient` bean is committed in this plan as forward-prep for **Phase 2** when `baas-engine` will need to call `baas-ncube` for live BVN/NIN/NIP. At that point, ncube callers in `baas-engine/src/main/java/com/nubbank/baas/engine/openbanking/` (or wherever Phase 2 puts them) inject `@Qualifier("internalServiceRestTemplate") RestTemplate` to get the auto-signing template.

- [ ] **Step 5: Run engine tests**

```
cd baas-engine && ./mvnw -q test
```

- [ ] **Step 6: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/config/InternalServiceClient.java \
         baas-engine/src/test/java/com/nubbank/baas/engine/config/InternalServiceClientTest.java \
         baas-engine/src/main/resources/application.yml
git commit -m "feat(baas-engine): InternalServiceClient — HMAC-SHA256 outbound signing for ncube calls (1B I7)"
```

---

## Task 6 — Add `StubModeGuard` to `baas-ncube`

Refuses to start when `baas.nps.live=false` AND `SPRING_PROFILES_ACTIVE` contains `prod`.

**Files:**
- Create: `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/StubModeGuard.java`
- Create: `baas-ncube/src/test/java/com/nubbank/baas/ncube/config/StubModeGuardTest.java`

- [ ] **Step 1: Write the test**

```java
package com.nubbank.baas.ncube.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

class StubModeGuardTest {

    @Test
    void stub_mode_with_prod_profile_throws() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        StubModeGuard guard = new StubModeGuard(env, false);
        assertThatThrownBy(guard::onStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("stub mode")
            .hasMessageContaining("prod");
    }

    @Test
    void live_mode_with_prod_profile_does_not_throw() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        assertThatNoException().isThrownBy(() -> new StubModeGuard(env, true).onStartup());
    }

    @Test
    void stub_mode_with_dev_profile_logs_warn_but_does_not_throw() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});
        assertThatNoException().isThrownBy(() -> new StubModeGuard(env, false).onStartup());
    }
}
```

- [ ] **Step 2: Implement**

```java
package com.nubbank.baas.ncube.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.Arrays;

@Component
@Slf4j
public class StubModeGuard {

    private final Environment env;
    private final boolean live;

    public StubModeGuard(Environment env, @Value("${baas.nps.live:false}") boolean live) {
        this.env = env;
        this.live = live;
    }

    @PostConstruct
    public void onStartup() {
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        if (!live && prod) {
            throw new IllegalStateException(
                "FATAL: baas-ncube is in stub mode (baas.nps.live=false) but SPRING_PROFILES_ACTIVE includes 'prod'. "
                + "Stub mode in production is forbidden — set NPS_LIVE=true or remove the prod profile.");
        }
        if (!live) {
            log.warn("");
            log.warn("╔═════════════════════════════════════════════════════════════════════╗");
            log.warn("║  STUB MODE: NIBSS calls are mocked. baas-ncube returns stub data.   ║");
            log.warn("║  DO NOT deploy with this configuration to production.               ║");
            log.warn("║  All stubbed responses include header X-NubBank-Stubbed: true.      ║");
            log.warn("╚═════════════════════════════════════════════════════════════════════╝");
            log.warn("");
        }
    }
}
```

- [ ] **Step 3: Run tests**

```
cd baas-ncube && ./mvnw -q -Dtest=StubModeGuardTest test
```

- [ ] **Step 4: Commit**

```bash
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/config/StubModeGuard.java \
         baas-ncube/src/test/java/com/nubbank/baas/ncube/config/StubModeGuardTest.java
git commit -m "feat(baas-ncube): StubModeGuard refuses prod profile in stub mode + startup banner (1B C1)"
```

---

## Task 7 — Add `X-NubBank-Stubbed: true` response header in stub mode

Makes every stubbed response unmistakable to consumers and logs.

**Files:**
- Create: `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/StubResponseHeaderInterceptor.java`
- Create: `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/WebMvcConfig.java` (or modify if exists)

- [ ] **Step 1: Implement the interceptor**

```java
package com.nubbank.baas.ncube.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class StubResponseHeaderInterceptor implements HandlerInterceptor {

    private final boolean live;
    public StubResponseHeaderInterceptor(@Value("${baas.nps.live:false}") boolean live) {
        this.live = live;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        if (!live) {
            resp.setHeader("X-NubBank-Stubbed", "true");
        }
        return true;
    }
}
```

- [ ] **Step 2: Register it**

```java
package com.nubbank.baas.ncube.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final StubResponseHeaderInterceptor stubHeaderInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(stubHeaderInterceptor)
                .addPathPatterns("/baas/v1/**");
    }
}
```

- [ ] **Step 3: Smoke test — assertion is in the comprehensive controller test (Task 9.5)**

The assertion `assertThat(resp.getHeaders().getFirst("X-NubBank-Stubbed")).isEqualTo("true")` lives in `NcubeIdentityControllerTest` which is fully written in Task 9.5. No separate test commit here — this task only commits the interceptor + WebMvcConfig.

- [ ] **Step 4: Commit**

```bash
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/config/StubResponseHeaderInterceptor.java \
         baas-ncube/src/main/java/com/nubbank/baas/ncube/config/WebMvcConfig.java
git commit -m "feat(baas-ncube): X-NubBank-Stubbed: true header on every stubbed response (1B C1)"
```

---

## Task 8 — Replace stubbed BVN/NIN data with `00000000000`

A real BVN/NIN registry would never return all-zeros. Using all-zeros means downstream `baas-engine` can deterministically detect stub responses regardless of `verificationSource` string.

**Files:**
- Modify: `baas-ncube/src/main/java/com/nubbank/baas/ncube/identity/NcubeIdentityController.java`

- [ ] **Step 1: Update the controller** — replace lines 27–31 and 37–40:

```java
@PostMapping("/verify-bvn")
public ResponseEntity<Map<String, Object>> verifyBvn(
        @Valid @RequestBody BvnVerificationRequest req,
        @RequestHeader(value = "Authorization", required = false) String auth) {
    log.debug("BVN verification: {} — Phase 1B stub", req.bvn());
    return ResponseEntity.ok(Map.of("data", new VerificationResponse(
        "00000000000",         // stubbed BVN — never echoes caller input
        true,                  // verified=true so partner flow proceeds
        "STUB_FIRST",
        "STUB_LAST",
        null, null,
        "NIBSS_NCUBE_STUB")));
}

@PostMapping("/verify-nin")
public ResponseEntity<Map<String, Object>> verifyNin(
        @Valid @RequestBody NinVerificationRequest req,
        @RequestHeader(value = "Authorization", required = false) String auth) {
    log.debug("NIN verification: {} — Phase 1B stub", req.nin());
    return ResponseEntity.ok(Map.of("data", new VerificationResponse(
        "00000000000",
        true,
        "STUB_FIRST",
        "STUB_LAST",
        null, null,
        "NIBSS_NCUBE_STUB")));
}
```

- [ ] **Step 2: Smoke test — assertion lives in Task 9.5**

The assertion `assertThat(body.bvn()).isEqualTo("00000000000")` is in `NcubeIdentityControllerTest` written in Task 9.5. This task only commits the controller change.

- [ ] **Step 3: Commit**

```bash
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/identity/NcubeIdentityController.java
git commit -m "fix(baas-ncube): stub BVN/NIN return 00000000000 instead of echoing input (1B C1)"
```

---

## Task 9 — Apply CBN vendor media type to all `baas-ncube` controllers

CBN audits the wire format. Adding `consumes`/`produces = application/vnd.cbn.openbanking.v1+json` rejects mismatched content types with 415.

**Files:**
- Modify: `baas-ncube/src/main/java/com/nubbank/baas/ncube/identity/NcubeIdentityController.java`
- Modify: `baas-ncube/src/main/java/com/nubbank/baas/ncube/account/NcubeAccountController.java`
- Modify: `baas-ncube/src/main/java/com/nubbank/baas/ncube/consent/NcubeConsentController.java`
- Modify: `baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/NcubePaymentController.java`
- Create: `baas-ncube/src/main/java/com/nubbank/baas/ncube/common/CbnMediaTypes.java` (constants)

- [ ] **Step 1: Add constants class**

```java
package com.nubbank.baas.ncube.common;

public final class CbnMediaTypes {
    public static final String CBN_OB_V1_JSON = "application/vnd.cbn.openbanking.v1+json";
    private CbnMediaTypes() {}
}
```

- [ ] **Step 2: Update each controller**

For each controller, change `@RequestMapping(...)` (or per-method mapping) to include:

```java
@RequestMapping(value = "/baas/v1/ncube/identity",
                consumes = CbnMediaTypes.CBN_OB_V1_JSON,
                produces = CbnMediaTypes.CBN_OB_V1_JSON)
```

- [ ] **Step 3: Test for 415 lives in Task 9.5** — `wrong_content_type_returns_415` is one of the assertions in the comprehensive `NcubeIdentityControllerTest` written next.

- [ ] **Step 4: Commit**

```bash
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/{identity,account,consent,payment}/*Controller.java \
         baas-ncube/src/main/java/com/nubbank/baas/ncube/common/CbnMediaTypes.java
git commit -m "feat(baas-ncube): require CBN vendor media type on all controllers (1B I3)"
```

---

## Task 9.5 — Comprehensive `NcubeIdentityControllerTest`

Single integration test class that asserts every controller-layer behaviour introduced by Tasks 7, 8, 9: stub-mode response header, all-zeros stub data, CBN media type enforcement (415 on mismatch), and that valid requests still pass through `InternalServiceAuthFilter` + `AuthEnforcementFilter` end-to-end.

**Files:**
- Create: `baas-ncube/src/test/java/com/nubbank/baas/ncube/identity/NcubeIdentityControllerTest.java`

- [ ] **Step 1: Write the test class with full bodies**

```java
package com.nubbank.baas.ncube.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class NcubeIdentityControllerTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;
    @Value("${app.internal-service.shared-secret}") private String secret;

    private static final String CBN_MT = "application/vnd.cbn.openbanking.v1+json";

    @Test
    void valid_request_returns_200_with_stub_data_and_stubbed_header() throws Exception {
        ResponseEntity<Map> resp = rest.exchange(
            "/baas/v1/ncube/identity/verify-bvn", HttpMethod.POST,
            signedEntity("POST", "/baas/v1/ncube/identity/verify-bvn",
                json.writeValueAsString(Map.of("bvn", "12345678901")), CBN_MT),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst("X-NubBank-Stubbed")).isEqualTo("true");
        Map<?,?> data = (Map<?,?>) resp.getBody().get("data");
        assertThat(data.get("bvn")).isEqualTo("00000000000");   // stub returns all zeros, not the input
        assertThat(data.get("verificationSource")).isEqualTo("NIBSS_NCUBE_STUB");
    }

    @Test
    void verify_nin_stub_returns_all_zeros() throws Exception {
        ResponseEntity<Map> resp = rest.exchange(
            "/baas/v1/ncube/identity/verify-nin", HttpMethod.POST,
            signedEntity("POST", "/baas/v1/ncube/identity/verify-nin",
                json.writeValueAsString(Map.of("nin", "98765432109")), CBN_MT),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?,?> data = (Map<?,?>) resp.getBody().get("data");
        assertThat(data.get("nin")).isEqualTo("00000000000");
    }

    @Test
    void plain_application_json_returns_415() throws Exception {
        ResponseEntity<Map> resp = rest.exchange(
            "/baas/v1/ncube/identity/verify-bvn", HttpMethod.POST,
            signedEntity("POST", "/baas/v1/ncube/identity/verify-bvn",
                json.writeValueAsString(Map.of("bvn", "12345678901")),
                MediaType.APPLICATION_JSON_VALUE),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void missing_authorization_returns_401() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(CBN_MT));
        HttpEntity<String> entity = new HttpEntity<>(
            json.writeValueAsString(Map.of("bvn", "12345678901")), h);
        ResponseEntity<Map> resp = rest.exchange(
            "/baas/v1/ncube/identity/verify-bvn", HttpMethod.POST, entity, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpEntity<String> signedEntity(String method, String path, String body, String contentType)
            throws Exception {
        long ts = Instant.now().getEpochSecond();
        // Body hash MUST match the filter's hash of the wrapped request body.
        // Use SHA-256 of the UTF-8 bytes; empty body hashes to e3b0c44... (well-known).
        byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String bodyHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bodyBytes));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String signedString = method + "|" + path + "|" + ts + "|" + bodyHash;
        String sig = HexFormat.of().formatHex(mac.doFinal(signedString.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(contentType));
        h.set("Authorization", "Internal " + sig);
        h.set("X-Internal-Timestamp", String.valueOf(ts));
        return new HttpEntity<>(body, h);
    }
}
```

- [ ] **Step 2: Set the shared secret in test profile**

Add to `baas-ncube/src/test/resources/application-test.yml` (create if missing):

```yaml
app:
  internal-service:
    shared-secret: test-shared-secret-min-32-chars-long-okay
baas:
  nps:
    live: false
```

And add `@ActiveProfiles("test")` to the test class.

- [ ] **Step 3: Run the test**

```
cd baas-ncube && ./mvnw -q -Dtest=NcubeIdentityControllerTest test
```
Expected: all 4 assertions pass.

- [ ] **Step 4: Commit**

```bash
git add baas-ncube/src/test/java/com/nubbank/baas/ncube/identity/NcubeIdentityControllerTest.java \
         baas-ncube/src/test/resources/application-test.yml
git commit -m "test(baas-ncube): comprehensive identity controller integration test (Tasks 7, 8, 9)"
```

---

## Task 10 — Add `PiiMaskingConverter` Logback converter

Pattern-masks BVN/NIN (11 digits adjacent to `bvn=` / `nin=`), NUBAN account numbers (10 digits), and PAN-like sequences (13–19 digits) anywhere in the log line, regardless of log level. Defence-in-depth so an SRE flipping log level to DEBUG cannot accidentally expose PII.

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/common/PiiMaskingConverter.java`
- Create: `baas-engine/src/test/java/com/nubbank/baas/engine/common/PiiMaskingConverterTest.java`
- Create: `baas-ncube/src/main/java/com/nubbank/baas/ncube/common/PiiMaskingConverter.java` (copy — services don't share modules)

- [ ] **Step 1: Write the test**

```java
package com.nubbank.baas.engine.common;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PiiMaskingConverterTest {

    private final PiiMaskingConverter sut = new PiiMaskingConverter();

    @Test
    void masks_bvn_eleven_digits() {
        assertThat(maskLine("BVN verification: 12345678901 succeeded"))
            .isEqualTo("BVN verification: 123****8901 succeeded");
    }

    @Test
    void masks_nin_eleven_digits() {
        assertThat(maskLine("NIN=98765432109 verified"))
            .isEqualTo("NIN=987****2109 verified");
    }

    @Test
    void masks_nuban_ten_digits() {
        assertThat(maskLine("transfer to 0581000042 amount=1000"))
            .isEqualTo("transfer to 058****042 amount=1000");
    }

    @Test
    void masks_pan_thirteen_to_nineteen_digits() {
        assertThat(maskLine("card 4123456789012345 charged"))
            .isEqualTo("card 4123********2345 charged");
    }

    @Test
    void leaves_short_numbers_alone() {
        // 4 to 9 digits not masked — likely amounts/IDs, not PII
        assertThat(maskLine("amount=99999 retries=3"))
            .isEqualTo("amount=99999 retries=3");
    }

    @Test
    void leaves_iso_dates_alone() {
        assertThat(maskLine("Started at 2026-05-04T10:23:45Z"))
            .isEqualTo("Started at 2026-05-04T10:23:45Z");
    }

    private String maskLine(String input) {
        ILoggingEvent ev = mock(ILoggingEvent.class);
        when(ev.getFormattedMessage()).thenReturn(input);
        return sut.convert(ev);
    }
}
```

- [ ] **Step 2: Implement the converter**

```java
package com.nubbank.baas.engine.common;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logback converter that masks PII patterns in log messages.
 * Wired in logback-spring.xml as %piimsg replacement for %msg.
 *
 * Masks:
 *   - 13-19 digit sequences (PAN/card numbers): keep first 4, last 4
 *   - 10-11 digit sequences (NUBAN accounts, BVN, NIN): keep first 3, last 3 (NUBAN) / 4 (BVN/NIN)
 *
 * Order matters: PAN (longer) is replaced first so the shorter regex doesn't grab pieces of it.
 */
public class PiiMaskingConverter extends ClassicConverter {

    // Word-boundary anchored — avoids masking middles of longer numeric tokens
    private static final Pattern PAN  = Pattern.compile("\\b(\\d{4})\\d{5,11}(\\d{4})\\b");
    private static final Pattern BVN_NIN = Pattern.compile("\\b(\\d{3})\\d{4}(\\d{4})\\b");
    private static final Pattern NUBAN = Pattern.compile("\\b(\\d{3})\\d{4}(\\d{3})\\b");

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        if (msg == null || msg.isEmpty()) return msg;
        // Order: longest pattern first
        msg = PAN.matcher(msg).replaceAll(m -> m.group(1) + "*".repeat(m.group().length() - 8) + m.group(2));
        msg = BVN_NIN.matcher(msg).replaceAll("$1****$2");
        msg = NUBAN.matcher(msg).replaceAll("$1****$2");
        return msg;
    }
}
```

- [ ] **Step 3: Run tests**

```
cd baas-engine && ./mvnw -q -Dtest=PiiMaskingConverterTest test
```

- [ ] **Step 4: Copy to baas-ncube**

```bash
mkdir -p baas-ncube/src/main/java/com/nubbank/baas/ncube/common
cp baas-engine/src/main/java/com/nubbank/baas/engine/common/PiiMaskingConverter.java \
   baas-ncube/src/main/java/com/nubbank/baas/ncube/common/PiiMaskingConverter.java
sed -i '' 's/com.nubbank.baas.engine.common/com.nubbank.baas.ncube.common/' \
   baas-ncube/src/main/java/com/nubbank/baas/ncube/common/PiiMaskingConverter.java
```

(Mac note: `sed -i ''` with empty string is the BSD-sed in-place form. On Linux use `sed -i ...`.)

Copy the test too with the same package rename.

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/common/PiiMaskingConverter.java \
         baas-engine/src/test/java/com/nubbank/baas/engine/common/PiiMaskingConverterTest.java \
         baas-ncube/src/main/java/com/nubbank/baas/ncube/common/PiiMaskingConverter.java \
         baas-ncube/src/test/java/com/nubbank/baas/ncube/common/PiiMaskingConverterTest.java
git commit -m "feat(common): PiiMaskingConverter for Logback — masks BVN/NIN/NUBAN/PAN (1B C5)"
```

---

## Task 11 — Wire `PiiMaskingConverter` into both services' Logback configs

**Files:**
- Create: `baas-engine/src/main/resources/logback-spring.xml`
- Create: `baas-ncube/src/main/resources/logback-spring.xml`

- [ ] **Step 1: Create `baas-engine/src/main/resources/logback-spring.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- Use Spring Boot defaults so console/file appenders work as before -->
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>

    <!-- Register the custom converter — replaces %msg with PII-masked output -->
    <conversionRule conversionWord="piimsg"
                    converterClass="com.nubbank.baas.engine.common.PiiMaskingConverter"/>

    <!-- Override the console appender's pattern to use %piimsg instead of %msg -->
    <appender name="CONSOLE_MASKED" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${CONSOLE_LOG_PATTERN:-%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %clr(%5p) %clr(${PID:- }){magenta} %clr(---){faint} %clr([%15.15t]){faint} %clr(%-40.40logger{39}){cyan} %clr(:){faint} %piimsg%n}</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE_MASKED"/>
    </root>

    <logger name="com.nubbank.baas" level="INFO"/>
    <logger name="org.hibernate.SQL" level="OFF"/>
</configuration>
```

- [ ] **Step 2: Create `baas-ncube/src/main/resources/logback-spring.xml`**

Same content as above, but replace `com.nubbank.baas.engine.common.PiiMaskingConverter` with `com.nubbank.baas.ncube.common.PiiMaskingConverter`.

- [ ] **Step 3: Smoke test that masking applies in real logs**

Run the engine, hit an endpoint that logs an account number, and confirm console shows `058****042` not the full number.

```
cd baas-engine && ./mvnw -q spring-boot:run &
# In another shell, post a transfer with destinationAccount=0581000042
# Check engine console output — log line should show 058****042
```

- [ ] **Step 4: Commit**

```bash
git add baas-engine/src/main/resources/logback-spring.xml \
         baas-ncube/src/main/resources/logback-spring.xml
git commit -m "feat(both): wire PiiMaskingConverter into logback for both services (1B C5)"
```

---

## Task 12 — Final integration test + branch verification

- [ ] **Step 1: Run full test suites**

```bash
cd baas-engine && ./mvnw -q test
cd ../baas-ncube && ./mvnw -q test
```

Both must report `BUILD SUCCESS` with zero failures.

- [ ] **Step 2: Smoke test the full chain**

With both services running, send an HTTP request through engine that triggers an internal call to ncube. Verify:
- `baas-ncube` rejects the call with 401 if `Authorization: Internal ...` is missing.
- `baas-engine`'s `internalServiceRestTemplate` correctly signs the call.
- The call succeeds end-to-end with stub data + `X-NubBank-Stubbed: true` header.
- Console log line for BVN verification shows `123****8901` not the full BVN.

- [ ] **Step 3: Update `baas-log.md`**

Add Session 5 entry at the top of Change History with:
- Session number, date (2026-05-04), summary citing PR for `feature/phase1f-0-cross-cutting`
- New/Updated Files table (12 new + 5 modified)
- Key Decisions block (HMAC choice, why all-zeros for stub BVN, etc.)
- Build Verification (test counts)
- Confirmed Platform Versions block (use SHA from `git log --oneline -1 -- baas-engine/`)

- [ ] **Step 4: Update `CLAUDE.md`**

- Bump SHA in Confirmed Platform Versions header to current commit
- Add new gotchas: HMAC inter-service auth pattern, PII Logback converter pattern

- [ ] **Step 5: Update `/baas` skill Phase Build Order**

Mark Phase 1F-0 as ✅ in `/Users/razormvp/nubbank-baas/.claude/skills/baas/SKILL.md`.

- [ ] **Step 6: Open PR + merge**

```bash
git push -u origin feature/phase1f-0-cross-cutting
gh pr create --title "Phase 1F-0: cross-cutting security baseline (1B C1, C2, C5, I1, I3, I7)" \
             --body "$(cat <<'EOF'
## Summary

Closes the most urgent security exposures across both baas-engine and baas-ncube before further work proceeds. Six 1B findings addressed (4 critical, 2 important).

- baas-ncube: AuthEnforcementFilter mirrors engine pattern; permitAll() removed
- Inter-service auth: HMAC-SHA256 signed Authorization: Internal <hmac> with 60s replay window
- StubModeGuard: refuses to start when stub mode + prod profile collide; startup banner; X-NubBank-Stubbed header on every stubbed response
- Stub BVN/NIN now return 00000000000 instead of echoing caller input
- All ncube controllers now require application/vnd.cbn.openbanking.v1+json
- PiiMaskingConverter masks BVN/NIN/NUBAN/PAN in logs across both services

## Test plan

- [ ] cd baas-engine && ./mvnw test  — expect all green
- [ ] cd baas-ncube && ./mvnw test  — expect all green
- [ ] Smoke test full chain with both services running

## Findings closed
- 1B C1 — stub-mode guards
- 1B C2 — ncube permitAll() removed
- 1B C5 — PII masking in logs
- 1B I1 — /actuator/info no longer exposed
- 1B I3 — CBN vendor media type required
- 1B I7 — inter-service HMAC auth
EOF
)"
```

After review and merge, mark Phase 1F-0 ✅ in `/baas` skill and tag the merge commit `phase1f-0-merged`.

---

## Summary

| Task | Files changed | Findings closed |
|------|---------------|-----------------|
| 1 | +2 (filter + test) | partial 1B C2 |
| 2 | +1 (context class) | infra |
| 3 | +2 (filter + test) + appl.yml | 1B I7 (ncube) |
| 4 | modified SecurityConfig + appl.yml | 1B C2 (wire) + 1B I1 |
| 5 | +2 (client + test) + appl.yml | 1B I7 (engine) |
| 6 | +2 (guard + test) | 1B C1 (boot) |
| 7 | +2 (interceptor + config) | 1B C1 (header) |
| 8 | modified controller | 1B C1 (data) |
| 9 | +1 (constants) + 4 controllers | 1B I3 |
| 9.5 | +2 (test class + test profile yml) | tests for Tasks 7+8+9 |
| 10 | +2 converters + tests | 1B C5 (impl) |
| 11 | +2 logback configs | 1B C5 (wire) |
| 12 | docs + push + PR | (gates) |

**Total: 13 tasks, ~16 new files, ~7 modified files, 6 findings closed (4 critical + 2 important).**

Estimated effort: 1 focused session.
