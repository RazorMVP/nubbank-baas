package com.nubbank.baas.card;

import com.nubbank.baas.card.tenant.PartnerContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for baas-card integration tests.
 *
 * Spins up a postgres:16-alpine container via Testcontainers on a random host port.
 * The container is shared across ALL subclasses via a static initializer block so
 * it starts exactly once for the entire test suite and is stopped by the JVM
 * shutdown hook — NOT by JUnit per-class lifecycle management.
 *
 * Deliberately NOT annotated with @Testcontainers (mirrors baas-engine): that
 * annotation manages container lifecycle per-subclass (start + stop per class),
 * which kills the Postgres container between test classes and breaks the cached
 * Spring context (HikariPool still points at the now-dead port).
 *
 * baas-card owns ONLY the card-public migrations. The engine-owned public tables
 * (partner_organizations, partner_api_keys) are stood up in tests via the
 * test-public stand-in migration — wired through the test profile's flyway
 * locations (see application-test.yml).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractCardIntegrationTest {

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("nubbank_card_test")
            .withUsername("card_test")
            .withPassword("card_test");
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
