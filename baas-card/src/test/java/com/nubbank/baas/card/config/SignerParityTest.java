package com.nubbank.baas.card.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5 Task 16 — card→engine signer parity. The card's outbound {@code SigningInterceptor}
 * must produce {@code HmacSHA256(secret, METHOD|rawPath|ts|sha256Hex(body))} — the EXACT scheme
 * the engine's {@code InternalServiceAuthFilter} validates (see
 * {@code EngineInternalServiceAuthFilterTest}). Signs over the RAW (un-decoded) path so it
 * matches the engine validator's {@code getRequestURI()}, and over the real body bytes.
 */
class SignerParityTest {

    private static final String SECRET = "test-shared-secret-min-32-chars-long-okay";

    @Test
    void cardSigner_matchesEngineValidatorScheme_rawPathAndBody() throws Exception {
        var interceptor = new InternalServiceClient.SigningInterceptor(SECRET);
        URI uri = URI.create("http://baas-engine:8080/internal/v1/card-debit");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, uri);
        byte[] body = "{\"authKey\":\"000001|TERM0001|0101120000\"}".getBytes(StandardCharsets.UTF_8);

        ClientHttpRequestExecution noop = (req, b) -> (ClientHttpResponse) null;
        interceptor.intercept(request, body, noop);

        String ts = request.getHeaders().getFirst("X-Internal-Timestamp");
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String signed = "POST|" + uri.getRawPath() + "|" + ts + "|" + bodyHash;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "Internal " + HexFormat.of().formatHex(
            mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));

        assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo(expected);
    }
}
