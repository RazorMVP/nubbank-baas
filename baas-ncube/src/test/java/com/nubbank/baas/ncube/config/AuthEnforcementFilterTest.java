package com.nubbank.baas.ncube.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AuthEnforcementFilterTest {

    @Autowired private TestRestTemplate rest;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void missingAuth_to_protected_path_returns_401() {
        ResponseEntity<Map> resp = rest.exchange(
            "/baas/v1/ncube/identity/verify-bvn",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("bvn", "12345678901"), new HttpHeaders()),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).isNotNull();
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).containsEntry("code", "MISSING_AUTH");
    }

    @Test
    void public_path_actuator_health_returns_200_without_auth() {
        ResponseEntity<Map> resp = rest.exchange(
            "/actuator/health", HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(resp.getStatusCode().value()).isNotEqualTo(401);
    }
}
