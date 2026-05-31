package com.nubbank.baas.fep;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FepContextTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthReady() {
        var r = rest.getForEntity("/actuator/health/readiness", String.class);
        Assertions.assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertThat(r.getBody()).contains("UP");
    }
}
