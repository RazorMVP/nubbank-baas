package com.nubbank.baas.engine.operator;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * DEF-1C-28 — {@code GET /baas/v1/operators/me} returns the authenticated operator's
 * identity (id, auth mode, partner, tier/environment) and their resolved permission
 * authorities. This is what lets a PKCE backoffice operator discover their server-side
 * authorities, which are NOT carried in the Keycloak token.
 */
class OperatorMeControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;
    private String schemaName;
    private UUID orgId;
    private String operatorSub;

    @BeforeEach
    void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = PartnerOrganization.builder()
            .name("Me Test Partner").status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX)
            .schemaName(schemaName).contactEmail("me@partner.com").build();
        org = orgRepo.save(org);
        orgId = org.getId();
        provisioningService.provision(org.getId(), schemaName);

        operatorSub = UUID.randomUUID().toString();
        jwt = jwtService.issue(operatorSub, "me@partner.com", "PARTNER_ADMIN",
            org.getId().toString(), "Me Test Partner", schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void me_authenticated_returnsIdentityAndAuthorities() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/operators/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("operatorId")).isEqualTo(operatorSub);
        assertThat(data.get("authMode")).isEqualTo("JWT");
        assertThat(data.get("partnerId")).isEqualTo(orgId.toString());
        assertThat(data.get("environment")).isEqualTo("SANDBOX");

        @SuppressWarnings("unchecked")
        List<String> authorities = (List<String>) data.get("authorities");
        // First-party partner JWT gets full tenant authority — must include the customer-read code.
        assertThat(authorities).contains("READ_CUSTOMER");

        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) data.get("roles");
        // No user_roles rows for a first-party JWT subject — roles is present but empty.
        assertThat(roles).isEmpty();
    }

    @Test
    void me_unauthenticated_returns401() {
        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/operators/me", HttpMethod.GET, HttpEntity.EMPTY, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
