package com.nubbank.baas.card;

import org.junit.jupiter.api.Test;

class CardApplicationContextTest extends AbstractCardIntegrationTest {

    @Test
    void contextLoads_and_healthReady() {
        var res = restTemplate.getForEntity("/actuator/health/readiness", String.class);
        System.out.println("DIAG readiness status=" + res.getStatusCode() + " body=" + res.getBody());
        var liveness = restTemplate.getForEntity("/actuator/health/liveness", String.class);
        System.out.println("DIAG liveness status=" + liveness.getStatusCode() + " body=" + liveness.getBody());
        var health = restTemplate.getForEntity("/actuator/health", String.class);
        System.out.println("DIAG health status=" + health.getStatusCode() + " body=" + health.getBody());
        org.assertj.core.api.Assertions.assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
