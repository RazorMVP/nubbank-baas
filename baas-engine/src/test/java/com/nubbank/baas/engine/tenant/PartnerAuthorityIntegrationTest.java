package com.nubbank.baas.engine.tenant;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerAuthorityIntegrationTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired com.nubbank.baas.engine.tenant.TenantProvisioningService provisioning;

    private String tokenFor(String schema, UUID userId, PartnerOrganization org) {
        return partnerJwtService.issue(userId.toString(), "u@t.com", "PARTNER_USER",
            org.getId().toString(), org.getName(), schema, "PRODUCTION", "PRODUCTION");
    }

    @Test
    void unassignedPartnerUser_isDenied_viewerCanRead() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Z").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("z@t.com").build());
        provisioning.provision(org.getId(), schema);

        UUID unassigned = UUID.randomUUID();
        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(tokenFor(schema, unassigned, org));
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts?page=0&size=20",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // READ_ACCOUNT not held

        UUID viewer = UUID.randomUUID();
        com.nubbank.baas.engine.tenant.PartnerContext.set(new com.nubbank.baas.engine.tenant.PartnerContext(
            org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { userRoleRepo.save(UserRole.builder().userId(viewer)
            .role(roleRepo.findByName("PARTNER_VIEWER").orElseThrow()).build()); }
        finally { com.nubbank.baas.engine.tenant.PartnerContext.clear(); }

        HttpHeaders hv = new HttpHeaders(); hv.setBearerAuth(tokenFor(schema, viewer, org));
        assertThat(restTemplate.exchange("/baas/v1/accounts?page=0&size=20",
            HttpMethod.GET, new HttpEntity<>(hv), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // sanity: the adminJwt helper yields full authority (it can read accounts too)
        assertThat(restTemplate.exchange("/baas/v1/accounts?page=0&size=20",
            HttpMethod.GET, new HttpEntity<>(headerBearer(adminJwt(org, schema))), Map.class)
            .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpHeaders headerBearer(String jwt) { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(jwt); return h; }
}
