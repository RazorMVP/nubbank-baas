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
    void addsAccountStatusEventsTableAndUpdateAccountPermission() {
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

        // PARTNER_ADMIN is the superuser role (V7 promotes it via is_superuser=true and drops
        // explicit permission rows — authority is resolved by the is_superuser flag, not by
        // individual role_permissions rows, so UPDATE_ACCOUNT is implicitly covered).
        Integer isSuperuser = jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".roles WHERE name = 'PARTNER_ADMIN' AND is_superuser = true",
            Integer.class);
        assertThat(isSuperuser).isEqualTo(1);

        // ACCOUNT_OFFICER must also hold the new permission (V6 grants it to both roles).
        Integer grantOfficer = jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".role_permissions rp "
            + "JOIN " + schema + ".roles r ON r.id = rp.role_id "
            + "JOIN " + schema + ".permissions p ON p.id = rp.permission_id "
            + "WHERE r.name = 'ACCOUNT_OFFICER' AND p.code = 'UPDATE_ACCOUNT'",
            Integer.class);
        assertThat(grantOfficer).isEqualTo(1);
    }
}
