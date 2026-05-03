package com.nubbank.baas.engine.cob;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CobControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("CoB Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("cob@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "cob@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "CoB Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void listCobJobs_returns200() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/jobs", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void triggerJobManually_returns200() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/jobs/arrearsClassificationJob/run",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
