package com.nubbank.baas.engine.partner.user;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerUserControllerTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository userRepo;
    @Autowired TenantProvisioningService provisioning;

    record Ctx(PartnerOrganization org, String schema, String adminJwt) {}

    private Ctx newOrgWithAdmin() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("U").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("u@t.com").build());
        provisioning.provision(org.getId(), schema);
        return new Ctx(org, schema, adminJwt(org, schema));
    }
    private HttpHeaders auth(String t){ HttpHeaders h=new HttpHeaders(); h.setBearerAuth(t); h.setContentType(MediaType.APPLICATION_JSON); return h; }

    @SuppressWarnings("unchecked")
    @Test
    void admin_createsMakerUser() {
        Ctx c = newOrgWithAdmin();
        ResponseEntity<Map> roles = restTemplate.exchange("/baas/v1/roles", HttpMethod.GET,
            new HttpEntity<>(auth(c.adminJwt())), Map.class);
        String makerRoleId = ((List<Map<String,Object>>) roles.getBody().get("data")).stream()
            .filter(r -> r.get("name").equals("PARTNER_MAKER")).findFirst().orElseThrow().get("id").toString();

        ResponseEntity<Map> created = restTemplate.exchange("/baas/v1/partner-users", HttpMethod.POST,
            new HttpEntity<>(Map.of("email","maker@u.com","password","secret12",
                "roleIds", List.of(makerRoleId)), auth(c.adminJwt())), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void createUser_withNoRoles_is400() {
        Ctx c = newOrgWithAdmin();
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/partner-users", HttpMethod.POST,
            new HttpEntity<>(Map.of("email","x@u.com","password","secret12","roleIds", List.of()),
                auth(c.adminJwt())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void crossOrg_userFetch_is404() {
        Ctx a = newOrgWithAdmin();
        Ctx b = newOrgWithAdmin();
        PartnerUser bUser = userRepo.save(PartnerUser.builder().organization(b.org())
            .email("other@b.com").passwordHash("x").role("PARTNER_ADMIN").active(true).build());
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/partner-users/" + bUser.getId(),
            HttpMethod.GET, new HttpEntity<>(auth(a.adminJwt())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
