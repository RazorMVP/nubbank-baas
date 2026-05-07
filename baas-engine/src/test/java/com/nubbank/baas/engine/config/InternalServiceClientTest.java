package com.nubbank.baas.engine.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class InternalServiceClientTest {

    private static final String SECRET = "test-shared-secret-min-32-chars-long-okay";

    @Test
    void interceptor_adds_authorization_and_timestamp_headers() throws Exception {
        InternalServiceClient.SigningInterceptor sut = new InternalServiceClient.SigningInterceptor(SECRET);
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.POST,
            URI.create("http://baas-ncube:8082/baas/v1/ncube/identity/verify-bvn"));
        ClientHttpRequestExecution exec = (request, body) ->
            new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        sut.intercept(req, new byte[0], exec);

        assertThat(req.getHeaders().getFirst("Authorization")).startsWith("Internal ");
        assertThat(req.getHeaders().getFirst("X-Internal-Timestamp")).isNotBlank();
    }

    @Test
    void signature_includes_body_hash() throws Exception {
        InternalServiceClient.SigningInterceptor sut = new InternalServiceClient.SigningInterceptor(SECRET);
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.POST,
            URI.create("http://baas-ncube:8082/baas/v1/ncube/identity/verify-bvn"));
        byte[] body = "{\"bvn\":\"12345678901\"}".getBytes(StandardCharsets.UTF_8);
        ClientHttpRequestExecution exec = (request, b) ->
            new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        sut.intercept(req, body, exec);

        String sig = req.getHeaders().getFirst("Authorization").substring("Internal ".length());
        String ts = req.getHeaders().getFirst("X-Internal-Timestamp");
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String signedString = "POST|/baas/v1/ncube/identity/verify-bvn|" + ts + "|" + bodyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(signedString.getBytes(StandardCharsets.UTF_8)));
        assertThat(sig).isEqualTo(expected);
    }

    @Test
    void empty_body_uses_empty_string_sha256() throws Exception {
        InternalServiceClient.SigningInterceptor sut = new InternalServiceClient.SigningInterceptor(SECRET);
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET,
            URI.create("http://baas-ncube:8082/baas/v1/ncube/health"));
        ClientHttpRequestExecution exec = (request, body) ->
            new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        sut.intercept(req, new byte[0], exec);

        String sig = req.getHeaders().getFirst("Authorization").substring("Internal ".length());
        String ts = req.getHeaders().getFirst("X-Internal-Timestamp");
        String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String signedString = "GET|/baas/v1/ncube/health|" + ts + "|" + emptyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(signedString.getBytes(StandardCharsets.UTF_8)));
        assertThat(sig).isEqualTo(expected);
    }
}
