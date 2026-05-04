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
        return builder.additionalInterceptors(new SigningInterceptor(sharedSecret)).build();
    }

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
