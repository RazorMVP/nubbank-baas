package com.nubbank.baas.engine.social;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MakerCheckerControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;
    private UUID userId;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("MC Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("mc@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        userId = UUID.randomUUID();
        jwt = jwtService.issue(userId.toString(), "mc@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "MC Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void createMakerCheckerRequest_and_approve() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        // Create request — madeByUserId comes from JWT sub
        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/makercheckers",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "entityType", "LOAN",
                "action", "APPROVE_LOAN",
                "commandAsJson", "{\"loanId\":\"abc-123\",\"amount\":100000}"
            ), h), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();
        assertThat(((Map<?, ?>) createResp.getBody().get("data")).get("status")).isEqualTo("PENDING");

        // Approve with checker user ID
        UUID checkerUserId = UUID.randomUUID();
        ResponseEntity<Map> approveResp = restTemplate.exchange(
            "/baas/v1/makercheckers/" + requestId + "?command=approve&checkerUserId=" + checkerUserId,
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) approveResp.getBody().get("data")).get("status")).isEqualTo("APPROVED");
    }
}
