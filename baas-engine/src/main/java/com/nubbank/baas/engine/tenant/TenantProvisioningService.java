package com.nubbank.baas.engine.tenant;

import com.nubbank.baas.engine.partner.PartnerOrganizationRepository;
import com.nubbank.baas.engine.partner.rbac.PartnerRbacReconciler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
    private final CardProvisioningClient cardProvisioningClient;
    private final PartnerOrganizationRepository orgRepo;

    // @Lazy breaks the construction cycle: PartnerRbacReconciler depends on this service.
    @Lazy @Autowired private PartnerRbacReconciler reconciler;

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
            migrateTenant(schemaName);
            migrateTenant(sandboxSchema);

            // DEF-1C-22: provision card schema objects into the SAME partner schema. A failure
            // here propagates to the catch below, so a partner is never left half-provisioned.
            cardProvisioningClient.provision(partnerId, schemaName);

            jdbcTemplate.update(
                "INSERT INTO public.schema_provision_log " +
                "(partner_id, schema_name, status, completed_at) VALUES (?, ?, 'SUCCESS', ?)",
                partnerId, schemaName, Timestamp.from(Instant.now()));

            log.info("Schema {} provisioned successfully", schemaName);

            // Grant RBAC to any users already registered for this org (e.g. the registering admin).
            orgRepo.findById(partnerId).ifPresent(reconciler::reconcileOrg);

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

    public void migrateTenant(String schemaName) {
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
