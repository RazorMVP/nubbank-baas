package com.nubbank.baas.fep;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for baas-fep integration tests (Stage 5). The FEP gained a datastore for the
 * authorization audit log (DEF-1C-24), so any {@code @SpringBootTest} context now needs a
 * DataSource. The {@code test} profile provides an in-memory H2 (PostgreSQL mode) datasource
 * (see {@code application-test.yml}) so the FEP suite has no Docker dependency — engine and
 * card already verify the real-Postgres Flyway path under Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractFepIntegrationTest {
}
