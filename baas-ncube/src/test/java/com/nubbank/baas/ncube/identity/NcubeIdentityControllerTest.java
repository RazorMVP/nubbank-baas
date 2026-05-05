package com.nubbank.baas.ncube.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the identity controller. Fires real HTTP requests
 * through the full filter chain: InternalServiceAuthFilter → AuthEnforcementFilter →
 * StubResponseHeaderInterceptor → controller → media-type negotiation.
 *
 * Asserts every behavior introduced by Tasks 7, 8, 9:
 *   - Task 7: X-NubBank-Stubbed: true response header
 *   - Task 8: stub data = "00000000000" (never echoes caller input)
 *   - Task 9: 415 on application/json (CBN vendor type required on POST)
 *
 * Plus end-to-end auth: 401 when Authorization header missing.
 *
 * Replaces the prior @WebMvcTest slice test which only covered controller wiring
 * in isolation.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NcubeIdentityControllerTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;

    @Value("${app.internal-service.shared-secret}")
    private String secret;

    private static final String CBN_MT = "application/vnd.cbn.openbanking.v1+json";

    @Test
    void valid_request_returns_200_with_stub_data_and_stubbed_header() throws Exception {
        ResponseEntity<Map> resp = rest.exchange(
            "/baas/v1/ncube/identity/verify-bvn", HttpMethod.POST,
            signedEntity("POST", "/baas/v1/ncube/identity/verify-bvn",
                json.writeValueAsString(Map.of("bvn", "12345678901")), CBN_MT),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst("X-NubBank-Stubbed")).isEqualTo("true");
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("identifier")).isEqualTo("00000000000");   // stub returns all zeros, not input
        assertThat(data.get("verificationSource")).isEqualTo("NIBSS_NCUBE_STUB");
    }

    @Test
    void verify_nin_stub_returns_all_zeros() throws Exception {
        ResponseEntity<Map> resp = rest.exchange(
            "/baas/v1/ncube/identity/verify-nin", HttpMethod.POST,
            signedEntity("POST", "/baas/v1/ncube/identity/verify-nin",
                json.writeValueAsString(Map.of("nin", "98765432109")), CBN_MT),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("identifier")).isEqualTo("00000000000");
    }

    @Test
    void plain_application_json_returns_415() throws Exception {
        ResponseEntity<Map> resp = rest.exchange(
            "/baas/v1/ncube/identity/verify-bvn", HttpMethod.POST,
            signedEntity("POST", "/baas/v1/ncube/identity/verify-bvn",
                json.writeValueAsString(Map.of("bvn", "12345678901")),
                MediaType.APPLICATION_JSON_VALUE),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void missing_authorization_returns_401() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(CBN_MT));
        HttpEntity<String> entity = new HttpEntity<>(
            json.writeValueAsString(Map.of("bvn", "12345678901")), h);
        ResponseEntity<Map> resp = rest.exchange(
            "/baas/v1/ncube/identity/verify-bvn", HttpMethod.POST, entity, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Builds an HTTP request that mirrors what {@link com.nubbank.baas.ncube.config.InternalServiceAuthFilter}
     * expects to validate: HMAC-SHA256(secret, METHOD|PATH|TIMESTAMP|sha256Hex(body)).
     * The body hash MUST match the filter's hash of the wrapped request body.
     * Empty body hashes to e3b0c44... (well-known empty-string SHA-256).
     */
    private HttpEntity<String> signedEntity(String method, String path, String body, String contentType)
            throws Exception {
        long ts = Instant.now().getEpochSecond();
        byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        String bodyHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bodyBytes));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signedString = method + "|" + path + "|" + ts + "|" + bodyHash;
        String sig = HexFormat.of().formatHex(
            mac.doFinal(signedString.getBytes(StandardCharsets.UTF_8)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(contentType));
        h.set("Authorization", "Internal " + sig);
        h.set("X-Internal-Timestamp", String.valueOf(ts));
        return new HttpEntity<>(body, h);
    }
}
