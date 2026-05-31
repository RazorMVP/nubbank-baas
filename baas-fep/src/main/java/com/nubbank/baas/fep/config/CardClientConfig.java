package com.nubbank.baas.fep.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

@Configuration
@EnableConfigurationProperties(FepProperties.class)
public class CardClientConfig {

    @Bean(name = "cardRestTemplate")
    public RestTemplate cardRestTemplate(RestTemplateBuilder builder, FepProperties fepProperties) {
        String secret = fepProperties.hmacSecret();
        if (secret.length() < 32) {
            throw new IllegalStateException(
                "fep.hmac-secret must be at least 32 characters");
        }
        return builder
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(5))
            .additionalInterceptors(new SigningInterceptor(secret))
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
