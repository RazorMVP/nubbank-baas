package com.nubbank.baas.engine.openbanking;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ConsentControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("OB Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("ob@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "ob@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "OB Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void createConsent_awaiting_authorisation() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/open-banking/consents", HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "tppClientId", "fintech-app-001",
                "tppName", "AcmePay",
                "scopes", List.of("accounts_read", "balances_read", "transactions_read"),
                "expiryDate", "2027-01-01"
            ), h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("status"))
            .isEqualTo("AWAITING_AUTHORISATION");
    }

    @Test
    void authoriseConsent_then_revoke() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> create = restTemplate.exchange(
            "/baas/v1/open-banking/consents", HttpMethod.POST,
            new HttpEntity<>(Map.of("tppClientId", "app-002", "tppName", "BetaPay",
                "scopes", List.of("payments")), h), Map.class);
        String id = ((Map<?, ?>) create.getBody().get("data")).get("id").toString();

        // Authorise
        ResponseEntity<Map> auth = restTemplate.exchange(
            "/baas/v1/open-banking/consents/" + id + "/authorise",
            HttpMethod.PUT, new HttpEntity<>(h), Map.class);
        assertThat(((Map<?, ?>) auth.getBody().get("data")).get("status")).isEqualTo("AUTHORISED");

        // Revoke
        restTemplate.exchange("/baas/v1/open-banking/consents/" + id,
            HttpMethod.DELETE, new HttpEntity<>(h), Map.class);

        // Get should show REVOKED
        ResponseEntity<Map> get = restTemplate.exchange(
            "/baas/v1/open-banking/consents/" + id,
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(((Map<?, ?>) get.getBody().get("data")).get("status")).isEqualTo("REVOKED");
    }
}
