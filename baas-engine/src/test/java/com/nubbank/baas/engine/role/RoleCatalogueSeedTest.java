package com.nubbank.baas.engine.role;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RoleCatalogueSeedTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;

    @Test
    void provisionSeeds30Roles() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Seed Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .contactEmail("s@test.com").build());
        provisioning.provision(org.getId(), schema);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "BASIC",
            "PRODUCTION", "API_KEY", null));
        try {
            List<String> names = roleRepo.findAll().stream().map(Role::getName).toList();
            assertThat(names).hasSize(30);
            assertThat(names).contains("PARTNER_ADMIN", "TELLER", "CREDIT_APPROVER",
                "REMITTANCE_OFFICER", "AUDITOR_READONLY");
            Role teller = roleRepo.findAll().stream()
                .filter(r -> r.getName().equals("TELLER")).findFirst().orElseThrow();
            assertThat(teller.getPermissions()).extracting("code").contains("DEPOSIT", "WITHDRAW");
        } finally { PartnerContext.clear(); }
    }
}
