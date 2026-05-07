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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Provides a RestTemplate that signs every outbound call with HMAC-SHA256 using the scheme
 * baas-ncube's InternalServiceAuthFilter validates: HmacSHA256(secret, METHOD|PATH|TS|sha256Hex(body)).
 *
 * Empty body uses the well-known empty-string SHA-256 (e3b0c44...). The signature MUST match
 * what InternalServiceAuthFilter recomputes on the receiving side or the call gets rejected
 * with 401 by AuthEnforcementFilter.
 *
 * No callers in baas-engine yet — this bean is forward-prep for Phase 2 when engine calls
 * ncube for live BVN/NIN/NIP. To use: inject @Qualifier("internalServiceRestTemplate") RestTemplate.
 *
 * <p>Eager instantiation is intentional: the constructor's ≥32-char validation runs at startup,
 * giving operators boot-time feedback if INTERNAL_SERVICE_SECRET is unset/weak. Do not add @Lazy.
 *
 * <p>Secret rotation requires a rolling restart of both baas-engine and baas-ncube. Phase 2 may
 * revisit this if rotation cadence tightens (Supplier&lt;String&gt; indirection).
 */
@Configuration
public class InternalServiceClient {

    @Bean(name = "internalServiceRestTemplate")
    public RestTemplate internalServiceRestTemplate(
            RestTemplateBuilder builder,
            @Value("${app.internal-service.shared-secret}") String sharedSecret) {
        if (sharedSecret == null || sharedSecret.length() < 32) {
            throw new IllegalStateException("app.internal-service.shared-secret must be set and ≥32 chars");
        }
        // Sensible default timeouts: a hung baas-ncube must not block engine threads indefinitely.
        // Override per-call by configuring a longer-running RestTemplate when Phase 2 needs it.
        return builder
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(30))
            .additionalInterceptors(new SigningInterceptor(sharedSecret))
            .build();
    }

    static class SigningInterceptor implements ClientHttpRequestInterceptor {
        private final SecretKeySpec keySpec;

        SigningInterceptor(String secret) {
            // Pre-build SecretKeySpec once; SecretKeySpec is immutable + thread-safe.
            // Mac.getInstance(...) returns a new instance per request; SecretKeySpec is shared.
            this.keySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            // Validate the algorithm is available on this JVM at construction-time (rather than
            // on first request) so missing-algorithm failures are caught at boot.
            try {
                Mac.getInstance("HmacSHA256").init(keySpec);
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("HmacSHA256 unavailable on this JVM", ex);
            }
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            long ts = Instant.now().getEpochSecond();
            String bodyHash = HexFormat.of().formatHex(
                sha256().digest(body == null ? new byte[0] : body));
            String signedString = request.getMethod().name() + "|"
                + request.getURI().getPath() + "|"
                + ts + "|"
                + bodyHash;
            String sig = HexFormat.of().formatHex(hmac(signedString));
            request.getHeaders().set("Authorization", "Internal " + sig);
            request.getHeaders().set("X-Internal-Timestamp", String.valueOf(ts));
            return execution.execute(request, body);
        }

        private byte[] hmac(String data) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(keySpec);
                return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException ex) {
                // Cannot recover at runtime — algorithm is verified at construction.
                throw new IllegalStateException("HMAC computation failed", ex);
            }
        }

        private MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 unavailable on this JVM", ex);
            }
        }
    }
}
