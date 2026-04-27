package com.nubbank.baas.engine;

import com.nubbank.baas.engine.tenant.PartnerContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests.
 *
 * Requires a PostgreSQL 16 database running at localhost:5435
 * (configured in application-test.yml). In CI or local dev, start it with:
 *   docker run -d --rm --name baas-test-postgres \
 *     -e POSTGRES_DB=nubbank_baas_test -e POSTGRES_USER=baas_test -e POSTGRES_PASSWORD=baas_test \
 *     -p 5435:5432 postgres:16-alpine
 *
 * Redis is excluded from autoconfiguration via application-test.yml.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestRestTemplate restTemplate;

    @AfterEach
    void clearTenantContext() {
        PartnerContext.clear();
    }
}
