package com.nubbank.baas.fep;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

class FepContextTest extends AbstractFepIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthReady() {
        var r = rest.getForEntity("/actuator/health/readiness", String.class);
        Assertions.assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertThat(r.getBody()).contains("UP");
    }
}
