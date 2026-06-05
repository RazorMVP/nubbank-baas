package com.nubbank.baas.fep.audit;

import com.nubbank.baas.fep.AbstractFepIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Stage 5 Task 13 — proves the FEP datastore wires up: the context loads with a DataSource and
 * Flyway has created {@code fep.authorization_log} (portable existence check — works on H2 and PG).
 */
class FepFlywayContextTest extends AbstractFepIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void authorizationLogTableExists() {
        assertThatCode(() -> {
            Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fep.authorization_log", Long.class);
            assertThat(rows).isEqualTo(0L);
        }).doesNotThrowAnyException();
    }
}
