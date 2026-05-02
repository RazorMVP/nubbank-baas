package com.nubbank.baas.engine.report;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReportControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Report Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("report@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "report@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Report Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void listSeededReports_returns5OrMore() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/reports", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> content = (List<?>) ((Map<?, ?>) resp.getBody().get("data")).get("content");
        assertThat(content).hasSizeGreaterThanOrEqualTo(5); // seeded by V2 migration
    }

    @Test
    void runAccountSummaryReport_returns200() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/runreports/AccountSummary",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // data is a list of result rows (empty for fresh schema)
        assertThat(resp.getBody().get("data")).isInstanceOf(List.class);
    }

    @Test
    void runReport_withSqlInjection_returns400() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/runreports/TransactionHistory?startDate=2026-01-01'; DROP TABLE accounts; --&endDate=2026-12-31",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
