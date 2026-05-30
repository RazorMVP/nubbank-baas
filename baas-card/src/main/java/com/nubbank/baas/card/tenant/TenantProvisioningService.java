package com.nubbank.baas.card.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

/**
 * Provisions a partner's card schemas.
 *
 * Creates the production {@code partner_{uuid}} schema and a {@code sandbox_{uuid}}
 * schema (idempotent {@code CREATE SCHEMA IF NOT EXISTS}), then runs the
 * card-specific tenant migrations on both.
 *
 * baas-card shares the same PostgreSQL DB as baas-engine but uses its OWN Flyway
 * history table ({@code flyway_schema_history_card}) so it never collides with
 * engine's {@code flyway_schema_history}. Card does NOT write engine's
 * {@code schema_provision_log} — that is engine bookkeeping; card shares the DB
 * but must not couple to it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    private final DataSource dataSource;

    /**
     * Provision a partner's card schemas synchronously.
     */
    public void provision(UUID partnerId, String schemaName) {
        log.info("Provisioning card schema {} for partner {}", schemaName, partnerId);

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
            log.info("Card schema {} provisioned successfully", schemaName);
        } catch (Exception ex) {
            log.error("Failed to provision card schema {}: {}", schemaName, ex.getMessage(), ex);
            throw new RuntimeException("Card schema provisioning failed for " + schemaName, ex);
        }
    }

    /**
     * Asynchronous provisioning — does not block the HTTP response.
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
            .locations("classpath:db/migration/card-tenant")   // card tenant migrations
            .table("flyway_schema_history_card")               // separate history; never collide with engine
            .baselineOnMigrate(true)
            .load();
        tenantFlyway.migrate();
    }
}
