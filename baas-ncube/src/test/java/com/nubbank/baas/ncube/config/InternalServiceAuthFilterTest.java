package com.nubbank.baas.ncube.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

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
