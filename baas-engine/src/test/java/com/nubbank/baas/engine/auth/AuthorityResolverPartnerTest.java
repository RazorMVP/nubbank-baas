package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorityResolverPartnerTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;
    @Autowired UserRoleRepository userRoleRepo;
    @Autowired AuthorityResolver resolver;
    @Autowired JdbcTemplate jdbc;
    @Autowired PartnerApiKeyRepository keyRepo;

    @Test
    void partnerUser_admin_isDynamicFull_others_areScoped() {
        String schema = setupTenant();
        PartnerContext.set(new PartnerContext("x", schema, "SANDBOX", "SANDBOX", "JWT", null));
        try {
            UUID admin = assign("PARTNER_ADMIN");
            UUID viewer = assign("PARTNER_VIEWER");
            UUID unassigned = UUID.randomUUID();

            List<String> adminAuth = resolver.partnerUserAuthorities(admin);
            assertThat(adminAuth).contains("CREATE_ACCOUNT", "MANAGE_ROLES", "READ_ACCOUNT");

            jdbc.update("insert into " + schema + ".permissions(grouping,code) values('x','BRAND_NEW_PERM')");
            assertThat(resolver.partnerUserAuthorities(admin)).contains("BRAND_NEW_PERM");

            assertThat(resolver.partnerUserAuthorities(viewer))
                .contains("READ_ACCOUNT").doesNotContain("CREATE_ACCOUNT");
            assertThat(resolver.partnerUserAuthorities(unassigned)).isEmpty();
        } finally { PartnerContext.clear(); }
    }

    @Test
    void apiKey_star_isFull_explicit_isScoped_empty_isDenied() {
        String schema = setupTenant();
        PartnerOrganization org = orgRepo.findBySchemaName(schema).orElseThrow();
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "SANDBOX", "SANDBOX", "API_KEY", null));
        try {
            UUID full = key(org, "[\"*\"]");
            UUID scoped = key(org, "[\"READ_ACCOUNT\"]");
            UUID none = key(org, "[]");
            assertThat(resolver.apiKeyAuthorities(full)).contains("CREATE_ACCOUNT");
            assertThat(resolver.apiKeyAuthorities(scoped)).containsExactly("READ_ACCOUNT");
            assertThat(resolver.apiKeyAuthorities(none)).isEmpty();
        } finally { PartnerContext.clear(); }
    }

    private String setupTenant() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("AR").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema).contactEmail("ar@t.com").build());
        provisioning.provision(org.getId(), schema);
        return schema;
    }

    private UUID assign(String roleName) {
        UUID user = UUID.randomUUID();
        Role r = roleRepo.findByName(roleName).orElseThrow();
        userRoleRepo.save(UserRole.builder().userId(user).role(r).build());
        return user;
    }

    private UUID key(PartnerOrganization org, String scopesJson) {
        PartnerApiKey k = PartnerApiKey.builder().organization(org)
            .keyHash(UUID.randomUUID().toString()).keyPrefix("cba_test")
            .scopes(scopesJson).tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX)
            .active(true).build();
        return keyRepo.save(k).getId();
    }
}
