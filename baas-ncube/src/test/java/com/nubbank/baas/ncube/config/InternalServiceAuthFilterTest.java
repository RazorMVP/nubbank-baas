package com.nubbank.baas.ncube.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
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
        req.setContent(tamperedBody.getBytes(StandardCharsets.UTF_8));
        req.addHeader("Authorization", "Internal " + sig);
        req.addHeader("X-Internal-Timestamp", String.valueOf(ts));
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(NcubeRequestContext.get()).isNull();
    }

    @Test
    void invalid_hmac_does_not_populate_context() throws Exception {
        long ts = Instant.now().getEpochSecond();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/baas/v1/ncube/identity/verify-bvn");
        req.setContent("{\"bvn\":\"12345678901\"}".getBytes(StandardCharsets.UTF_8));
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
    void malformed_timestamp_does_not_throw_does_not_populate_context() throws Exception {
        // Non-numeric timestamp must be treated as auth-failure (context null), not propagate as 500.
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/baas/v1/ncube/identity/verify-bvn");
        req.setContent("{\"bvn\":\"12345678901\"}".getBytes(StandardCharsets.UTF_8));
        req.addHeader("Authorization", "Internal deadbeef");
        req.addHeader("X-Internal-Timestamp", "not-a-number");
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(NcubeRequestContext.get()).isNull();
    }

    @Test
    void short_shared_secret_fails_fast_at_construction() {
        // Constructor must reject any secret <32 chars; prevents weak prod config.
        assertThatThrownBy(() -> new InternalServiceAuthFilter("short"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("≥32 chars");
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

    @Test
    void oversized_body_returns_400_via_filter() throws Exception {
        // Construct a CachedBodyHttpServletRequest with a body larger than the cap.
        // Easiest: directly invoke the wrapper's constructor with a mock servlet request whose
        // getContentLengthLong returns > MAX_BODY_BYTES.
        org.springframework.mock.web.MockHttpServletRequest big = new org.springframework.mock.web.MockHttpServletRequest("POST", "/baas/v1/ncube/identity/verify-bvn");
        // Setting an oversized content via setContent triggers the readBoundedBytes path
        byte[] huge = new byte[CachedBodyHttpServletRequest.MAX_BODY_BYTES + 1];
        big.setContent(huge);

        org.springframework.mock.web.MockHttpServletResponse resp = new org.springframework.mock.web.MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);
        filter.doFilter(big, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(400);
        assertThat(resp.getContentAsString()).contains("BAD_REQUEST");
        assertThat(NcubeRequestContext.get()).isNull();
        // chain.doFilter must NOT have been invoked when we wrote 400 directly
        verify(chain, never()).doFilter(any(), any());
    }

    private String hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256Hex(String data) throws Exception {
        return HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
    }
}
