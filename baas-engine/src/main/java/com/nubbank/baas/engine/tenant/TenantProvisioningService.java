package com.nubbank.baas.engine.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Provision a new partner schema synchronously.
     * Creates the PostgreSQL production schema and a sandbox schema.
     * Runs the tenant Flyway migrations on both.
     * Records the result in public.schema_provision_log.
     */
    public void provision(UUID partnerId, String schemaName) {
        log.info("Provisioning schema {} for partner {}", schemaName, partnerId);

        if (!schemaName.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }

        String sandboxSchema = schemaName.startsWith("partner_")
            ? schemaName.replace("partner_", "sandbox_")
            : "sandbox_" + schemaName;

        try {
            createSchema(schemaName);
            createSchema(sandboxSchema);
            runTenantMigrations(schemaName);
            runTenantMigrations(sandboxSchema);

            jdbcTemplate.update(
                "INSERT INTO public.schema_provision_log " +
                "(partner_id, schema_name, status, completed_at) VALUES (?, ?, 'SUCCESS', ?)",
                partnerId, schemaName, Timestamp.from(Instant.now()));

            log.info("Schema {} provisioned successfully", schemaName);

        } catch (Exception ex) {
            log.error("Failed to provision schema {}: {}", schemaName, ex.getMessage(), ex);
            try {
                jdbcTemplate.update(
                    "INSERT INTO public.schema_provision_log " +
                    "(partner_id, schema_name, status, error_message) VALUES (?, ?, 'FAILED', ?)",
                    partnerId, schemaName, ex.getMessage());
            } catch (Exception logEx) {
                log.error("Failed to write provision log", logEx);
            }
            throw new RuntimeException("Schema provisioning failed for " + schemaName, ex);
        }
    }

    /**
     * Asynchronous provisioning — called from register endpoint so the HTTP response
     * is not blocked by schema creation. Status can be polled via schema_provision_log.
     */
    @Async
    public void provisionAsync(UUID partnerId, String schemaName) {
        provision(partnerId, schemaName);
    }

    private void createSchema(String schemaName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
        }
    }

    private void runTenantMigrations(String schemaName) {
        Flyway tenantFlyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas(schemaName)
            .defaultSchema(schemaName)
            .locations("classpath:db/migration/tenant")
            .baselineOnMigrate(true)
            .load();
        tenantFlyway.migrate();
    }
}
