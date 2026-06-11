package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerKycEventsApiTest extends AbstractIntegrationTest {
    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    private String jwt; private String schemaName; private UUID orgId;

    @BeforeEach void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Evt").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("evt@partner.com").build());
        orgId = org.getId(); provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "evt@partner.com", "PARTNER_ADMIN",
            org.getId().toString(), "Evt", schemaName, "SANDBOX", "SANDBOX");
    }
    private HttpHeaders auth() { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON); return h; }
    private UUID createCustomer() {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(Map.of("firstName", "A", "lastName", "B"), auth()), Map.class);
        return UUID.fromString((String) ((Map<?, ?>) r.getBody().get("data")).get("id"));
    }

    @Test
    void kycEvents_returnsHistoryNewestFirst() {
        UUID id = createCustomer();
        restTemplate.exchange("/baas/v1/customers/" + id + "/activate", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "verified"), auth()), Map.class);
        restTemplate.exchange("/baas/v1/customers/" + id + "/suspend", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "fraud flag"), auth()), Map.class);

        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id + "/kyc-events",
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> events = (List<Map<String,Object>>) r.getBody().get("data");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("toStatus")).isEqualTo("SUSPENDED");
        assertThat(events.get(0).get("reason")).isEqualTo("fraud flag");
        assertThat(events.get(1).get("toStatus")).isEqualTo("ACTIVE");
    }

    @Test
    void kycEvents_emptyForNewCustomer() {
        UUID id = createCustomer();
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id + "/kyc-events",
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> events = (List<Map<String,Object>>) r.getBody().get("data");
        assertThat(events).isEmpty();
    }
}
