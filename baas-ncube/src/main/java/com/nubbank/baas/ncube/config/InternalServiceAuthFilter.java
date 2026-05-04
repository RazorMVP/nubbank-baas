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
 * Body bytes are read via {@link ContentCachingRequestWrapper} so the controller can still
 * read them downstream (raw HttpServletRequest's input stream is single-use).
 *
 * On valid signature, populates {@link NcubeRequestContext} with caller identity.
 * On invalid signature (or tampered body), leaves context null —
 * {@link AuthEnforcementFilter} then rejects with 401.
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
