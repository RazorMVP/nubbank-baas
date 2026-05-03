package com.nubbank.baas.engine.campaign;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CampaignControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Campaign Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("camp@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "camp@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Campaign Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void createSmsCampaign_activate() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/smscampaigns",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "name", "Welcome Campaign",
                "campaignType", "ALL",
                "triggerType", "DIRECT",
                "messageTemplate", "Welcome to {{name}}!"
            ), h), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();

        ResponseEntity<Map> activateResp = restTemplate.exchange(
            "/baas/v1/smscampaigns/" + id + "?command=activate",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(((Map<?, ?>) activateResp.getBody().get("data")).get("status"))
            .isEqualTo("ACTIVE");
    }

    @Test
    void createReportMailingJob_run() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/reportmailingjobs",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "name", "Monthly Loan Report",
                "reportName", "LoanPortfolio",
                "emailRecipients", "cfo@bank.com,risk@bank.com",
                "outputType", "CSV",
                "recurrence", "FREQ=MONTHLY;BYDAY=1MO"
            ), h), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();

        ResponseEntity<Map> runResp = restTemplate.exchange(
            "/baas/v1/reportmailingjobs/" + id + "?command=run",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(runResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) ((Map<?, ?>) runResp.getBody().get("data")).get("runCount"))
            .intValue()).isEqualTo(1);
    }
}
