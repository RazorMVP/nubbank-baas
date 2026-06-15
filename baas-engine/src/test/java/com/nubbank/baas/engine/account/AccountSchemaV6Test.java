package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AccountSchemaV6Test extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void v6_addsStatusEventsTableAndUpdateAccountPermission() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("V6").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("v6@test.com").build());
        provisioningService.provision(org.getId(), schema);

        assertThat(jdbc.queryForObject("SELECT to_regclass(?)", String.class,
            schema + ".account_status_events")).isNotNull();

        Integer perm = jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".permissions WHERE code = 'UPDATE_ACCOUNT'",
            Integer.class);
        assertThat(perm).isEqualTo(1);

        // PARTNER_ADMIN must hold the new permission (V3 granted only the V1/V2 codes).
        Integer grant = jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".role_permissions rp "
            + "JOIN " + schema + ".roles r ON r.id = rp.role_id "
            + "JOIN " + schema + ".permissions p ON p.id = rp.permission_id "
            + "WHERE r.name = 'PARTNER_ADMIN' AND p.code = 'UPDATE_ACCOUNT'",
            Integer.class);
        assertThat(grant).isEqualTo(1);
    }
}
