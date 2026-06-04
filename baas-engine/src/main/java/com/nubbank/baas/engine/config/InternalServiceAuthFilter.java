package com.nubbank.baas.engine.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Inbound HMAC validation for service-to-service calls on {@code /internal/v1/**}.
 *
 * Recomputes the signature using the EXACT scheme produced by
 * {@code InternalServiceClient.SigningInterceptor} on the calling side:
 * <pre>
 *   signedString = METHOD | URI.getPath() | epochSeconds | sha256Hex(body)
 *   signature    = HexFormat(HmacSHA256(sharedSecret, signedString))   // lowercase hex
 *   headers      = "Authorization: Internal &lt;hex&gt;"
 *                  "X-Internal-Timestamp: &lt;epochSeconds&gt;"
 * </pre>
 * An empty body hashes the well-known empty SHA-256 ({@code e3b0c442...}),
 * matching the signer's {@code body == null ? new byte[0]} path.
 *
 * Rejects with 401 on a missing/malformed {@code Authorization: Internal} header,
 * a missing/non-numeric timestamp, a timestamp skewed more than
 * {@link #MAX_SKEW_SECONDS} from now, or a signature mismatch (constant-time
 * comparison via {@link MessageDigest#isEqual}).
 *
 * <p>The {@code ≥32-char} secret check runs at construction so a weak/unset
 * {@code app.internal-service.shared-secret} fails fast at boot — matching the
 * signer side's guard.
 */
@Component
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    private static final long MAX_SKEW_SECONDS = 60; // 1 minute — matches engine signer
    private static final String AUTH_PREFIX = "Internal ";

    private final SecretKeySpec keySpec;

    public InternalServiceAuthFilter(
            @Value("${app.internal-service.shared-secret}") String sharedSecret) {
        if (sharedSecret == null || sharedSecret.length() < 32) {
            throw new IllegalStateException("app.internal-service.shared-secret must be set and ≥32 chars");
        }
        this.keySpec = new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        try {
            Mac.getInstance("HmacSHA256").init(keySpec);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable on this JVM", ex);
        }
    }

    /**
     * Only guard {@code /internal/v1/**}. A {@code @Component}-annotated
     * {@code OncePerRequestFilter} is auto-registered by Spring Boot for EVERY
     * request (in addition to the security chain), so without this guard the
     * filter would 401 actuator, swagger, and partner paths too.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authz = request.getHeader("Authorization");
        String timestamp = request.getHeader("X-Internal-Timestamp");

        if (authz == null || !authz.startsWith(AUTH_PREFIX) || timestamp == null) {
            reject(response);
            return;
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            reject(response);
            return;
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > MAX_SKEW_SECONDS) {
            reject(response);
            return;
        }

        // Cache the body so the canonical hash matches the signer AND downstream can re-read it.
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request, body);

        // Match SigningInterceptor: METHOD | URI.getPath() | ts | sha256Hex(body).
        // getRequestURI() returns the un-decoded path component, equivalent to URI.getPath().
        String signedString = request.getMethod() + "|"
            + request.getRequestURI() + "|"
            + timestamp.trim() + "|"
            + HexFormat.of().formatHex(sha256(body));
        String expected = HexFormat.of().formatHex(hmac(signedString));
        String provided = authz.substring(AUTH_PREFIX.length()).trim();

        if (!constantTimeEquals(expected, provided)) {
            reject(response);
            return;
        }

        chain.doFilter(cached, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            "{\"data\":null,\"errors\":[{\"code\":\"INVALID_INTERNAL_SIGNATURE\","
            + "\"message\":\"Invalid or missing internal service signature\"}]}");
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", ex);
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keySpec);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC computation failed", ex);
        }
    }

    /**
     * Wraps a request with an already-read body so it can be consumed again downstream.
     */
    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }
                @Override public int read() { return in.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }
}
