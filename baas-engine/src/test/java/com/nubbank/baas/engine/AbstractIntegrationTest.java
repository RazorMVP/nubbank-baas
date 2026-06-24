package com.nubbank.baas.engine;

import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.PartnerOrganization;
import com.nubbank.baas.engine.role.RoleRepository;
import com.nubbank.baas.engine.role.UserRole;
import com.nubbank.baas.engine.role.UserRoleId;
import com.nubbank.baas.engine.role.UserRoleRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.UUID;

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

    @Autowired protected RoleRepository roleRepo;
    @Autowired protected UserRoleRepository userRoleRepo;
    @Autowired protected PartnerJwtService partnerJwtService;

    @AfterEach
    void clearTenantContext() {
        PartnerContext.clear();
    }

    /**
     * Grant a (JWT-subject) user PARTNER_ADMIN in the tenant schema — the test equivalent of the
     * production backfill, so a partner JWT for that user resolves to full authority.
     */
    protected void grantAdmin(String schema, UUID userId) {
        PartnerContext.set(
            new PartnerContext(null, schema, "SANDBOX", "SANDBOX", "JWT", null));
        try {
            com.nubbank.baas.engine.role.Role admin =
                roleRepo.findByName("PARTNER_ADMIN").orElseThrow();
            if (userRoleRepo.findById(new UserRoleId(userId, admin.getId())).isEmpty())
                userRoleRepo.save(UserRole.builder().userId(userId).role(admin).build());
        } finally {
            PartnerContext.clear();
        }
    }

    /**
     * Provision a granted PARTNER_ADMIN user and return a JWT for it (matches the org's tier/env).
     */
    protected String adminJwt(PartnerOrganization org, String schema) {
        UUID userId = UUID.randomUUID();
        grantAdmin(schema, userId);
        return partnerJwtService.issue(userId.toString(), "admin@test.local", "PARTNER_ADMIN",
            org.getId().toString(), org.getName(), schema, org.getTier().name(), org.getEnvironment().name());
    }
}
