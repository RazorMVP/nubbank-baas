package com.nubbank.baas.card.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubbank.baas.card.AbstractCardIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5 Task 11 — the engine→card provisioning trigger endpoint. A signed call runs the
 * card-tenant migrations into the partner schema (idempotent); an unsigned call is 401.
 */
class InternalProvisioningControllerTest extends AbstractCardIntegrationTest {

    private static final String SECRET = "test-shared-secret-min-32-chars-long-okay";
    private static final String PATH = "/internal/v1/provision";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void signedProvision_createsCardTablesInSchema_idempotently() throws Exception {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> body = Map.of("partnerId", UUID.randomUUID().toString(), "schemaName", schema);

        assertThat(postSigned(body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass(?)", String.class, schema + ".cards"))
            .isNotNull();   // card-tenant migrations ran

        // Idempotent — a second provision of the same schema succeeds without error.
        assertThat(postSigned(body).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unsignedProvision_is401() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = restTemplate.exchange(PATH, HttpMethod.POST,
            new HttpEntity<>("{}", h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map> postSigned(Object body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        long ts = Instant.now().getEpochSecond();
        String bodyHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        String signed = "POST|" + PATH + "|" + ts + "|" + bodyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Internal " + sig);
        h.set("X-Internal-Timestamp", String.valueOf(ts));
        return restTemplate.exchange(PATH, HttpMethod.POST, new HttpEntity<>(json, h), Map.class);
    }
}
