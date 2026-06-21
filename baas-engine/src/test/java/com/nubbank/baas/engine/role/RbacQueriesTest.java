package com.nubbank.baas.engine.role;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RbacQueriesTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;
    @Autowired UserRoleRepository userRoleRepo;

    @Test
    void superuserCheck_and_scopeFilter() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Q").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema).contactEmail("q@t.com").build());
        provisioning.provision(org.getId(), schema);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema,
            "SANDBOX", "SANDBOX", "JWT", null));
        try {
            UUID admin = UUID.randomUUID();
            UUID viewer = UUID.randomUUID();
            UUID adminRoleId = roleRepo.findAll().stream()
                .filter(r -> r.getName().equals("PARTNER_ADMIN")).findFirst().orElseThrow().getId();
            UUID viewerRoleId = roleRepo.findAll().stream()
                .filter(r -> r.getName().equals("PARTNER_VIEWER")).findFirst().orElseThrow().getId();
            userRoleRepo.save(UserRole.builder().userId(admin).role(roleRepo.findById(adminRoleId).get()).build());
            userRoleRepo.save(UserRole.builder().userId(viewer).role(roleRepo.findById(viewerRoleId).get()).build());

            assertThat(userRoleRepo.existsSuperuserRoleByUserId(admin)).isTrue();
            assertThat(userRoleRepo.existsSuperuserRoleByUserId(viewer)).isFalse();
            assertThat(roleRepo.findByRoleScopeIn(List.of("PARTNER", "SHARED")))
                .extracting(Role::getName).contains("PARTNER_MAKER", "PARTNER_VIEWER", "PARTNER_ADMIN")
                .doesNotContain("TELLER"); // operator-scoped
            assertThat(userRoleRepo.countDistinctUsersWithRole("PARTNER_ADMIN")).isEqualTo(1);
        } finally {
            PartnerContext.clear();
        }
    }
}
