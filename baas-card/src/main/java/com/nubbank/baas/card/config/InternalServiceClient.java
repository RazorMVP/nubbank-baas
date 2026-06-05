package com.nubbank.baas.card.config;

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
 * baas-engine's {@code InternalServiceAuthFilter} validates:
 * {@code HmacSHA256(secret, METHOD|PATH|TS|sha256Hex(body))}.
 *
 * Empty body uses the well-known empty-string SHA-256 (e3b0c44...). The signature MUST match
 * what the engine's filter recomputes on the receiving side or the call gets rejected with 401.
 *
 * Used by {@code EngineClient} for card-debit / card-credit / account-lookup. Inject
 * {@code @Qualifier("internalServiceRestTemplate") RestTemplate}.
 *
 * <p>Eager instantiation is intentional: the constructor's ≥32-char validation runs at startup,
 * giving operators boot-time feedback if INTERNAL_SERVICE_SECRET is unset/weak. Do not add @Lazy.
 *
 * <p>Path signing uses {@code getURI().getRawPath()} so the signed path matches exactly what the
 * engine validator reads via {@code getRequestURI()} (both un-decoded).
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
        // Money path must fail fast: a hung engine must not block card threads indefinitely.
        return builder
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(10))
            .additionalInterceptors(new SigningInterceptor(sharedSecret))
            .build();
    }

    static class SigningInterceptor implements ClientHttpRequestInterceptor {
        private final SecretKeySpec keySpec;

        SigningInterceptor(String secret) {
            this.keySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
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
                + request.getURI().getRawPath() + "|"
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
