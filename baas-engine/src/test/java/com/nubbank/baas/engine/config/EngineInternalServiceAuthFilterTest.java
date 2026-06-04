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

        filter.doFilter(req, resp, chain); // shouldNotFilter -> passes through untouched

        verify(chain).doFilter(req, resp);
        assertThat(resp.getStatus()).isEqualTo(200);
    }
}
