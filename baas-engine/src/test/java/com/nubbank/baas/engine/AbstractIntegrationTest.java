package com.nubbank.baas.engine;

import com.nubbank.baas.engine.tenant.PartnerContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests.
 *
 * Spins up a postgres:16-alpine container via Testcontainers on a random host port.
 * The container is shared across ALL subclasses via a static initializer block so
 * it starts exactly once for the entire test suite and is stopped by the JVM shutdown
 * hook — NOT by JUnit per-class lifecycle management.
 *
 * Deliberately NOT annotated with @Testcontainers: that annotation causes JUnit to
 * manage container lifecycle per-subclass (start + stop per class), which stops the
 * Postgres container between test classes and causes the next class to see
 * "Connection to localhost:PORT refused" when it tries to reuse the same
 * Spring application context (which still holds the old HikariPool pointing at the
 * now-dead port).
 *
 * Redis is excluded from autoconfiguration via application-test.yml.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("nubbank_baas_test")
            .withUsername("baas_test")
            .withPassword("baas_test");
        postgres.start();
    }

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @AfterEach
    void clearTenantContext() {
        PartnerContext.clear();
    }
}
