package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class OperatorProvisioningServiceTest extends AbstractIntegrationTest {

    @Autowired OperatorProvisioningService service;
    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;
    @Autowired PermissionRepository permRepo;
    @Autowired UserRoleRepository userRoleRepo;

    @Test
    void revokeAllGrants_removesUserRoles() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Deprov Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .contactEmail("d@test.com").build());
        provisioning.provision(org.getId(), schema);

        UUID sub = UUID.randomUUID();
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "BASIC",
            "PRODUCTION", "API_KEY", null));
        try {
            Role r = roleRepo.save(Role.builder().name("TMP").permissions(Set.of()).build());
            userRoleRepo.save(UserRole.builder().userId(sub).role(r).build());
            assertThat(userRoleRepo.findByUserId(sub)).isNotEmpty();

            service.revokeAllGrants(sub);

            assertThat(userRoleRepo.findByUserId(sub)).isEmpty();
        } finally { PartnerContext.clear(); }
    }
}
