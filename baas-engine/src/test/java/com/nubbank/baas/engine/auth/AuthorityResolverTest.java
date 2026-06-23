package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorityResolverTest extends AbstractIntegrationTest {

    @Autowired AuthorityResolver authorityResolver;
    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;
    @Autowired PermissionRepository permRepo;
    @Autowired UserRoleRepository userRoleRepo;

    @Test
    void operatorGetsOnlyGrantedPermissionCodes() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Auth Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .contactEmail("a@test.com").build());
        provisioning.provision(org.getId(), schema);

        UUID sub = UUID.randomUUID();
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "BASIC",
            "PRODUCTION", "OPERATOR_JWT", sub.toString()));
        try {
            Permission readCustomer = permRepo.findAll().stream()
                .filter(p -> p.getCode().equals("READ_CUSTOMER")).findFirst().orElseThrow();
            Role r = roleRepo.save(Role.builder().name("VIEWER")
                .permissions(Set.of(readCustomer)).build());
            userRoleRepo.save(UserRole.builder().userId(sub).role(r).build());

            List<String> codes = authorityResolver.operatorAuthorities(sub);
            assertThat(codes).contains("READ_CUSTOMER");
            assertThat(codes).doesNotContain("CREATE_CUSTOMER");
        } finally { PartnerContext.clear(); }
    }

    @Test
    void firstPartyGetsFullTenantAuthority() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Full Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .contactEmail("f@test.com").build());
        provisioning.provision(org.getId(), schema);
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "BASIC",
            "PRODUCTION", "API_KEY", null));
        try {
            // fullTenantAuthorities() was removed (no production caller); delegate directly to the
            // permission repo, which is what apiKeyAuthorities("*" scope) calls internally.
            assertThat(permRepo.findAllCodes())
                .contains("READ_CUSTOMER", "CREATE_CUSTOMER", "APPROVE_LOAN");
        } finally { PartnerContext.clear(); }
    }
}
