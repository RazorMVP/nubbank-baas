package com.nubbank.baas.engine.tenant;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.PartnerEnvironment;
import com.nubbank.baas.engine.partner.PartnerOrganization;
import com.nubbank.baas.engine.partner.PartnerOrganizationRepository;
import com.nubbank.baas.engine.partner.PartnerStatus;
import com.nubbank.baas.engine.partner.PartnerTier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class TenantProvisioningTest extends AbstractIntegrationTest {

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private PartnerOrganizationRepository partnerRepo;

    @Autowired
    private DataSource dataSource;

    /** Create a minimal PartnerOrganization row so that schema_provision_log FK is satisfied. */
    private PartnerOrganization createPartner(String schemaName) {
        PartnerOrganization org = PartnerOrganization.builder()
            .name("Test Partner " + UUID.randomUUID())
            .status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.SANDBOX)
            .schemaName(schemaName)
            .build();
        return partnerRepo.save(org);
    }

    @Test
    void provision_createsSchemaWithTenantTables() throws Exception {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization partner = createPartner(schemaName);
        UUID partnerId = partner.getId();

        provisioningService.provision(partnerId, schemaName);

        // Verify production schema has customers table
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, schemaName, "customers", null)) {
            assertThat(rs.next()).as("customers table should exist in schema " + schemaName).isTrue();
        }

        // Verify accounts table
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, schemaName, "accounts", null)) {
            assertThat(rs.next()).as("accounts table should exist").isTrue();
        }

        // Verify sandbox schema also created
        String sandboxSchema = schemaName.replace("partner_", "sandbox_");
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, sandboxSchema, "customers", null)) {
            assertThat(rs.next()).as("sandbox customers table should exist").isTrue();
        }
    }

    @Test
    void provision_isolatesDataBetweenPartners() throws Exception {
        String schema1 = "partner_" + UUID.randomUUID().toString().replace("-", "");
        String schema2 = "partner_" + UUID.randomUUID().toString().replace("-", "");
        provisioningService.provision(createPartner(schema1).getId(), schema1);
        provisioningService.provision(createPartner(schema2).getId(), schema2);

        // Insert a row in schema1
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO " + schema1 + ", public");
            conn.createStatement().execute(
                "INSERT INTO customers (first_name_encrypted, last_name_encrypted) " +
                "VALUES ('enc_john', 'enc_doe')");
        }

        // schema2 should NOT see schema1's row
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO " + schema2 + ", public");
            ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM customers");
            rs.next();
            assertThat(rs.getInt(1)).as("schema2 should have 0 customers").isZero();
        }

        // schema1 SHOULD have its row
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO " + schema1 + ", public");
            ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM customers");
            rs.next();
            assertThat(rs.getInt(1)).as("schema1 should have 1 customer").isEqualTo(1);
        }
    }
}
