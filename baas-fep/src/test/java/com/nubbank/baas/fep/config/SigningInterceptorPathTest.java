package com.nubbank.baas.fep.config;

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
 * F8: the signer must sign the RAW (un-decoded) path so it matches the card validator's
 * {@code getRequestURI()}. This signs a request whose path has an encoded segment and
 * asserts the signature is computed over the raw path, not the decoded one.
 */
class SigningInterceptorPathTest {

    private static final String SECRET = "test-shared-secret-min-32-chars-long-okay";

    @Test
    void signsRawPath_notDecodedPath() throws Exception {
        var interceptor = new CardClientConfig.SigningInterceptor(SECRET);
        URI uri = URI.create("http://card:8081/internal/v1/bins/50%20600");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, uri);
        byte[] body = new byte[0];

        ClientHttpRequestExecution noop = (req, b) -> (ClientHttpResponse) null;
        interceptor.intercept(request, body, noop);

        String ts = request.getHeaders().getFirst("X-Internal-Timestamp");
        String rawPath = uri.getRawPath();   // "/internal/v1/bins/50%20600"
        String emptyHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        String signed = "GET|" + rawPath + "|" + ts + "|" + emptyHash;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "Internal " + HexFormat.of().formatHex(
            mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));

        assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo(expected);
    }
}
