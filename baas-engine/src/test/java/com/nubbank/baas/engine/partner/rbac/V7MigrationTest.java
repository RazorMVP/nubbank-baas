package com.nubbank.baas.engine.partner.rbac;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class V7MigrationTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired JdbcTemplate jdbc;

    @Test
    void v7_addsMarkers_seedsBuiltInRoles_andManagePermissions() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("V7 Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("v7@test.com").build());
        provisioning.provision(org.getId(), schema);

        List<Map<String, Object>> cols = jdbc.queryForList(
            "select column_name from information_schema.columns " +
            "where table_schema = ? and table_name = 'roles'", schema);
        Set<String> names = new HashSet<>();
        cols.forEach(c -> names.add((String) c.get("column_name")));
        assertThat(names).contains("built_in", "role_scope", "is_superuser");

        Integer superusers = jdbc.queryForObject(
            "select count(*) from " + schema + ".roles where name = 'PARTNER_ADMIN' and is_superuser = true",
            Integer.class);
        assertThat(superusers).isEqualTo(1);

        Integer partnerRoles = jdbc.queryForObject(
            "select count(*) from " + schema + ".roles " +
            "where role_scope = 'PARTNER' and name in ('PARTNER_MAKER','PARTNER_APPROVER','PARTNER_VIEWER')",
            Integer.class);
        assertThat(partnerRoles).isEqualTo(3);

        Integer managePerms = jdbc.queryForObject(
            "select count(*) from " + schema + ".permissions " +
            "where code in ('MANAGE_PARTNER_USERS','MANAGE_ROLES','MANAGE_API_KEYS')", Integer.class);
        assertThat(managePerms).isEqualTo(3);

        Integer makerCreate = jdbc.queryForObject(
            "select count(*) from " + schema + ".role_permissions rp " +
            "join " + schema + ".roles r on r.id = rp.role_id " +
            "join " + schema + ".permissions p on p.id = rp.permission_id " +
            "where r.name = 'PARTNER_MAKER' and p.code = 'CREATE_ACCOUNT'", Integer.class);
        assertThat(makerCreate).isEqualTo(1);
    }
}
